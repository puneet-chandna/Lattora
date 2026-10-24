#!/usr/bin/env python3
"""Versioned static scenario configuration: strict JSON in, resolved document, presets, one campaign.

Every rejection is the engine's own JSON-path-addressed sentence, diagnosed in the
same order (structure, unknown/duplicate field, then field by field). JSON numbers
parse to int when exactly integral and to float otherwise; the resolved document
keeps the engine's key order, value types and canonical text.
"""
import argparse
from copy import deepcopy
from datetime import datetime, timezone
import hashlib
import io
import json
import math
from decimal import Decimal
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tempfile
import time

import research_runner as shared
from statistics_validator import JavaRandom, seed

ROOT = Path(__file__).resolve().parents[1]
CONFIG_VERSION = 1
PHASE = 'scenario'
SCENARIO_SCHEMA_VERSION = 2
PROTOCOL = 'static_scenario_v2'
OBJECTIVE_VERSION = 1
POWER_NORMALIZATION = 'sum_host_max_w'
UTILIZATION_NORMALIZATION = 'reserved_peak_fraction'
SLA_MODEL = 'unsupported_constant_full'
SLA_UNSUPPORTED = ('objective.kind sla is unsupported: constant full utilization predicts zero SLA violations for '
                   'every feasible placement; use power, utilization or weighted until a contention model is supported')
ALGORITHMS = ('HO', 'GA', 'FirstFit', 'BestFit')
SEARCH_ALGORITHMS = ('HO', 'GA')
CONFIG_NAME = '[a-z][a-z0-9._-]{0,63}'
LIMITS = {'MAX_HOST_CLASSES': 16, 'MAX_HOSTS': 100000, 'MAX_VMS': 100000, 'MAX_REPLICATIONS': 10000,
          'MAX_DECLARED_VALUES': 4096, 'MIN_SECONDS': 1, 'MAX_SECONDS': 1440}
# The 0.125 s minimum event interval only divides declared per-PE MIPS exactly for multiples of 8.
EXACT_EVENT_DIVISOR = 8
INT_MAX = (1 << 31) - 1
LONG_MAX = (1 << 63) - 1
LONG_MIN = -(1 << 63)
DEFAULT_HEAP_MIB = 1024
MODEL_LIMITS = ('Only constant full utilization (1.0), one cloudlet per VM, VmSchedulerSpaceShared, '
                'CloudletSchedulerSpaceShared, no migrations, no overcommit, a single simultaneous release and a linear '
                "idle-to-max power curve over the host's own total MIPS are simulated. PE, MIPS, RAM, bandwidth and storage "
                'limits are static reservation admission checks; they are not simulated network or storage contention.')
EVIDENCE_LIMIT = ('Scalar traces check order, budgets and eligible-best consistency; they cannot '
                  'independently replay discarded candidates or guarantee detection of coherent non-best scalar tampering.')
ALLOCATION_TIMING = ('End-to-end search including synchronous buffered scalar trace writing; '
                     'not pure CPU or directly comparable to frozen allocation timing.')
NAME_PATTERN = re.compile(CONFIG_NAME)
NUMBER_PATTERN = re.compile(r'-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?')
# Engine field order; every JSON path and rejection message follows it.
TOP_KEYS = ('config_version', 'name', 'master_seed', 'replications', 'algorithms', 'objective', 'optimizer', 'hosts',
            'vms', 'workload')
HOST_KEYS = ('count', 'pes', 'mips_per_pe', 'ram_mib', 'bw_mbps', 'storage_mib', 'idle_w', 'max_w')
OPTIMIZER_KEYS = ('population', 'iterations')
OBJECTIVE_KEYS = ('kind', 'weights')
WEIGHT_KEYS = ('power', 'utilization')
WORKLOAD_KEYS = ('length_seconds', 'utilization', 'release_seconds')
DEMANDS = ('pes', 'mips_per_pe', 'ram_mib', 'bw_mbps', 'storage_mib')
DIMENSIONS = ('pes', 'mips', 'ram_mib', 'bw_mbps', 'storage_mib')
HOST_BOUNDS = {'count': (1, LIMITS['MAX_HOSTS']), 'pes': (1, INT_MAX), 'mips_per_pe': (1, INT_MAX),
               'ram_mib': (1, INT_MAX), 'bw_mbps': (1, INT_MAX), 'storage_mib': (1, INT_MAX),
               'idle_w': (0, INT_MAX), 'max_w': (0, INT_MAX)}
NONFINITE = ('NaN', 'Infinity', '-Infinity')
ESCAPES = {'"': '"', '\\': '\\', '/': '/', 'b': '\b', 'f': '\f', 'n': '\n', 'r': '\r', 't': '\t'}

FEASIBLE = {'config_version': 1, 'name': 'known-answer-feasible', 'master_seed': 123456, 'replications': 1,
            'algorithms': ['HO', 'GA', 'FirstFit', 'BestFit'], 'optimizer': {'population': 10, 'iterations': 4},
            'hosts': [{'count': 2, 'pes': 8, 'mips_per_pe': 3000, 'ram_mib': 16384, 'bw_mbps': 4000,
                       'storage_mib': 40000, 'idle_w': 100, 'max_w': 200}],
            'vms': {'count': 2, 'pes': [1], 'mips_per_pe': [3000], 'ram_mib': [2048], 'bw_mbps': [1000],
                    'storage_mib': [10000]},
            'workload': {'length_seconds': {'min': 30, 'max': 30}, 'utilization': 1.0, 'release_seconds': 0.0}}
TIGHT = {'config_version': 1, 'name': 'known-answer-tight', 'master_seed': 123456, 'replications': 1,
         'algorithms': ['HO', 'GA', 'FirstFit', 'BestFit'], 'optimizer': {'population': 10, 'iterations': 4},
         'hosts': [{'count': 2, 'pes': 2, 'mips_per_pe': 3000, 'ram_mib': 8192, 'bw_mbps': 2000,
                    'storage_mib': 20000, 'idle_w': 100, 'max_w': 200}],
         'vms': {'count': 2, 'pes': [2], 'mips_per_pe': [3000], 'ram_mib': [8192], 'bw_mbps': [2000],
                 'storage_mib': [20000]},
         'workload': {'length_seconds': {'min': 60, 'max': 60}, 'utilization': 1.0, 'release_seconds': 0.0}}
TRADEOFF = {'config_version': 1, 'name': 'known-answer-tradeoff', 'master_seed': 123456, 'replications': 1,
            'algorithms': ['HO', 'GA'], 'optimizer': {'population': 10, 'iterations': 4},
            'objective': {'kind': 'power'},
            # Low-power host first: two VMs fit together at 100% reserved utilization and 20 W steady.
            # The six-PE host is more expensive but halves the reserved peak; selection flips with the objective.
            'hosts': [{'count': 1, 'pes': 2, 'mips_per_pe': 3000, 'ram_mib': 8192, 'bw_mbps': 2000,
                       'storage_mib': 20000, 'idle_w': 10, 'max_w': 20},
                      {'count': 1, 'pes': 6, 'mips_per_pe': 3000, 'ram_mib': 8192, 'bw_mbps': 2000,
                       'storage_mib': 20000, 'idle_w': 100, 'max_w': 200}],
            'vms': {'count': 2, 'pes': [1], 'mips_per_pe': [3000], 'ram_mib': [2048], 'bw_mbps': [1000],
                    'storage_mib': [10000]},
            'workload': {'length_seconds': {'min': 30, 'max': 30}, 'utilization': 1.0, 'release_seconds': 0.0}}
INFEASIBLE = {'config_version': 1, 'name': 'known-answer-infeasible', 'master_seed': 123456, 'replications': 1,
              'algorithms': ['HO', 'GA', 'FirstFit', 'BestFit'], 'optimizer': {'population': 10, 'iterations': 4},
              'hosts': [{'count': 1, 'pes': 4, 'mips_per_pe': 3000, 'ram_mib': 4096, 'bw_mbps': 1000,
                         'storage_mib': 10000, 'idle_w': 100, 'max_w': 200}],
              'vms': {'count': 2, 'pes': [1], 'mips_per_pe': [3000], 'ram_mib': [4096], 'bw_mbps': [1000],
                      'storage_mib': [10000]},
              'workload': {'length_seconds': {'min': 60, 'max': 60}, 'utilization': 1.0, 'release_seconds': 0.0}}
HETEROGENEOUS = {'config_version': 1, 'name': 'heterogeneous-hosts', 'master_seed': 123456, 'replications': 2,
                 'algorithms': ['HO', 'GA', 'FirstFit', 'BestFit'], 'optimizer': {'population': 10, 'iterations': 4},
                 'hosts': [{'count': 4, 'pes': 32, 'mips_per_pe': 1500, 'ram_mib': 65536, 'bw_mbps': 20000,
                            'storage_mib': 2000000, 'idle_w': 220, 'max_w': 380},
                           {'count': 8, 'pes': 16, 'mips_per_pe': 3000, 'ram_mib': 32768, 'bw_mbps': 10000,
                            'storage_mib': 1000000, 'idle_w': 175, 'max_w': 250},
                           {'count': 6, 'pes': 8, 'mips_per_pe': 2000, 'ram_mib': 16384, 'bw_mbps': 4000,
                            'storage_mib': 250000, 'idle_w': 90, 'max_w': 140}],
                 # The witness places VM i on host i % hostCount, so every declared VM must be admissible on the
                 # slowest class (1500 MIPS/PE); 1000 and 1400 are both exact multiples of the 8-MIPS event divisor.
                 'vms': {'count': 48, 'pes': [1, 2], 'mips_per_pe': [1000, 1400], 'ram_mib': [1024, 2048, 4096],
                         'bw_mbps': [1000], 'storage_mib': [10000]},
                 'workload': {'length_seconds': {'min': 30, 'max': 120}, 'utilization': 1.0, 'release_seconds': 0.0}}
PRESSURE = {'config_version': 1, 'name': 'resource-pressure', 'master_seed': 123456, 'replications': 1,
            'algorithms': ['HO', 'GA', 'FirstFit', 'BestFit'], 'optimizer': {'population': 10, 'iterations': 4},
            'hosts': [{'count': 10, 'pes': 16, 'mips_per_pe': 3000, 'ram_mib': 32768, 'bw_mbps': 10000,
                       'storage_mib': 1000000, 'idle_w': 175, 'max_w': 250}],
            'vms': {'count': 36, 'pes': [2], 'mips_per_pe': [2000], 'ram_mib': [8192], 'bw_mbps': [1000],
                    'storage_mib': [20000]},
            'workload': {'length_seconds': {'min': 60, 'max': 120}, 'utilization': 1.0, 'release_seconds': 0.0}}
PRESETS = {'known-answer-feasible': FEASIBLE, 'known-answer-tight': TIGHT, 'known-answer-tradeoff': TRADEOFF,
           'known-answer-infeasible': INFEASIBLE, 'heterogeneous-hosts': HETEROGENEOUS,
           'resource-pressure': PRESSURE}


class ConfigError(ValueError):
    """A strict configuration rejection whose sentence matches the Java engine byte for byte."""


class UnreadableConfig(ConfigError):
    """The configuration file is missing or cannot be read."""


def preset_config(name):
    """A private copy of one bundled configuration, safe for callers to mutate."""
    if name not in PRESETS:
        raise KeyError(name)
    return deepcopy(PRESETS[name])


def request_json(config):
    """Defaults applied but left unresolved: the document written for a bundled preset."""
    document = deepcopy(config)
    if 'algorithms' not in document:
        document['algorithms'] = list(ALGORITHMS)
    return document


class _Reader:
    """Strict JSON reader: duplicate keys, non-finite numbers and trailing content are rejections."""

    def __init__(self, text):
        self.text, self.index, self.length = text, 0, len(text)

    def error(self, message):
        raise ConfigError('invalid JSON: ' + message)

    def skip(self):
        while self.index < self.length and self.text[self.index] in ' \t\n\r':
            self.index += 1

    def token(self):
        start = self.index
        while self.index < self.length and self.text[self.index] not in ' \t\n\r,]}':
            self.index += 1
        return self.text[start:self.index]

    def value(self, path):
        self.skip()
        if self.index >= self.length:
            self.error('unexpected end of input')
        char = self.text[self.index]
        if char == '{':
            return self.object(path)
        if char == '[':
            return self.array(path)
        if char == '"':
            return self.string()
        literal = self.token()
        if not literal:
            self.error(f'unexpected token {char} at {path or "root"}')
        if literal == 'true':
            return True
        if literal == 'false':
            return False
        if literal == 'null':
            return None
        if literal in NONFINITE:
            self.error('non-finite number')
        if char == '-' or char.isdigit():
            if NUMBER_PATTERN.fullmatch(literal) is None:
                self.error('malformed number ' + literal)
            return _number_value(literal)
        self.error(f'unexpected token {literal} at {path or "root"}')

    def object(self, path):
        self.index += 1
        result = {}
        self.skip()
        if self.index < self.length and self.text[self.index] == '}':
            self.index += 1
            return result
        while True:
            self.skip()
            if self.index >= self.length or self.text[self.index] != '"':
                self.error('expected a string key at ' + (path or 'root'))
            key = self.string()
            child = f'{path}.{key}' if path else key
            self.skip()
            if self.index >= self.length or self.text[self.index] != ':':
                self.error('expected a colon at ' + child)
            self.index += 1
            if key in result:
                raise ConfigError('duplicate field: ' + child)
            result[key] = self.value(child)
            self.skip()
            if self.index >= self.length:
                self.error('unexpected end of input')
            char = self.text[self.index]
            self.index += 1
            if char == '}':
                return result
            if char != ',':
                self.error(f'unexpected token {char} at {child}')

    def array(self, path):
        self.index += 1
        result = []
        self.skip()
        if self.index < self.length and self.text[self.index] == ']':
            self.index += 1
            return result
        while True:
            result.append(self.value(f'{path}[{len(result)}]'))
            self.skip()
            if self.index >= self.length:
                self.error('unexpected end of input')
            char = self.text[self.index]
            self.index += 1
            if char == ']':
                return result
            if char != ',':
                self.error(f'unexpected token {char} at {path}')

    def string(self):
        self.index += 1
        result = []
        while True:
            if self.index >= self.length:
                self.error('unterminated string')
            char = self.text[self.index]
            self.index += 1
            if char == '"':
                return ''.join(result)
            if char != '\\':
                if char < ' ':
                    self.error('unescaped control character in string')
                result.append(char)
                continue
            if self.index >= self.length:
                self.error('unterminated escape')
            escape = self.text[self.index]
            self.index += 1
            if escape == 'u':
                code = self.hexadecimal()
                if 0xD800 <= code <= 0xDBFF and self.text[self.index:self.index + 2] == '\\u':
                    self.index += 2
                    low = self.hexadecimal()
                    result.append(chr(0x10000 + ((code - 0xD800) << 10) + low - 0xDC00) if 0xDC00 <= low <= 0xDFFF
                                  else chr(code) + chr(low))
                else:
                    result.append(chr(code))
            elif escape in ESCAPES:
                result.append(ESCAPES[escape])
            else:
                self.error('invalid escape \\' + escape)

    def hexadecimal(self):
        digits = self.text[self.index:self.index + 4]
        if len(digits) != 4 or re.fullmatch('[0-9a-fA-F]{4}', digits) is None:
            self.error('invalid unicode escape')
        self.index += 4
        return int(digits, 16)


def _number_value(literal):
    """Java-parity numbers: integers stay exact, and a literal only becomes an integer when it names that integer."""
    if re.fullmatch(r'-?[0-9]+', literal):
        return int(literal)
    value = float(literal)
    if math.isfinite(value) and value.is_integer() and Decimal(literal) == int(value):
        return int(value)
    # Keep the rounded float only when the literal names it exactly; otherwise carry the exact decimal.
    if Decimal(literal) == Decimal(repr(value)):
        return value
    return Decimal(literal)


def _parse(text):
    reader = _Reader(text)
    value = reader.value('')
    reader.skip()
    if reader.index != reader.length:
        raise ConfigError('invalid JSON: trailing content after the root object')
    if not isinstance(value, dict):
        raise ConfigError('invalid JSON: the root value must be an object')
    return value


def load_config(path):
    """Read bytes, decode UTF-8 and strictly parse a request document without resolving it."""
    path = Path(path)
    try:
        data = path.read_bytes()
    except OSError as error:
        raise UnreadableConfig(f'config file is missing or unreadable: {path}') from error
    try:
        text = data.decode('utf-8')
    except UnicodeDecodeError as error:
        raise ConfigError('config must contain valid UTF-8') from error
    return _parse(text)


def _integer(value, minimum, maximum):
    if isinstance(value, bool):
        return False
    if isinstance(value, int):
        return minimum <= value <= maximum
    if isinstance(value, Decimal):
        integral = value == value.to_integral_value()
        number = int(value) if integral else None
        return integral and minimum <= number <= maximum
    return False


def _equals(value, expected):
    """Exact equality on the parsed number, so a float64-rounded literal cannot pose as the constant."""
    return (not isinstance(value, bool) and isinstance(value, (int, float))
            and math.isfinite(value) and value == expected)


def _unknown(document, allowed, path):
    for key in document:
        if key not in allowed:
            raise ConfigError('unknown field: ' + (f'{path}.{key}' if path else key))
    return document


def _search(algorithms):
    return any(algorithm in SEARCH_ALGORITHMS for algorithm in algorithms)


def _algorithms(value):
    message = 'algorithms must be a non-empty array without duplicates, drawn from ' + ','.join(ALGORITHMS)
    if value is None:
        return list(ALGORITHMS)
    if not isinstance(value, list) or not value:
        raise ConfigError(message)
    selected = set()
    for entry in value:
        if not isinstance(entry, str) or entry not in ALGORITHMS or entry in selected:
            raise ConfigError(message)
        selected.add(entry)
    return [algorithm for algorithm in ALGORITHMS if algorithm in selected]


def _weight(value):
    """Exact decimal value of a declared weight, or None when it is not a finite number from 0 to 1."""
    if isinstance(value, bool) or not isinstance(value, (int, float, Decimal)):
        return None
    if isinstance(value, float) and not math.isfinite(value):
        return None
    try:
        decimal = Decimal(str(value))
    except ArithmeticError:
        return None
    return decimal if Decimal(0) <= decimal <= Decimal(1) else None


def _objective(value):
    """Resolved objective choice with its weights; SLA is recognized but rejected by the supported model."""
    if value is None:
        return {'kind': 'power', 'power': 1.0, 'utilization': 0.0}
    if not isinstance(value, dict):
        raise ConfigError('objective must be an object with a kind')
    declared = _unknown(value, OBJECTIVE_KEYS, 'objective')
    kind = declared.get('kind')
    if not isinstance(kind, str) or kind not in ('power', 'utilization', 'sla', 'weighted'):
        raise ConfigError('objective.kind must be one of power,utilization,sla,weighted')
    if kind == 'sla':
        raise ConfigError(SLA_UNSUPPORTED)
    raw_weights = declared.get('weights')
    if kind != 'weighted':
        if raw_weights is not None:
            raise ConfigError('objective.weights is only valid when objective.kind is weighted')
        return {'kind': kind, 'power': 1.0 if kind == 'power' else 0.0,
                'utilization': 1.0 if kind == 'utilization' else 0.0}
    if not isinstance(raw_weights, dict):
        raise ConfigError('objective.weights is required when objective.kind is weighted')
    for key in raw_weights:
        if key == 'sla':
            raise ConfigError('objective.weights.sla is unsupported: constant full utilization cannot predict SLA differences')
        if key not in WEIGHT_KEYS:
            raise ConfigError('unknown field: objective.weights.' + key)
    power, utilization = _weight(raw_weights.get('power')), _weight(raw_weights.get('utilization'))
    if power is None or utilization is None or power + utilization != Decimal(1):
        raise ConfigError('objective.weights must contain power and utilization numbers from 0.0 to 1.0 '
                          'whose sum is exactly 1.0')
    return {'kind': 'weighted', 'power': float(power), 'utilization': float(utilization)}


def _optimizer(value, algorithms):
    if not _search(algorithms):
        if value is not None:
            raise ConfigError('optimizer is unused: algorithms exclude HO and GA')
        return None
    if not isinstance(value, dict):
        raise ConfigError('optimizer is required when HO or GA is configured')
    declared = _unknown(value, OPTIMIZER_KEYS, 'optimizer')
    population = declared.get('population')
    if not _integer(population, 2, INT_MAX) or int(population) % 2:
        raise ConfigError('optimizer.population must be an even integer of at least 2')
    iterations = declared.get('iterations')
    if not _integer(iterations, 1, INT_MAX):
        raise ConfigError('optimizer.iterations must be an integer of at least 1')
    population, iterations = int(population), int(iterations)
    if population + 3 * population * iterations > INT_MAX:
        raise ConfigError(f'evaluation budget must not exceed {INT_MAX}')
    return {'population': population, 'iterations': iterations}


def _values(value, minimum, maximum, path, message, count_message):
    """Ascending resolved list: an explicit list keeps its weights, a min/max range expands to [min..max]."""
    resolved = []
    if isinstance(value, list):
        if not value:
            raise ConfigError(message)
        previous = minimum - 1
        for entry in value:
            if not _integer(entry, minimum, maximum) or int(entry) < previous:
                raise ConfigError(message)
            previous = int(entry)
            resolved.append(previous)
    elif isinstance(value, dict):
        declared = _unknown(value, ('min', 'max'), path)
        low, high = declared.get('min'), declared.get('max')
        if (not _integer(low, minimum, maximum) or not _integer(high, minimum, maximum)
                or int(low) > int(high)):
            raise ConfigError(message)
        if int(high) - int(low) + 1 > LIMITS['MAX_DECLARED_VALUES']:
            raise ConfigError(count_message)
        resolved = list(range(int(low), int(high) + 1))
    else:
        raise ConfigError(message)
    if len(resolved) > LIMITS['MAX_DECLARED_VALUES']:
        raise ConfigError(count_message)
    return resolved


def _host_classes(value):
    if not isinstance(value, list) or len(value) > LIMITS['MAX_HOST_CLASSES']:
        raise ConfigError(f"hosts must be an array of at most {LIMITS['MAX_HOST_CLASSES']} classes")
    classes = []
    for index, raw in enumerate(value):
        path = f'hosts[{index}]'
        if not isinstance(raw, dict):
            raise ConfigError(f'{path}.count must be an integer from 1 to {LIMITS["MAX_HOSTS"]}')
        declared = _unknown(raw, HOST_KEYS, path)
        values = {}
        for key in HOST_KEYS:
            minimum, maximum = HOST_BOUNDS[key]
            if key == 'max_w':
                minimum = values['idle_w']
            if not _integer(declared.get(key), minimum, maximum):
                raise ConfigError(f'{path}.{key} must be an integer from {minimum} to {maximum}')
            values[key] = int(declared[key])
        if values['pes'] * values['mips_per_pe'] > LONG_MAX:
            raise ConfigError(f'{path}.pes multiplied by mips_per_pe must not exceed {LONG_MAX}')
        classes.append({key: values[key] for key in HOST_KEYS})
    return classes


def _demands(value, host_classes):
    if not isinstance(value, dict):
        raise ConfigError(f'vms.count must be an integer from 1 to {LIMITS["MAX_VMS"]}')
    declared = _unknown(value, ('count',) + DEMANDS, 'vms')
    if not _integer(declared.get('count'), 1, LIMITS['MAX_VMS']):
        raise ConfigError(f'vms.count must be an integer from 1 to {LIMITS["MAX_VMS"]}')
    lists = {}
    for field in DEMANDS:
        message = f'vms.{field} must be a non-empty non-decreasing integer list or a min/max integer range'
        count_message = f'vms.{field} must not declare more than {LIMITS["MAX_DECLARED_VALUES"]} values'
        resolved = _values(declared.get(field), 1, INT_MAX, f'vms.{field}', message, count_message)
        if field == 'mips_per_pe':
            for declared_value in resolved:
                if declared_value % EXACT_EVENT_DIVISOR:
                    raise ConfigError(f'vms.mips_per_pe value {declared_value} must be a multiple of '
                                      f'{EXACT_EVENT_DIVISOR}: the 0.125 s event interval must stay exact')
        largest = max((host_class[field] for host_class in host_classes), default=0)
        if resolved[-1] > largest:
            raise ConfigError(f'vms.{field} value {resolved[-1]} exceeds every host class {field} ({largest})')
        lists[field] = resolved
    return {'count': int(declared['count']), **{field: lists[field] for field in DEMANDS}}


def _workload(value):
    message = (f'workload.length_seconds must be a non-empty non-decreasing integer list or a min/max integer range '
               f'from {LIMITS["MIN_SECONDS"]} to {LIMITS["MAX_SECONDS"]}')
    if not isinstance(value, dict):
        raise ConfigError(message)
    declared = _unknown(value, WORKLOAD_KEYS, 'workload')
    seconds = _values(declared.get('length_seconds'), LIMITS['MIN_SECONDS'], LIMITS['MAX_SECONDS'],
                      'workload.length_seconds', message, message)
    if not _equals(declared.get('utilization'), 1):
        raise ConfigError('workload.utilization must be 1.0: constant full utilization is the only simulated model')
    if not _equals(declared.get('release_seconds'), 0):
        raise ConfigError('workload.release_seconds must be 0.0: a single simultaneous release is the only simulated model')
    return {'length_seconds': seconds, 'utilization': 1.0, 'release_seconds': 0.0}


def _resolve(document):
    if not isinstance(document, dict):
        raise ConfigError('invalid JSON: the root value must be an object')
    _unknown(document, TOP_KEYS, '')
    if not _integer(document.get('config_version'), CONFIG_VERSION, CONFIG_VERSION):
        raise ConfigError(f'config_version must be the integer {CONFIG_VERSION}')
    name = document.get('name')
    if not isinstance(name, str) or NAME_PATTERN.fullmatch(name) is None:
        raise ConfigError('name must be a string matching ' + CONFIG_NAME)
    if not _integer(document.get('master_seed'), LONG_MIN, LONG_MAX):
        raise ConfigError('master_seed must be a signed 64-bit integer')
    master_seed = int(document['master_seed'])
    if not _integer(document.get('replications'), 1, LIMITS['MAX_REPLICATIONS']):
        raise ConfigError(f"replications must be an integer from 1 to {LIMITS['MAX_REPLICATIONS']}")
    replications = int(document['replications'])
    algorithms = _algorithms(document.get('algorithms'))
    objective = _objective(document.get('objective'))
    optimizer = _optimizer(document.get('optimizer'), algorithms)
    host_classes = _host_classes(document.get('hosts'))
    host_count = sum(host_class['count'] for host_class in host_classes)
    if not 1 <= host_count <= LIMITS['MAX_HOSTS']:
        raise ConfigError(f"total host count must be from 1 to {LIMITS['MAX_HOSTS']}")
    power_max_w_sum = sum(host_class['count'] * host_class['max_w'] for host_class in host_classes)
    if power_max_w_sum < 1:
        raise ConfigError('hosts must declare a positive total max_w power ceiling')
    vms = _demands(document.get('vms'), host_classes)
    workload = _workload(document.get('workload'))
    if vms['count'] * host_count > INT_MAX:
        raise ConfigError(f'vms.count multiplied by total host count must not exceed {INT_MAX}')
    if replications * len(algorithms) > INT_MAX:
        raise ConfigError(f'replications multiplied by algorithms must not exceed {INT_MAX}')
    worst_pes = max(vms['pes'])
    worst_mips = max(vms['mips_per_pe'])
    if any(worst_mips * seconds > LONG_MAX for seconds in workload['length_seconds']) or \
            worst_pes * worst_mips * max(workload['length_seconds']) > LONG_MAX:
        raise ConfigError("a VM's workload length in MI does not fit a signed 64-bit integer")
    search = sum(1 for algorithm in algorithms if algorithm in SEARCH_ALGORITHMS)
    budget = 0 if optimizer is None else optimizer['population'] + 3 * optimizer['population'] * optimizer['iterations']
    if replications * (search * budget + len(algorithms) - search) > INT_MAX:
        raise ConfigError(f'total expected evaluations must not exceed {INT_MAX}')
    resolved = {'config_version': CONFIG_VERSION, 'name': name, 'master_seed': master_seed,
                'replications': replications, 'algorithms': algorithms,
                'objective': {'kind': objective['kind'], 'version': OBJECTIVE_VERSION,
                              'weights': {'power': objective['power'], 'utilization': objective['utilization']},
                              'normalization': {'power': POWER_NORMALIZATION,
                                                'power_max_w_sum': power_max_w_sum,
                                                'utilization': UTILIZATION_NORMALIZATION}}}
    if optimizer is not None:
        resolved['optimizer'] = optimizer
    resolved['hosts'] = host_classes
    resolved['vms'] = vms
    resolved['workload'] = workload
    return resolved


def validate_config(config):
    """Raise the engine's own rejection sentence unless the configuration resolves exactly."""
    _resolve(config)
    return config


def resolve_config(config):
    """The resolved document: the engine's key order, value types and canonical values."""
    return _resolve(config)


def generated_spec(resolved, replication):
    """The exact record written to scenarios/replication-<r>.json for one replication."""
    name = resolved['name']
    master = resolved['master_seed']
    scenario_seed = seed(master, PHASE, name, replication, 'scenario')
    workload_seed = seed(master, PHASE, name, replication, 'workload')
    hosts = []
    for host_class in resolved['hosts']:
        for _ in range(host_class['count']):
            hosts.append({'id': len(hosts), 'pes': host_class['pes'], 'mipsPerPe': host_class['mips_per_pe'],
                          'ramMiB': host_class['ram_mib'], 'bwMbps': host_class['bw_mbps'],
                          'storageMiB': host_class['storage_mib'], 'idleW': host_class['idle_w'],
                          'maxW': host_class['max_w']})
    demands = resolved['vms']
    seconds = resolved['workload']['length_seconds']
    scenario, workload = JavaRandom(scenario_seed), JavaRandom(workload_seed)
    vms, cloudlets, references = [], [], []
    for index in range(demands['count']):
        pes = demands['pes'][scenario.integer(len(demands['pes']))]
        mips = demands['mips_per_pe'][scenario.integer(len(demands['mips_per_pe']))]
        ram = demands['ram_mib'][scenario.integer(len(demands['ram_mib']))]
        bw = demands['bw_mbps'][scenario.integer(len(demands['bw_mbps']))]
        storage = demands['storage_mib'][scenario.integer(len(demands['storage_mib']))]
        duration = seconds[workload.integer(len(seconds))]
        vms.append({'id': index, 'pes': pes, 'mipsPerPe': mips, 'ramMiB': ram, 'bwMbps': bw, 'storageMiB': storage})
        cloudlets.append({'id': index, 'vmId': index, 'pes': pes, 'lengthMi': mips * duration})
        references.append(float(duration))
    inputs = {'seeds': {'master': master, 'phase': PHASE, 'scenario': name, 'replication': replication,
                        'scenarioSeed': scenario_seed, 'workloadSeed': workload_seed},
              'hosts': hosts, 'vms': vms, 'cloudlets': cloudlets}
    lines = ['kind,id,pes,mips_per_pe,ram_mib,bw_mbps,storage_mib,idle_w,max_w,vm_id,length_mi']
    for host in hosts:
        lines.append(','.join(map(str, ['host', host['id'], host['pes'], host['mipsPerPe'], host['ramMiB'],
                                        host['bwMbps'], host['storageMiB'], host['idleW'], host['maxW'], '', ''])))
    for vm in vms:
        lines.append(','.join(map(str, ['vm', vm['id'], vm['pes'], vm['mipsPerPe'], vm['ramMiB'],
                                        vm['bwMbps'], vm['storageMiB'], '', '', '', ''])))
    for cloudlet in cloudlets:
        lines.append(','.join(map(str, ['cloudlet', cloudlet['id'], cloudlet['pes'], '', '', '', '', '', '',
                                        cloudlet['vmId'], cloudlet['lengthMi']])))
    keys = {'cloudlet_scheduler': 'CloudletSchedulerSpaceShared', 'host_scheduler': 'VmSchedulerSpaceShared',
            'min_event_interval_s': '0.125', 'power_policy': 'used_on_until_horizon', 'power_curve': 'linear',
            'release_s': '0.0', 'utilization_model': 'constant_full', 'censor_s': str(10 * max(references))}
    keys.update({f'reference.{index}': str(value) for index, value in enumerate(references)})
    canonical = '\n'.join(lines + ['key,value'] + [f'{key},{keys[key]}' for key in sorted(keys)]) + '\n'
    return {'inputs': inputs, 'reference_seconds': references, 'censor_s': 10 * max(references),
            'canonical_text': canonical, 'scenario_sha256': hashlib.sha256(canonical.encode('utf-8')).hexdigest()}


def _saturating(total, value):
    return LONG_MAX if total > LONG_MAX - value else total + value


def _witness_failure(inputs):
    """First rejected dimension of the constructive witness, or None when it constructs."""
    hosts, vms = inputs['hosts'], inputs['vms']
    for host in hosts:
        totals = [0, 0, 0, 0, 0]
        for vm in vms:
            if vm['id'] % len(hosts) != host['id']:
                continue
            if vm['mipsPerPe'] > host['mipsPerPe']:
                return {'host': host['id'], 'dimension': 'per_pe_mips', 'demand': vm['mipsPerPe'],
                        'capacity': host['mipsPerPe']}
            demand = [vm['pes'], vm['pes'] * vm['mipsPerPe'], vm['ramMiB'], vm['bwMbps'], vm['storageMiB']]
            totals = [total + value for total, value in zip(totals, demand)]
        capacity = [host['pes'], host['pes'] * host['mipsPerPe'], host['ramMiB'], host['bwMbps'], host['storageMiB']]
        for index, dimension in enumerate(DIMENSIONS):
            if totals[index] > capacity[index]:
                return {'host': host['id'], 'dimension': dimension, 'demand': totals[index],
                        'capacity': capacity[index]}
    return None


def feasibility(inputs, replication):
    """Necessary-condition proof, constructive witness, or an explicit gap between them."""
    demand = [0, 0, 0, 0, 0]
    for vm in inputs['vms']:
        demand = [_saturating(total, value) for total, value in
                  zip(demand, [vm['pes'], vm['pes'] * vm['mipsPerPe'], vm['ramMiB'], vm['bwMbps'], vm['storageMiB']])]
    capacity = [0, 0, 0, 0, 0]
    for host in inputs['hosts']:
        capacity = [_saturating(total, value) for total, value in
                    zip(capacity, [host['pes'], host['pes'] * host['mipsPerPe'], host['ramMiB'], host['bwMbps'],
                                   host['storageMiB']])]
    for index, dimension in enumerate(DIMENSIONS):
        if demand[index] > capacity[index]:
            return {'replication': replication, 'kind': 'INFEASIBLE_PROVED',
                    'reason': f'replication {replication} total {dimension} demand {demand[index]} '
                              f'exceeds total host {dimension} capacity {capacity[index]}'}
    largest = max(host['mipsPerPe'] for host in inputs['hosts'])
    for vm in inputs['vms']:
        if vm['mipsPerPe'] > largest:
            return {'replication': replication, 'kind': 'INFEASIBLE_PROVED',
                    'reason': f'replication {replication} total mips_per_pe demand {vm["mipsPerPe"]} '
                              f'exceeds total host mips_per_pe capacity {largest}'}
    failure = _witness_failure(inputs)
    if failure is not None:
        return {'replication': replication, 'kind': 'UNPROVED',
                'reason': f'replication {replication} witness placement rejected: host {failure["host"]} '
                          f'{failure["dimension"]} demand {failure["demand"]} exceeds capacity {failure["capacity"]}'}
    return {'replication': replication, 'kind': 'CONSTRUCTIBLE', 'reason': None}


def feasibilities(resolved):
    """One classification per replication, matching the artifact's feasibility records."""
    return [feasibility(generated_spec(resolved, replication)['inputs'], replication)
            for replication in range(resolved['replications'])]


def planned_work(resolved):
    """Case and evaluation counts: search cases spend the optimizer budget, baselines one evaluation."""
    algorithms = resolved['algorithms']
    optimizer = resolved.get('optimizer')
    budget = 0 if optimizer is None else optimizer['population'] + 3 * optimizer['population'] * optimizer['iterations']
    search = sum(1 for algorithm in algorithms if algorithm in SEARCH_ALGORITHMS)
    baseline = len(algorithms) - search
    return {'expected_cases': resolved['replications'] * len(algorithms), 'evaluation_budget': budget,
            'expected_evaluations': resolved['replications'] * (search * budget + baseline)}


def effective(resolved, config_sha256):
    """The flat, sorted-by-renderer effective map recorded in effective.properties."""
    plan = planned_work(resolved)
    optimizer = resolved.get('optimizer')
    objective = resolved['objective']
    seconds = resolved['workload']['length_seconds']
    result = {'profile': PHASE, 'experiment.kind': 'static_scenario',
              'scenario.schema.version': str(SCENARIO_SCHEMA_VERSION), 'experiment.phase': PHASE,
              'config.name': resolved['name'], 'config.sha256': config_sha256,
              'master.seed': str(resolved['master_seed']), 'replication.count': str(resolved['replications']),
              'algorithms': ','.join(resolved['algorithms']), 'objective.kind': objective['kind'],
              'objective.version': str(OBJECTIVE_VERSION),
              'objective.weights.power': str(objective['weights']['power']),
              'objective.weights.utilization': str(objective['weights']['utilization']),
              'objective.normalization.power': POWER_NORMALIZATION,
              'objective.normalization.power_max_w_sum': str(objective['normalization']['power_max_w_sum']),
              'objective.normalization.utilization': UTILIZATION_NORMALIZATION,
              'objective.sla.model': SLA_MODEL,
              'vm.count': str(resolved['vms']['count']),
              'host.count': str(sum(host_class['count'] for host_class in resolved['hosts'])),
              'host.classes': str(len(resolved['hosts'])), 'expected.cases': str(plan['expected_cases']),
              'expected.evaluations': str(plan['expected_evaluations']), 'utilization.model': 'constant_full',
              'utilization.value': '1.0', 'workload.length.seconds': f'{seconds[0]},{seconds[-1]}'}
    if optimizer is not None:
        result['evaluation.budget'] = str(plan['evaluation_budget'])
        result['optimizer.population'] = str(optimizer['population'])
        result['optimizer.iterations'] = str(optimizer['iterations'])
    return result


def render_effective(resolved, config_sha256):
    """SORTED key=value lines, byte-identical to the engine's effective.properties."""
    values = effective(resolved, config_sha256)
    return ''.join(f'{key}={values[key]}\n' for key in sorted(values))


def parse_duration(value):
    if value == 'none':
        return None
    match = re.fullmatch(r'([0-9]+)([smh]?)', value)
    if not match or len(match[1]) > 19:
        raise argparse.ArgumentTypeError('time limit must be positive integer seconds or s/m/h, or none')
    seconds = int(match[1]) * {'': 1, 's': 1, 'm': 60, 'h': 3600}[match[2]]
    if not 0 < seconds <= INT_MAX:
        raise argparse.ArgumentTypeError('time limit must be positive and at most 2147483647 seconds')
    return float(seconds)


def output_path(value):
    if not value.strip():
        raise argparse.ArgumentTypeError('output directory is required')
    return Path(value)


STRESS_FLAGS = ('--vms', '--hosts', '--population', '--iterations', '--seed', '--replications')


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False,
        epilog='Requires full JDK 21, Linux/cgroup or macOS memory evidence and Python 3.11+. Deadline covers production and validation; excludes build/tests. Exactly one of --config or --preset is required.')
    parser.add_argument('--config', type=Path, help='existing JSON configuration file (strictly validated before any simulation)')
    parser.add_argument('--preset', choices=tuple(PRESETS), help='bundled configuration: ' + ', '.join(PRESETS))
    parser.add_argument('--output-dir', type=output_path, default=ROOT/'results/scenario', help='parent for unique retained output directories (default: results/scenario)')
    parser.add_argument('--workers', type=shared.worker_value, default='auto', help='independent case workers: auto or 1..32, bounded by CPU and shared heap')
    parser.add_argument('--heap-mib', type=shared.heap_value, default=DEFAULT_HEAP_MIB, help='explicit maximum Java heap in MiB (default: 1024, minimum: 512); never grows automatically')
    parser.add_argument('--plain', action='store_true', help='plain progress lines instead of terminal dashboard')
    parser.add_argument('--time-limit', type=parse_duration, default='none', help='shared deadline: positive integer seconds or s/m/h suffix, or none (default: none)')
    parser.add_argument('--skip-build', action='store_true', help='skip Maven clean verify and Python discovery; copy the existing packaged JAR')
    parser.add_argument('--force-build', action='store_true', help='force full build and verification instead of verified reuse')
    parser.add_argument('--dry-run', action='store_true', help='show the request, resolution, planned work and feasibility; create no files or child processes')
    arguments = list(argv if argv is not None else sys.argv[1:])
    flags = [argument.split('=', 1)[0] for argument in arguments if argument.startswith('--')]
    if len(flags) != len(set(flags)):
        parser.error('duplicate options are not supported')
    for flag in STRESS_FLAGS:
        if flag in flags:
            parser.error(f'{flag} configures the stress profile; declare hosts, VMs, algorithms and optimizer effort in the configuration file')
    args = parser.parse_args(arguments)
    if args.skip_build and args.force_build:
        parser.error('--force-build and --skip-build are incompatible')
    if (args.config is None) == (args.preset is None):
        parser.error('exactly one of --config or --preset is required')
    if args.preset is not None:
        args.source = 'preset:' + args.preset
        args.request = request_json(PRESETS[args.preset])
        args.config_path = None
    else:
        args.source = str(args.config)
        args.config_path = args.config
        try:
            args.request = load_config(args.config)
        except UnreadableConfig as error:
            print(f'IO_ERROR: {error}', file=sys.stderr)
            raise SystemExit(1) from error
        except ConfigError as error:
            parser.error(str(error))
    try:
        args.resolved = resolve_config(args.request)
    except ConfigError as error:
        parser.error(str(error))
    args.expected_cases = planned_work(args.resolved)['expected_cases']
    return args


def java_command(java, config_path, jar, output, workers=1, *, heap_mib=DEFAULT_HEAP_MIB):
    """The engine command; the shared helper only accepts frozen profiles, so --profile is replaced."""
    command = shared.java_command(java, heap_mib, jar, output, workers=workers)
    command[command.index('--profile') + 1] = PHASE
    command[command.index('--output-dir') + 1] = str(output)
    return command + ['--config', str(config_path)]


def memory_preview():
    evidence = {'sampled_at': shared.stamp(), 'physical_memory_bytes': None, 'usable_memory_bytes': None,
                'warning': None, 'policy_note': None}
    for name, reader in [('physical_memory_bytes', shared.physical_memory), ('usable_memory_bytes', shared.usable_memory)]:
        try:
            evidence[name] = reader()
        except (OSError, ValueError) as error:
            evidence[name + '_error'] = shared.sanitize(error)
    return evidence


def show_memory(evidence, output=None):
    output = output if output is not None else sys.stdout
    def gib(value):
        return f'{value/1024**3:.2f} GiB' if value is not None else 'unknown'
    source = 'macOS free/reclaimable pages' if sys.platform == 'darwin' else 'Linux/cgroup-aware'
    print(f'Memory: physical: {gib(evidence["physical_memory_bytes"])}; usable: {gib(evidence["usable_memory_bytes"])} ({source})', file=output)
    if evidence['warning']:
        print(evidence['warning'], file=output)


def show_planned_work(plan, output=None):
    output = output if output is not None else sys.stdout
    print(f'Production: {plan["expected_cases"]} cases / {plan["expected_evaluations"]} evaluations '
          f'({plan["evaluation_budget"]} optimizer evaluations per search case)', file=output)


def show_feasibility(entries, output=None):
    output = output if output is not None else sys.stdout
    for entry in entries:
        reason = f' ({entry["reason"]})' if entry['reason'] else ''
        print(f'Replication {entry["replication"]}: {entry["kind"]}{reason}', file=output)


def warnings(args):
    if args.time_limit is None:
        return ['No --time-limit: production and its validation may run unbounded.']
    return []


def preview(args):
    """The --dry-run record: request, resolution, planned work, feasibility and the command template."""
    template = shlex.join(java_command(Path('JAVA_HOME/bin/java'), Path('<CONFIG>'), Path('<RETAINED>/scenario.jar'),
                                      Path('<RETAINED>/production/artifacts'), 1, heap_mib=args.heap_mib))
    return {'action': 'dry-run', 'profile': PHASE, 'config': args.request, 'resolved_config': args.resolved,
            'planned_work': planned_work(args.resolved), 'feasibility': feasibilities(args.resolved),
            'execution': shared.execution_settings(args.workers, args.heap_mib), 'memory_evidence': memory_preview(),
            'warnings': warnings(args), 'commands': [template],
            'time_limit_scope': 'production + validation; excludes build/tests'}


def progress(parent, resolved, deadline):
    total = planned_work(resolved)['expected_cases']
    remaining = None if deadline is None else max(0, deadline - time.monotonic())
    remaining_text = 'none' if remaining is None else f'{remaining:.0f}s'
    timing = {'deadline_remaining': remaining, 'deadline_unlimited': deadline is None}
    extra = [f'V{resolved["vms"]["count"]}/H{sum(host_class["count"] for host_class in resolved["hosts"])}'
             f'/R{resolved["replications"]}; deadline remaining {remaining_text}']
    try:
        paths = list(parent.glob('scenario-*/progress.json'))
        if len(paths) != 1:
            raise ValueError('progress unavailable')
        data = json.loads(paths[0].read_text())
        done = data['successful_cases']
        if type(done) is not int or not 0 <= done <= total:
            raise ValueError('invalid progress')
        active = data.get('current_case')
        if active is not None and not isinstance(active, dict):
            raise ValueError('invalid active case')
        evaluations, expected = data['completed_evaluations'], data['expected_evaluations']
        if any(type(value) is not int or value < 0 for value in (evaluations, expected)) or evaluations > expected:
            raise ValueError('invalid evaluation counters')
        if active and active.get('computing_phase'):
            detail = (f'{active.get("algorithm", "?")} replication {active.get("replication", "?")}: '
                      f'{active["computing_phase"]}')
            if active.get('computing_evaluation') is not None:
                detail += f' computing evaluation {active["computing_evaluation"]}, iteration {active.get("computing_iteration", "?")} ({active.get("computing_stage", "?")})'
            detail += '; published '
        elif active:
            detail = (f'{active.get("algorithm", "?")} replication {active.get("replication", "?")} '
                      f'evaluation {active.get("evaluation", "?")}; ')
        else:
            detail = ''
        detail += f'evaluations {evaluations}/{expected}'
        return {'done': done, 'total': total, 'detail': detail, 'extra_lines': extra,
                'evaluations': evaluations, 'evaluations_total': expected, **timing,
                **{key: (active or {}).get(key) for key in ('algorithm', 'scenario', 'replication', 'phase')}}
    except (OSError, ValueError, KeyError, TypeError):
        return {'done': None, 'total': total, 'detail': 'Progress unavailable (logs retained)',
                'extra_lines': extra, **timing}


def completed_run(parent, digest, resolved):
    """Assert the single retained artifact records exactly the Python resolution; return its path."""
    paths = list(parent.iterdir())
    if len(paths) != 1 or not paths[0].is_dir() or paths[0].is_symlink():
        raise ValueError('Expected exactly one new scenario artifact')
    run = paths[0]
    data = json.loads((run/'run.json').read_text())
    if (data.get('experiment_kind') != 'static_scenario' or data.get('scenario_schema_version') != SCENARIO_SCHEMA_VERSION
            or data.get('protocol') != PROTOCOL or data.get('profile') != PHASE or data.get('state') != 'COMPLETE'
            or data.get('error') is not None or data.get('artifact_sha256') != digest
            or data.get('config_name') != resolved['name'] or data.get('master_seed') != resolved['master_seed']):
        raise ValueError('Scenario artifact is incomplete, unsuccessful or mismatched')
    plan = planned_work(resolved)
    for name, value in [('expected_cases', plan['expected_cases']), ('attempted_cases', plan['expected_cases']),
                        ('successful_cases', plan['expected_cases']), ('failed_cases', 0), ('unattempted_cases', 0),
                        ('expected_evaluations', plan['expected_evaluations']),
                        ('completed_evaluations', plan['expected_evaluations']),
                        ('expected_simulations', plan['expected_cases']),
                        ('completed_simulations', plan['expected_cases']), ('search_simulations', 0)]:
        if type(data.get(name)) is not int or data[name] != value:
            raise ValueError('Incorrect scenario completion counters')
    if data.get('objective') != resolved.get('objective'):
        raise ValueError('Recorded objective differs from the resolved configuration')
    recorded = data.get('config_sha256')
    if not isinstance(recorded, str) or len(recorded) != 64:
        raise ValueError('Scenario artifact does not record a configuration digest')
    if data.get('effective_config') != effective(resolved, recorded):
        raise ValueError('Recorded effective configuration differs from the resolved configuration')
    if data.get('feasibility') != feasibilities(resolved):
        raise ValueError('Recorded feasibility differs from the derived classification')
    if data.get('resolved_config') != resolve_config(load_config(run/'config'/'requested.json')):
        raise ValueError('Recorded resolved configuration differs from the requested configuration')
    if hashlib.sha256((run/'config'/'requested.json').read_bytes()).hexdigest() != recorded:
        raise ValueError('Requested configuration does not match its recorded digest')
    if json.loads((run/'config'/'resolved.json').read_text()) != data.get('resolved_config'):
        raise ValueError('Recorded resolved.json differs from the recorded resolution')
    return run


def _request_bytes(document):
    return (json.dumps(document, indent=2) + '\n').encode('utf-8')


def request_digest(document):
    """SHA-256 of the canonical request bytes the engine will receive."""
    return hashlib.sha256(_request_bytes(document)).hexdigest()


def _child_failure(parent, code, block):
    error_code, message = 'RUN_ERROR', 'unknown failure'
    try:
        paths = [path for path in parent.iterdir() if path.is_dir()]
        if len(paths) == 1:
            error = json.loads((paths[0]/'run.json').read_text()).get('error') or {}
            error_code = error.get('code') or 'RUN_ERROR'
            message = error.get('message') or 'unknown failure'
    except (OSError, ValueError, KeyError, TypeError):
        pass
    return ValueError(f'production child failed with exit {code}: {error_code}: {message}; retained: {block}')


def main(argv=None, *, dashboard=None, on_complete=None, invocation=None) -> int:
    arguments = list(argv if argv is not None else sys.argv[1:])
    args = parse_args(arguments)
    resolved = args.resolved
    request_written = args.config_path is None
    config_sha256 = (hashlib.sha256(_request_bytes(args.request)).hexdigest() if request_written
                     else shared.sha256(args.config_path))
    metadata = {'started_at': shared.stamp(), 'status': 'preflight', 'exit_code': None, 'profile': PHASE,
                'arguments': arguments, 'config': args.source, 'config_sha256': config_sha256,
                'resolved_config': resolved, 'effective_config': effective(resolved, config_sha256),
                'planned_work': planned_work(resolved), 'feasibility': feasibilities(resolved),
                'validation': 'NOT_RUN', 'tests': 'NOT_RUN', 'memory_evidence': memory_preview(),
                'time_limit_scope': 'production + validation; excludes build/tests', 'skip_build': args.skip_build}
    metadata['execution'] = shared.execution_settings(args.workers, args.heap_mib)
    if invocation is not None:
        metadata['invocation'] = deepcopy(invocation)
    supplied_dashboard = dashboard is not None
    dashboard = dashboard if supplied_dashboard else shared.Dashboard(plain=args.plain, heap_mib=args.heap_mib,
                                                                     title='STATIC SCENARIO', total=args.expected_cases)
    control = shared.ProcessControl(dashboard, ROOT)
    if args.dry_run:
        code = 0
        try:
            rendered = io.StringIO()
            show_memory(metadata['memory_evidence'], rendered)
            show_planned_work(metadata['planned_work'], rendered)
            show_feasibility(metadata['feasibility'], rendered)
            print(json.dumps(preview(args), indent=2), file=rendered)
            print('Java command template: ' + shlex.join(java_command(Path('JAVA_HOME/bin/java'), Path('CONFIG/scenario-request.json'),
                                                                     Path('RETAINED/scenario.jar'), Path('RETAINED/production/artifacts'),
                                                                     1, heap_mib=args.heap_mib)), file=rendered)
            if supplied_dashboard:
                control.render('dry-run', {'detail': rendered.getvalue()}, force=True)
            else:
                print(rendered.getvalue(), end='')
            metadata['status'] = 'dry-run'
        except shared.PresentationError as error:
            code = 1
            metadata.update(status='failed', error=shared.sanitize(error))
        return shared.finish(control, metadata, code, on_complete=on_complete)
    control.install()
    outer = console = None
    code = 1
    deadline = None
    try:
        args.output_dir = args.output_dir.resolve()
        args.output_dir.mkdir(parents=True, exist_ok=True)
        outer = Path(tempfile.mkdtemp(prefix='scenario-' + datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '-',
                                      dir=args.output_dir))
        control.root = outer
        console = (outer/'console.log').open('a')
        dashboard.console = console
        request = outer/'scenario-request.json' if request_written else args.config_path.resolve()
        if request_written:
            request.write_bytes(_request_bytes(args.request))
        metadata.update(output_directory=str(outer), request_file=str(request))
        shared.save_metadata(outer, metadata)
        if supplied_dashboard:
            rendered = io.StringIO()
            show_memory(metadata['memory_evidence'], rendered)
            control.render('preflight', {'detail': rendered.getvalue()}, force=True)
        else:
            show_memory(metadata['memory_evidence'])
        java, metadata['usable_memory_bytes'] = shared.check_environment(control, args.heap_mib)
        metadata.update(java=str(java))
        shared.save_metadata(outer, metadata)
        shared.capture_provenance(control, metadata, outer, root=ROOT)
        retained, artifact_source = shared.prepare_artifact(args, metadata, control, outer, PHASE, root=ROOT)
        shared.save_metadata(outer, metadata)
        digest = shared.sha256(retained)
        control.render('configuration', {'detail': f"R{resolved['replications']} x {len(resolved['algorithms'])} algorithms; "
                                                     f"V{resolved['vms']['count']}/H{sum(h['count'] for h in resolved['hosts'])}; "
                                                     f"seed {resolved['master_seed']}",
                                         'extra_lines': [f'Time limit: {args.time_limit if args.time_limit is not None else "none"} seconds; heap {args.heap_mib} MiB',
                                                         'Deadline: production + validation; excludes build/tests',
                                                         f'Production workers: {metadata["execution"]["workers"]}; shared heap {args.heap_mib} MiB.',
                                                         *[f'Replication {entry["replication"]}: {entry["kind"]}' for entry in metadata['feasibility']]]},
                       force=True)
        metadata.update(artifact_sha256=digest, artifact_source=str(artifact_source), deadline_started_at=shared.stamp())
        deadline = time.monotonic() + args.time_limit if args.time_limit is not None else None
        shared.require_memory(args.heap_mib)
        control.check(deadline)
        block = outer/'production'
        block.mkdir()
        parent = block/'artifacts'
        command = java_command(java, request, retained, parent, metadata['execution']['workers'], heap_mib=args.heap_mib)
        record = {'phase': PHASE, 'config': str(request), 'workers': metadata['execution']['workers'],
                  'java_command': command, 'validation': 'NOT_RUN', 'directory': str(block)}
        metadata['production'] = record
        metadata['java_command'] = command
        metadata['status'] = 'production'
        shared.save_metadata(outer, metadata)
        reader = lambda: progress(parent, resolved, deadline)
        control.render('production', reader(), force=True)
        code = control.run(command, block/'java.log', 'production', cwd=block, timeout=None, deadline=deadline,
                           progress_reader=reader)
        record.update(control.last_measurement)
        shared.save_metadata(outer, metadata)
        if code:
            raise _child_failure(parent, code, block)
        run = completed_run(parent, digest, resolved)
        record['run_directory'] = str(run)
        metadata['run_directory'] = str(run)
        if shared.sha256(retained) != digest:
            raise ValueError('Retained JAR changed after launch')
        metadata['status'] = 'production-validation'
        shared.save_metadata(outer, metadata)
        shared.require_memory(512)
        control.check(deadline)
        code = control.run([sys.executable, '-B', str(ROOT/'scripts/scenario_validator.py'), str(run)],
                           block/'validation.log', 'production-validation', cwd=ROOT, timeout=None, deadline=deadline,
                           progress_reader=reader)
        if code:
            raise ValueError(f'Independent production validation failed with exit {code}; see {block/"validation.log"}')
        control.check(deadline)
        completed_run(parent, digest, resolved)
        if shared.sha256(retained) != digest:
            raise ValueError('Retained JAR changed during validation')
        record['validation'] = 'PASS'
        record['validation_wall_seconds'] = control.last_measurement['wall_seconds']
        artifact = json.loads((run/'run.json').read_text())
        record.update(artifact_revision=artifact.get('git_revision'), artifact_dirty=artifact.get('git_dirty'),
                      max_heap_bytes=artifact.get('max_heap_bytes'))
        diagnostic = metadata['source_dirty'] or args.skip_build or record['artifact_dirty'] is not False \
            or record['artifact_revision'] != metadata['source_revision']
        metadata.update(status='complete', validation='PASS', diagnostic=diagnostic,
                        artifact_revision=record['artifact_revision'], artifact_dirty=record['artifact_dirty'])
        control.render('complete', {'done': args.expected_cases,
                                    'detail': f'VALIDATED: {args.expected_cases} configured static scenario cases; '
                                              f'retained: {outer}'}, force=True)
        code = 0
    except shared.Interrupted as error:
        code = 128 + error.signum
        metadata.update(status='interrupted', error=f'Interrupted by signal {error.signum}')
    except shared.DeadlineExceeded as error:
        code = 124
        metadata.update(status='timeout', error=str(error))
    except (OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        code = getattr(error, 'exit_code', 0) or (code if code not in (0, 1) else 1)
        metadata.update(status='failed', error=shared.sanitize(error))
    finally:
        if metadata['status'] in ('failed', 'timeout', 'interrupted'):
            try:
                control.render(metadata['status'], {'detail': metadata['error'] + f'; retained: {outer}'}, force=True)
            except shared.PresentationError:
                pass
        code = shared.finish(control, metadata, code, outer, console, on_complete)
        if outer and not supplied_dashboard:
            try:
                print(f'Retained results: {shared.sanitize(outer)}')
            except (OSError, ValueError):
                pass
    return code


if __name__ == '__main__':
    sys.exit(main())
