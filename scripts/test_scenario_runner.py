"""Scenario configuration, preset and retained-artifact contracts (stdlib only, no network)."""
import hashlib
import io
import json
from contextlib import redirect_stderr, redirect_stdout
from copy import deepcopy
from pathlib import Path
import re
import tempfile
import unittest

import scenario_runner as runner

INT_MAX = (1 << 31) - 1
LONG_MAX = (1 << 63) - 1
# Engine texts (Step 3/Step 5 of the plan), independent of the module under test.
MODEL_LIMITS = ('Only constant full utilization (1.0), one cloudlet per VM, VmSchedulerSpaceShared, '
                'CloudletSchedulerSpaceShared, no migrations, no overcommit, a single simultaneous release and a linear '
                "idle-to-max power curve over the host's own total MIPS are simulated. PE, MIPS, RAM, bandwidth and storage "
                'limits are static reservation admission checks; they are not simulated network or storage contention.')
EVIDENCE_LIMIT = ('Scalar traces check order, budgets and eligible-best consistency; they cannot '
                  'independently replay discarded candidates or guarantee detection of coherent non-best scalar tampering.')
ALLOCATION_TIMING = ('End-to-end search including synchronous buffered scalar trace writing; '
                     'not pure CPU or directly comparable to frozen allocation timing.')
TIGHT_SPEC_SHA256 = 'fafb1e1b12f215f16158824524486e1d229ae671935f17fad7d8513f7dd91dba'
TIGHT_SEEDS = {'master': 123456, 'phase': 'scenario', 'scenario': 'known-answer-tight', 'replication': 0,
               'scenarioSeed': -5587950289576536148, 'workloadSeed': -7972519024796350700}
TIGHT_CANONICAL = [
    'kind,id,pes,mips_per_pe,ram_mib,bw_mbps,storage_mib,idle_w,max_w,vm_id,length_mi',
    'host,0,2,3000,8192,2000,20000,100,200,,', 'host,1,2,3000,8192,2000,20000,100,200,,',
    'vm,0,2,3000,8192,2000,20000,,,,', 'vm,1,2,3000,8192,2000,20000,,,,',
    'cloudlet,0,2,,,,,,,0,180000', 'cloudlet,1,2,,,,,,,1,180000', 'key,value',
    'censor_s,600.0', 'cloudlet_scheduler,CloudletSchedulerSpaceShared', 'host_scheduler,VmSchedulerSpaceShared',
    'min_event_interval_s,0.125', 'power_curve,linear', 'power_policy,used_on_until_horizon',
    'reference.0,60.0', 'reference.1,60.0', 'release_s,0.0', 'utilization_model,constant_full']


def mutate(config, **changes):
    config.update(changes)
    return config


class ConfigurationRules(unittest.TestCase):
    """Every Step 3 rejection, its exact sentence and the engine's diagnosis order."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='scenario config ')
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.tight = runner.preset_config('known-answer-tight')

    def rejected(self, change, message):
        config = deepcopy(self.tight)
        change(config)
        with self.assertRaises(runner.ConfigError) as caught:
            runner.resolve_config(config)
        self.assertEqual(str(caught.exception), message)

    def test_structure_unknown_and_version_rules(self):
        for label, change, message in [
            ('unknown top level', lambda c: mutate(c, bogus=1), 'unknown field: bogus'),
            ('unknown host field', lambda c: c['hosts'][0].update(bogus=1), 'unknown field: hosts[0].bogus'),
            ('unknown vms field', lambda c: c['vms'].update(bogus=1), 'unknown field: vms.bogus'),
            ('unknown optimizer field', lambda c: c['optimizer'].update(bogus=1), 'unknown field: optimizer.bogus'),
            ('unknown workload field', lambda c: c['workload'].update(bogus=1), 'unknown field: workload.bogus'),
            ('unknown range field', lambda c: c['vms'].update(pes={'min': 1, 'max': 2, 'bogus': 3}),
             'unknown field: vms.pes.bogus'),
            ('version 2', lambda c: mutate(c, config_version=2), 'config_version must be the integer 1'),
            ('version string', lambda c: mutate(c, config_version='1'), 'config_version must be the integer 1'),
            ('version missing', lambda c: c.pop('config_version'), 'config_version must be the integer 1'),
            ('version fractional', lambda c: mutate(c, config_version=1.5), 'config_version must be the integer 1'),
            ('name uppercase', lambda c: mutate(c, name='Tight'), 'name must be a string matching [a-z][a-z0-9._-]{0,63}'),
            ('name not a string', lambda c: mutate(c, name=7), 'name must be a string matching [a-z][a-z0-9._-]{0,63}'),
            ('name too long', lambda c: mutate(c, name='a' + 'b' * 64),
             'name must be a string matching [a-z][a-z0-9._-]{0,63}'),
            ('name missing', lambda c: c.pop('name'), 'name must be a string matching [a-z][a-z0-9._-]{0,63}'),
            ('seed overflows', lambda c: mutate(c, master_seed=LONG_MAX + 1),
             'master_seed must be a signed 64-bit integer'),
            ('seed fractional', lambda c: mutate(c, master_seed=1.5), 'master_seed must be a signed 64-bit integer'),
            ('seed string', lambda c: mutate(c, master_seed='123456'), 'master_seed must be a signed 64-bit integer'),
            ('seed missing', lambda c: c.pop('master_seed'), 'master_seed must be a signed 64-bit integer'),
            ('replications zero', lambda c: mutate(c, replications=0), 'replications must be an integer from 1 to 10000'),
            ('replications high', lambda c: mutate(c, replications=10001),
             'replications must be an integer from 1 to 10000'),
            ('replications fractional', lambda c: mutate(c, replications=1.5),
             'replications must be an integer from 1 to 10000'),
        ]:
            with self.subTest(rule=label):
                self.rejected(change, message)

    def test_algorithm_and_optimizer_rules(self):
        for label, change, message in [
            ('empty algorithms', lambda c: mutate(c, algorithms=[]),
             'algorithms must be a non-empty array without duplicates, drawn from HO,GA,FirstFit,BestFit'),
            ('duplicate algorithms', lambda c: mutate(c, algorithms=['HO', 'HO']),
             'algorithms must be a non-empty array without duplicates, drawn from HO,GA,FirstFit,BestFit'),
            ('unknown algorithm', lambda c: mutate(c, algorithms=['X']),
             'algorithms must be a non-empty array without duplicates, drawn from HO,GA,FirstFit,BestFit'),
            ('algorithms not a list', lambda c: mutate(c, algorithms='HO'),
             'algorithms must be a non-empty array without duplicates, drawn from HO,GA,FirstFit,BestFit'),
            ('optimizer required', lambda c: c.pop('optimizer'),
             'optimizer is required when HO or GA is configured'),
            ('optimizer not an object', lambda c: mutate(c, optimizer=10),
             'optimizer is required when HO or GA is configured'),
            ('optimizer unused', lambda c: mutate(c, algorithms=['FirstFit', 'BestFit']),
             'optimizer is unused: algorithms exclude HO and GA'),
            ('population odd', lambda c: c['optimizer'].update(population=3),
             'optimizer.population must be an even integer of at least 2'),
            ('population one', lambda c: c['optimizer'].update(population=1),
             'optimizer.population must be an even integer of at least 2'),
            ('population missing', lambda c: c['optimizer'].pop('population'),
             'optimizer.population must be an even integer of at least 2'),
            ('iterations zero', lambda c: c['optimizer'].update(iterations=0),
             'optimizer.iterations must be an integer of at least 1'),
            ('iterations fractional', lambda c: c['optimizer'].update(iterations=1.5),
             'optimizer.iterations must be an integer of at least 1'),
            ('evaluation budget overflows', lambda c: c['optimizer'].update(population=5000, iterations=200000),
             f'evaluation budget must not exceed {INT_MAX}'),
        ]:
            with self.subTest(rule=label):
                self.rejected(change, message)

    def test_host_rules(self):
        for label, change, message in [
            ('class count zero', lambda c: c['hosts'][0].update(count=0),
             'hosts[0].count must be an integer from 1 to 100000'),
            ('class count high', lambda c: c['hosts'][0].update(count=100001),
             'hosts[0].count must be an integer from 1 to 100000'),
            ('class pes zero', lambda c: c['hosts'][0].update(pes=0),
             f'hosts[0].pes must be an integer from 1 to {INT_MAX}'),
            ('class mips zero', lambda c: c['hosts'][0].update(mips_per_pe=0),
             f'hosts[0].mips_per_pe must be an integer from 1 to {INT_MAX}'),
            ('class ram zero', lambda c: c['hosts'][0].update(ram_mib=0),
             f'hosts[0].ram_mib must be an integer from 1 to {INT_MAX}'),
            ('class bandwidth zero', lambda c: c['hosts'][0].update(bw_mbps=0),
             f'hosts[0].bw_mbps must be an integer from 1 to {INT_MAX}'),
            ('class storage zero', lambda c: c['hosts'][0].update(storage_mib=0),
             f'hosts[0].storage_mib must be an integer from 1 to {INT_MAX}'),
            ('class idle negative', lambda c: c['hosts'][0].update(idle_w=-1),
             f'hosts[0].idle_w must be an integer from 0 to {INT_MAX}'),
            ('class max below idle', lambda c: c['hosts'][0].update(max_w=99),
             f'hosts[0].max_w must be an integer from 100 to {INT_MAX}'),
            ('class field missing', lambda c: c['hosts'][0].pop('mips_per_pe'),
             f'hosts[0].mips_per_pe must be an integer from 1 to {INT_MAX}'),
            ('class not an object', lambda c: c.update(hosts=[1]),
             'hosts[0].count must be an integer from 1 to 100000'),
            ('hosts not a list', lambda c: c.update(hosts={}), 'hosts must be an array of at most 16 classes'),
            ('too many classes', lambda c: c.update(hosts=[dict(c['hosts'][0]) for _ in range(17)]),
             'hosts must be an array of at most 16 classes'),
            ('no hosts', lambda c: c.update(hosts=[]), 'total host count must be from 1 to 100000'),
            ('host total too high', lambda c: c.update(hosts=[dict(c['hosts'][0], count=100000),
                                                              dict(c['hosts'][0], count=1)]),
             'total host count must be from 1 to 100000'),
        ]:
            with self.subTest(rule=label):
                self.rejected(change, message)

    def test_vm_and_workload_rules(self):
        for label, change, message in [
            ('vms not an object', lambda c: c.update(vms=[]), f'vms.count must be an integer from 1 to 100000'),
            ('vm count zero', lambda c: c['vms'].update(count=0), 'vms.count must be an integer from 1 to 100000'),
            ('vm count high', lambda c: c['vms'].update(count=100001), 'vms.count must be an integer from 1 to 100000'),
            ('vm count fractional', lambda c: c['vms'].update(count=1.5),
             'vms.count must be an integer from 1 to 100000'),
            ('empty demand list', lambda c: c['vms'].update(pes=[]),
             'vms.pes must be a non-empty non-decreasing integer list or a min/max integer range'),
            ('decreasing demand list', lambda c: c['vms'].update(pes=[3, 2]),
             'vms.pes must be a non-empty non-decreasing integer list or a min/max integer range'),
            ('negative demand', lambda c: c['vms'].update(pes=[0]),
             'vms.pes must be a non-empty non-decreasing integer list or a min/max integer range'),
            ('demand string', lambda c: c['vms'].update(pes='1'),
             'vms.pes must be a non-empty non-decreasing integer list or a min/max integer range'),
            ('demand missing', lambda c: c['vms'].pop('pes'),
             'vms.pes must be a non-empty non-decreasing integer list or a min/max integer range'),
            ('inverted range', lambda c: c['vms'].update(pes={'min': 2, 'max': 1}),
             'vms.pes must be a non-empty non-decreasing integer list or a min/max integer range'),
            ('range out of bounds', lambda c: c['vms'].update(pes={'min': 0, 'max': 2}),
             'vms.pes must be a non-empty non-decreasing integer list or a min/max integer range'),
            ('mips not a multiple of the event divisor', lambda c: c['vms'].update(mips_per_pe=[3001]),
             'vms.mips_per_pe value 3001 must be a multiple of 8: the 0.125 s event interval must stay exact'),
            ('mips range with a non-multiple', lambda c: c['vms'].update(mips_per_pe={'min': 3000, 'max': 3008}),
             'vms.mips_per_pe value 3001 must be a multiple of 8: the 0.125 s event interval must stay exact'),
            ('mips divisor applies to the whole list', lambda c: c['vms'].update(mips_per_pe=[1000, 1400, 3004]),
             'vms.mips_per_pe value 3004 must be a multiple of 8: the 0.125 s event interval must stay exact'),
            ('too many declared values', lambda c: c['vms'].update(pes=list(range(1, 4098))),
             'vms.pes must not declare more than 4096 values'),
            ('range over the value limit', lambda c: c['vms'].update(pes={'min': 1, 'max': 4097}),
             'vms.pes must not declare more than 4096 values'),
            ('workload not an object', lambda c: c.update(workload=[]),
             'workload.length_seconds must be a non-empty non-decreasing integer list or a min/max integer range '
             'from 1 to 1440'),
            ('minutes zero', lambda c: c['workload'].update(length_seconds={'min': 0, 'max': 2}),
             'workload.length_seconds must be a non-empty non-decreasing integer list or a min/max integer range '
             'from 1 to 1440'),
            ('minutes high', lambda c: c['workload'].update(length_seconds=[1441]),
             'workload.length_seconds must be a non-empty non-decreasing integer list or a min/max integer range '
             'from 1 to 1440'),
            ('minutes decreasing', lambda c: c['workload'].update(length_seconds=[60, 30]),
             'workload.length_seconds must be a non-empty non-decreasing integer list or a min/max integer range '
             'from 1 to 1440'),
            ('minutes missing', lambda c: c['workload'].pop('length_seconds'),
             'workload.length_seconds must be a non-empty non-decreasing integer list or a min/max integer range '
             'from 1 to 1440'),
            ('utilization not full', lambda c: c['workload'].update(utilization=0.5),
             'workload.utilization must be 1.0: constant full utilization is the only simulated model'),
            ('utilization string', lambda c: c['workload'].update(utilization='1.0'),
             'workload.utilization must be 1.0: constant full utilization is the only simulated model'),
            ('utilization missing', lambda c: c['workload'].pop('utilization'),
             'workload.utilization must be 1.0: constant full utilization is the only simulated model'),
            ('release not zero', lambda c: c['workload'].update(release_seconds=1.0),
             'workload.release_seconds must be 0.0: a single simultaneous release is the only simulated model'),
            ('release missing', lambda c: c['workload'].pop('release_seconds'),
             'workload.release_seconds must be 0.0: a single simultaneous release is the only simulated model'),
        ]:
            with self.subTest(rule=label):
                self.rejected(change, message)

    def test_event_divisor_rule_precedes_the_host_class_ceiling(self):
        # 3010 is neither an exact multiple of 8 nor admitted by the 3000 MIPS/PE host class: the divisor wins.
        self.rejected(lambda c: c['vms'].update(mips_per_pe=[3010]),
                      'vms.mips_per_pe value 3010 must be a multiple of 8: the 0.125 s event interval must stay exact')
        self.rejected(lambda c: c['vms'].update(mips_per_pe=[3008]),
                      'vms.mips_per_pe value 3008 exceeds every host class mips_per_pe (3000)')
        config = deepcopy(self.tight)
        config['vms'].update(pes=[1, 2], mips_per_pe=[1000, 1400, 3000])
        self.assertEqual(runner.resolve_config(config)['vms']['mips_per_pe'], [1000, 1400, 3000])

    def test_demand_beyond_every_host_class_and_cross_products(self):
        self.rejected(lambda c: c['vms'].update(pes=[4]), 'vms.pes value 4 exceeds every host class pes (2)')
        self.rejected(lambda c: c['vms'].update(ram_mib=[16384]),
                      'vms.ram_mib value 16384 exceeds every host class ram_mib (8192)')
        self.rejected(lambda c: c.update(vms={'count': 100000, 'pes': [1], 'mips_per_pe': [1000], 'ram_mib': [1024],
                                             'bw_mbps': [1000], 'storage_mib': [1000]},
                                         hosts=[dict(c['hosts'][0], count=100000)]),
                      f'vms.count multiplied by total host count must not exceed {INT_MAX}')

    def test_cross_product_guards_stay_unreachable_at_the_declared_limits(self):
        # 2147483647**2 < 2**63-1 and 10000*4 < 2**31-1, so both guards are defensive only.
        config = deepcopy(self.tight)
        config['hosts'] = [dict(config['hosts'][0], count=100000, pes=INT_MAX, mips_per_pe=INT_MAX,
                                ram_mib=INT_MAX, bw_mbps=INT_MAX, storage_mib=INT_MAX)]
        config['vms'] = {'count': 1, 'pes': [1], 'mips_per_pe': [8], 'ram_mib': [1], 'bw_mbps': [1], 'storage_mib': [1]}
        config['replications'] = runner.LIMITS['MAX_REPLICATIONS']
        config['optimizer'] = {'population': 2, 'iterations': 1}
        resolved = runner.resolve_config(config)
        self.assertEqual(resolved['replications'], 10000)
        self.assertEqual(runner.planned_work(resolved), {'expected_cases': 40000, 'evaluation_budget': 8,
                                                         'expected_evaluations': 10000 * (2 * 8 + 2)})

    def test_baseline_only_configuration_resolves_without_optimizer(self):
        config = deepcopy(self.tight)
        config['algorithms'] = ['BestFit', 'FirstFit']
        config.pop('optimizer')
        resolved = runner.resolve_config(config)
        self.assertNotIn('optimizer', resolved)
        self.assertEqual(resolved['algorithms'], ['FirstFit', 'BestFit'])
        self.assertEqual(runner.planned_work(resolved), {'expected_cases': 2, 'evaluation_budget': 0,
                                                         'expected_evaluations': 2})
        self.assertNotIn('evaluation.budget', runner.effective(resolved, '0' * 64))


class ConfigurationLoading(unittest.TestCase):
    """Bytes in: UTF-8, strict JSON, duplicate fields, then the same resolution rules."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='scenario load ')
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)

    def write(self, name, data):
        path = self.base/name
        path.write_bytes(data if isinstance(data, bytes) else data.encode('utf-8'))
        return path

    def load_error(self, path):
        with self.assertRaises(runner.ConfigError) as caught:
            runner.load_config(path)
        return caught.exception

    def test_missing_and_unreadable_files(self):
        missing = self.base/'absent.json'
        with self.assertRaises(runner.UnreadableConfig) as caught:
            runner.load_config(missing)
        self.assertEqual(str(caught.exception), f'config file is missing or unreadable: {missing}')
        self.assertIsInstance(caught.exception, runner.ConfigError)
        with self.assertRaises(runner.UnreadableConfig):
            runner.load_config(self.base)

    def test_utf8_and_json_rejections(self):
        self.assertEqual(str(self.load_error(self.write('bad-utf8.json', b'{"name":"\xff"}'))),
                         'config must contain valid UTF-8')
        for label, text, message in [
            ('syntax', '{"name": }', 'invalid JSON: unexpected token } at name'),
            ('trailing comma', '{"name":"x",}', 'invalid JSON: expected a string key at root'),
            ('trailing content', '{"name":"x"} {}', 'invalid JSON: trailing content after the root object'),
            ('root array', '[1]', 'invalid JSON: the root value must be an object'),
            ('non-finite number', '{"master_seed":NaN}', 'invalid JSON: non-finite number'),
            ('malformed number', '{"master_seed":01}', 'invalid JSON: malformed number 01'),
            ('unquoted key', '{name:"x"}', 'invalid JSON: expected a string key at root'),
            ('single quotes', "{'name':'x'}", 'invalid JSON: expected a string key at root'),
        ]:
            with self.subTest(case=label):
                self.assertEqual(str(self.load_error(self.write(f'{label}.json', text))), message)

    def test_duplicate_fields_are_path_addressed(self):
        preset = json.dumps(runner.preset_config('known-answer-tight'))
        self.assertEqual(str(self.load_error(self.write('top.json', preset[:-1] + ',"name":"other"}'))),
                         'duplicate field: name')
        nested = preset.replace('"count": 2', '"count": 1, "count": 2', 1)
        self.assertEqual(str(self.load_error(self.write('nested.json', nested))), 'duplicate field: hosts[0].count')
        self.assertEqual(str(self.load_error(self.write('range.json', '{"config_version":1,"name":"a","master_seed":1,'
                                                           '"replications":1,"hosts":[{"count":1,"pes":1,"mips_per_pe":1,'
                                                           '"ram_mib":1,"bw_mbps":1,"storage_mib":1,"idle_w":0,"max_w":0}],'
                                                           '"vms":{"count":1,"pes":{"min":1,"min":2},"mips_per_pe":[1],'
                                                           '"ram_mib":[1],"bw_mbps":[1],"storage_mib":[1]},'
                                                           '"workload":{"length_seconds":[1],"utilization":1.0,'
                                                           '"release_seconds":0.0}}'))),
                         'duplicate field: vms.pes.min')

    def test_numeric_literals_follow_the_engine_integrality_rule(self):
        preset = runner.preset_config('known-answer-tight')
        preset['replications'] = '1e2'
        preset['config_version'] = '1.0'
        path = self.write('exponents.json', json.dumps(preset).replace('"replications": "1e2"', '"replications": 1e2')
                          .replace('"config_version": "1.0"', '"config_version": 1.0'))
        resolved = runner.resolve_config(runner.load_config(path))
        self.assertEqual(resolved['config_version'], 1)
        self.assertEqual(resolved['replications'], 100)
        self.assertIs(type(resolved['config_version']), int)
        huge = runner.preset_config('known-answer-tight')
        path = self.write('huge.json', json.dumps(huge).replace('"master_seed": 123456', '"master_seed": 1e400'))
        with self.assertRaises(runner.ConfigError) as caught:
            runner.resolve_config(runner.load_config(path))
        self.assertEqual(str(caught.exception), 'master_seed must be a signed 64-bit integer')

    def test_validate_config_matches_resolve_config(self):
        config = runner.preset_config('known-answer-tight')
        self.assertEqual(runner.validate_config(config), config)
        broken = runner.preset_config('known-answer-tight')
        broken['name'] = 'Nope'
        with self.assertRaises(runner.ConfigError):
            runner.validate_config(broken)
        with self.assertRaises(runner.ConfigError):
            runner.resolve_config([])


class ResolutionAndGeneration(unittest.TestCase):
    def resolved(self, name):
        return runner.resolve_config(runner.preset_config(name))

    def test_resolution_is_the_engine_record(self):
        resolved = self.resolved('known-answer-tight')
        self.assertEqual(resolved, {
            'config_version': 1, 'name': 'known-answer-tight', 'master_seed': 123456, 'replications': 1,
            'algorithms': ['HO', 'GA', 'FirstFit', 'BestFit'], 'optimizer': {'population': 10, 'iterations': 4},
            'hosts': [{'count': 2, 'pes': 2, 'mips_per_pe': 3000, 'ram_mib': 8192, 'bw_mbps': 2000,
                       'storage_mib': 20000, 'idle_w': 100, 'max_w': 200}],
            'vms': {'count': 2, 'pes': [2], 'mips_per_pe': [3000], 'ram_mib': [8192], 'bw_mbps': [2000],
                    'storage_mib': [20000]},
            'workload': {'length_seconds': [60], 'utilization': 1.0, 'release_seconds': 0.0}})
        self.assertEqual(list(resolved['hosts'][0]), list(runner.HOST_KEYS))
        self.assertIs(type(resolved['config_version']), int)
        self.assertIs(type(resolved['workload']['utilization']), float)
        self.assertIs(type(resolved['workload']['release_seconds']), float)
        self.assertIs(type(resolved['vms']['pes'][0]), int)

    def test_range_expansion_algorithm_order_and_defaults(self):
        config = {'config_version': 1, 'name': 'ranges', 'master_seed': -5, 'replications': 3,
                  'optimizer': {'population': 10, 'iterations': 4},
                  'hosts': [{'count': 2, 'pes': 8, 'mips_per_pe': 3000, 'ram_mib': 16384, 'bw_mbps': 4000,
                             'storage_mib': 40000, 'idle_w': 100, 'max_w': 200}],
                  'vms': {'count': 4, 'pes': {'min': 1, 'max': 3}, 'mips_per_pe': [1000, 1000, 2000],
                          'ram_mib': {'min': 1024, 'max': 1025}, 'bw_mbps': [1000, 1000], 'storage_mib': [10000]},
                  'workload': {'length_seconds': {'min': 30, 'max': 32}, 'utilization': 1.0, 'release_seconds': 0.0}}
        resolved = runner.resolve_config(config)
        self.assertEqual(resolved['algorithms'], ['HO', 'GA', 'FirstFit', 'BestFit'])
        self.assertEqual(resolved['optimizer'], {'population': 10, 'iterations': 4})
        self.assertEqual(resolved['vms']['pes'], [1, 2, 3])
        self.assertEqual(resolved['vms']['mips_per_pe'], [1000, 1000, 2000])
        self.assertEqual(resolved['vms']['ram_mib'], [1024, 1025])
        self.assertEqual(resolved['workload']['length_seconds'], [30, 31, 32])
        ordered = dict(config, algorithms=['BestFit', 'HO', 'GA', 'FirstFit', 'GA'])
        with self.assertRaises(runner.ConfigError):
            runner.resolve_config(ordered)
        ordered = dict(config, algorithms=['BestFit', 'HO', 'GA', 'FirstFit'])
        self.assertEqual(runner.resolve_config(ordered)['algorithms'], ['HO', 'GA', 'FirstFit', 'BestFit'])
        head = runner.request_json(runner.preset_config('known-answer-feasible'))
        self.assertEqual(head['algorithms'], ['HO', 'GA', 'FirstFit', 'BestFit'])
        self.assertEqual(runner.request_json({'config_version': 1})['algorithms'], list(runner.ALGORITHMS))
        self.assertIn('algorithms', runner.preset_config('known-answer-tight'),
                      'presets already declare their algorithms')

    def test_presets_are_complete_and_private(self):
        self.assertEqual(list(runner.PRESETS),
                         ['known-answer-feasible', 'known-answer-tight', 'known-answer-infeasible',
                          'heterogeneous-hosts', 'resource-pressure'])
        for name in runner.PRESETS:
            with self.subTest(preset=name):
                config = runner.preset_config(name)
                self.assertEqual(config['config_version'], 1)
                self.assertEqual(config['name'], name)
                self.assertIs(type(config['master_seed']), int)
                self.assertIs(type(config['replications']), int)
                self.assertEqual(config['algorithms'], ['HO', 'GA', 'FirstFit', 'BestFit'])
                self.assertEqual(config['optimizer'], {'population': 10, 'iterations': 4})
                self.assertEqual(runner.validate_config(config)['name'], name)
                self.assertEqual(runner.resolve_config(config)['name'], name)
                self.assertEqual(len(set(config['vms']['mips_per_pe'])), len(config['vms']['mips_per_pe']))
                for value in config['vms']['mips_per_pe']:
                    self.assertEqual(value % runner.EXACT_EVENT_DIVISOR, 0,
                                     'declared VM MIPS/PE must divide the 0.125 s event interval exactly')
        config = runner.preset_config('known-answer-tight')
        config['name'] = 'mutated'
        self.assertEqual(runner.PRESETS['known-answer-tight']['name'], 'known-answer-tight')

    def test_preset_classes(self):
        expected = {'known-answer-feasible': [(0, 'CONSTRUCTIBLE', None)],
                    'known-answer-tight': [(0, 'CONSTRUCTIBLE', None)],
                    'known-answer-infeasible': [(0, 'INFEASIBLE_PROVED',
                                                 'replication 0 total ram_mib demand 8192 exceeds total host ram_mib '
                                                 'capacity 4096')],
                    'heterogeneous-hosts': [(0, 'CONSTRUCTIBLE', None), (1, 'CONSTRUCTIBLE', None)],
                    'resource-pressure': [(0, 'CONSTRUCTIBLE', None)]}
        for name, records in expected.items():
            with self.subTest(preset=name):
                resolved = self.resolved(name)
                self.assertEqual([(entry['replication'], entry['kind'], entry['reason'])
                                  for entry in runner.feasibilities(resolved)], records)

    def test_family_properties(self):
        heterogeneous = self.resolved('heterogeneous-hosts')
        classes = heterogeneous['hosts']
        self.assertGreaterEqual(len(classes), 3)
        self.assertEqual(len({host_class['mips_per_pe'] for host_class in classes}), len(classes))
        self.assertEqual(len({(host_class['idle_w'], host_class['max_w']) for host_class in classes}), len(classes))
        self.assertEqual([host_class['pes'] for host_class in classes], [32, 16, 8])
        pressure = self.resolved('resource-pressure')
        self.assertEqual(len(pressure['hosts']), 1)
        spec = runner.generated_spec(pressure, 0)
        demand = sum(vm['ramMiB'] for vm in spec['inputs']['vms'])
        capacity = sum(host['ramMiB'] for host in spec['inputs']['hosts'])
        self.assertGreaterEqual(demand/capacity, 0.85)
        self.assertGreaterEqual(len({host_class['mips_per_pe'] for host_class in heterogeneous['hosts']}), 3)

    def test_heterogeneous_declares_witness_admissible_vm_speeds(self):
        heterogeneous = self.resolved('heterogeneous-hosts')
        self.assertEqual([host_class['mips_per_pe'] for host_class in heterogeneous['hosts']], [1500, 3000, 2000])
        self.assertEqual(heterogeneous['vms']['mips_per_pe'], [1000, 1400])
        slowest = min(host_class['mips_per_pe'] for host_class in heterogeneous['hosts'])
        self.assertLessEqual(max(heterogeneous['vms']['mips_per_pe']), slowest)

    def test_two_families_differ_by_configuration_alone(self):
        heterogeneous = runner.generated_spec(self.resolved('heterogeneous-hosts'), 0)
        pressure = runner.generated_spec(self.resolved('resource-pressure'), 0)
        self.assertEqual(len({host['mipsPerPe'] for host in heterogeneous['inputs']['hosts']}), 3)
        self.assertEqual(len({host['mipsPerPe'] for host in pressure['inputs']['hosts']}), 1)
        self.assertNotEqual(heterogeneous['inputs']['seeds'], pressure['inputs']['seeds'])

    def test_generated_spec_is_deterministic_and_engine_exact(self):
        resolved = self.resolved('known-answer-tight')
        spec = runner.generated_spec(resolved, 0)
        self.assertEqual(spec, runner.generated_spec(resolved, 0))
        self.assertNotEqual(spec['inputs']['seeds'], runner.generated_spec(resolved, 1)['inputs']['seeds'])
        self.assertEqual(spec['inputs']['seeds'], TIGHT_SEEDS)
        self.assertEqual(spec['inputs']['hosts'], [{'id': 0, 'pes': 2, 'mipsPerPe': 3000, 'ramMiB': 8192,
                                                    'bwMbps': 2000, 'storageMiB': 20000, 'idleW': 100, 'maxW': 200},
                                                   {'id': 1, 'pes': 2, 'mipsPerPe': 3000, 'ramMiB': 8192,
                                                    'bwMbps': 2000, 'storageMiB': 20000, 'idleW': 100, 'maxW': 200}])
        self.assertEqual(spec['inputs']['vms'], [{'id': 0, 'pes': 2, 'mipsPerPe': 3000, 'ramMiB': 8192,
                                                  'bwMbps': 2000, 'storageMiB': 20000},
                                                 {'id': 1, 'pes': 2, 'mipsPerPe': 3000, 'ramMiB': 8192,
                                                  'bwMbps': 2000, 'storageMiB': 20000}])
        self.assertEqual(spec['inputs']['cloudlets'], [{'id': 0, 'vmId': 0, 'pes': 2, 'lengthMi': 180000},
                                                       {'id': 1, 'vmId': 1, 'pes': 2, 'lengthMi': 180000}])
        self.assertEqual(spec['reference_seconds'], [60.0, 60.0])
        self.assertEqual(spec['censor_s'], 600.0)
        self.assertEqual(spec['canonical_text'], '\n'.join(TIGHT_CANONICAL) + '\n')
        self.assertEqual(spec['scenario_sha256'], TIGHT_SPEC_SHA256)
        self.assertEqual(spec['scenario_sha256'],
                         hashlib.sha256(spec['canonical_text'].encode('utf-8')).hexdigest())
        self.assertEqual(json.loads(json.dumps(spec)), spec)
        for index, seconds in enumerate(spec['reference_seconds']):
            self.assertEqual(seconds, float(spec['inputs']['cloudlets'][index]['lengthMi'] //
                                            spec['inputs']['vms'][index]['mipsPerPe']))

    def test_heterogeneous_spec_uses_file_order_and_one_draw_per_field(self):
        resolved = self.resolved('heterogeneous-hosts')
        spec = runner.generated_spec(resolved, 0)
        hosts = spec['inputs']['hosts']
        self.assertEqual(len(hosts), 18)
        self.assertEqual([host['id'] for host in hosts], list(range(18)))
        self.assertEqual([host['pes'] for host in hosts[:4]], [32] * 4)
        self.assertEqual([host['pes'] for host in hosts[4:12]], [16] * 8)
        self.assertEqual([host['maxW'] for host in hosts[12:]], [140] * 6)
        self.assertEqual(len(spec['inputs']['vms']), 48)
        for vm in spec['inputs']['vms']:
            self.assertIn(vm['pes'], resolved['vms']['pes'])
            self.assertIn(vm['mipsPerPe'], resolved['vms']['mips_per_pe'])
            self.assertIn(vm['ramMiB'], resolved['vms']['ram_mib'])
        self.assertEqual(spec['censor_s'], 10 * max(spec['reference_seconds']))


class EffectiveAndPlannedWork(unittest.TestCase):
    def test_effective_map_and_bytes(self):
        resolved = runner.resolve_config(runner.preset_config('known-answer-tight'))
        values = runner.effective(resolved, 'a' * 64)
        self.assertEqual(values, {
            'algorithms': 'HO,GA,FirstFit,BestFit', 'config.name': 'known-answer-tight', 'config.sha256': 'a' * 64,
            'evaluation.budget': '130', 'expected.cases': '4', 'expected.evaluations': '262',
            'experiment.kind': 'static_scenario', 'experiment.phase': 'scenario', 'host.classes': '1',
            'host.count': '2', 'master.seed': '123456', 'optimizer.iterations': '4', 'optimizer.population': '10',
            'profile': 'scenario', 'replication.count': '1', 'scenario.schema.version': '1',
            'utilization.model': 'constant_full', 'utilization.value': '1.0', 'vm.count': '2',
            'workload.length.seconds': '60,60'})
        self.assertTrue(all(isinstance(value, str) for value in values.values()))
        rendered = runner.render_effective(resolved, 'a' * 64)
        self.assertEqual(rendered, ''.join(f'{key}={values[key]}\n' for key in sorted(values)))
        self.assertEqual(rendered.splitlines(), sorted(rendered.splitlines()))
        ranges = runner.resolve_config(runner.preset_config('heterogeneous-hosts'))
        self.assertEqual(runner.effective(ranges, 'b' * 64)['workload.length.seconds'], '30,120')
        self.assertEqual(runner.effective(ranges, 'b' * 64)['host.classes'], '3')
        self.assertEqual(runner.effective(ranges, 'b' * 64)['host.count'], '18')

    def test_planned_work_values(self):
        tight = runner.resolve_config(runner.preset_config('known-answer-tight'))
        self.assertEqual(runner.planned_work(tight),
                         {'expected_cases': 4, 'evaluation_budget': 130, 'expected_evaluations': 262})
        search_only = deepcopy(tight)
        search_only['algorithms'] = ['GA', 'HO']
        self.assertEqual(runner.planned_work(search_only),
                         {'expected_cases': 2, 'evaluation_budget': 130, 'expected_evaluations': 260})
        heterogeneous = runner.resolve_config(runner.preset_config('heterogeneous-hosts'))
        self.assertEqual(runner.planned_work(heterogeneous),
                         {'expected_cases': 8, 'evaluation_budget': 130, 'expected_evaluations': 524})

    def test_engine_texts_are_pinned(self):
        self.assertEqual(runner.MODEL_LIMITS, MODEL_LIMITS)
        self.assertEqual(runner.EVIDENCE_LIMIT, EVIDENCE_LIMIT)
        self.assertEqual(runner.ALLOCATION_TIMING, ALLOCATION_TIMING)
        self.assertEqual(runner.PROTOCOL, 'static_scenario_v1')
        self.assertEqual(runner.PHASE, 'scenario')
        self.assertEqual(runner.SCENARIO_SCHEMA_VERSION, 1)
        self.assertEqual(runner.CONFIG_VERSION, 1)
        self.assertEqual(runner.ALGORITHMS, ('HO', 'GA', 'FirstFit', 'BestFit'))
        self.assertEqual(runner.SEARCH_ALGORITHMS, ('HO', 'GA'))
        self.assertEqual(runner.LIMITS, {'MAX_HOST_CLASSES': 16, 'MAX_HOSTS': 100000, 'MAX_VMS': 100000,
                                         'MAX_REPLICATIONS': 10000, 'MAX_DECLARED_VALUES': 4096, 'MIN_SECONDS': 1,
                                         'MAX_SECONDS': 1440})
        self.assertEqual(runner.EXACT_EVENT_DIVISOR, 8)


class RetainedArtifacts(unittest.TestCase):
    """completed_run is the Java/Python parity gate for one retained artifact."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='scenario artifact ')
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.digest = 'c' * 64

    def fixture(self, preset='known-answer-tight', complete=True):
        resolved = runner.resolve_config(runner.preset_config(preset))
        request = json.dumps(runner.preset_config(preset), indent=2).encode('utf-8') + b'\n'
        recorded = hashlib.sha256(request).hexdigest()
        plan = runner.planned_work(resolved)
        expected = plan['expected_cases'] if complete else 1
        manifest = {'experiment_kind': 'static_scenario', 'scenario_schema_version': 1, 'protocol': runner.PROTOCOL,
                    'profile': 'scenario', 'run_id': 'scenario-test', 'config_name': resolved['name'],
                    'config_sha256': recorded, 'state': 'COMPLETE' if complete else 'FAILED',
                    'error': None if complete else {'code': 'SCENARIO_INFEASIBLE', 'message': 'replication 0'},
                    'artifact_sha256': self.digest, 'master_seed': resolved['master_seed'],
                    'resolved_config': resolved, 'effective_config': runner.effective(resolved, recorded),
                    'feasibility': runner.feasibilities(resolved), 'expected_cases': plan['expected_cases'],
                    'attempted_cases': expected, 'successful_cases': expected if complete else 0,
                    'failed_cases': 0 if complete else 1, 'unattempted_cases': plan['expected_cases'] - expected,
                    'expected_evaluations': plan['expected_evaluations'],
                    'completed_evaluations': plan['expected_evaluations'] if complete else 0}
        parent = Path(tempfile.mkdtemp(prefix='run-', dir=self.base))/'artifacts'
        run = parent/'scenario-test'
        (run/'config').mkdir(parents=True)
        (run/'config'/'requested.json').write_bytes(request)
        (run/'config'/'resolved.json').write_text(json.dumps(resolved, indent=2) + '\n')
        (run/'run.json').write_text(json.dumps(manifest, indent=2) + '\n')
        return parent, run, resolved

    def rewrite(self, run, manifest):
        (run/'run.json').write_text(json.dumps(manifest, indent=2) + '\n')

    def test_accepts_a_consistent_artifact(self):
        parent, run, resolved = self.fixture()
        self.assertEqual(runner.completed_run(parent, self.digest, resolved), run)

    def test_rejects_tampered_artifacts(self):
        for label, tamper in [
            ('tampered resolved.json', lambda parent, run, resolved: (run/'config'/'resolved.json').write_text(
                json.dumps(dict(resolved, name='other'), indent=2) + '\n')),
            ('tampered requested.json', lambda parent, run, resolved: (
                run/'config'/'requested.json').write_bytes(b'{}')),
            ('tampered effective_config', lambda parent, run, resolved: self.rewrite(
                run, dict(json.loads((run/'run.json').read_text()), effective_config={}))),
            ('tampered feasibility', lambda parent, run, resolved: self.rewrite(
                run, dict(json.loads((run/'run.json').read_text()), feasibility=[]))),
            ('tampered counters', lambda parent, run, resolved: self.rewrite(
                run, dict(json.loads((run/'run.json').read_text()), successful_cases=0))),
            ('tampered resolution', lambda parent, run, resolved: self.rewrite(
                run, dict(json.loads((run/'run.json').read_text()), resolved_config={'name': 'other'}))),
            ('tampered name', lambda parent, run, resolved: self.rewrite(
                run, dict(json.loads((run/'run.json').read_text()), config_name='other'))),
            ('tampered digest', lambda parent, run, resolved: self.rewrite(
                run, dict(json.loads((run/'run.json').read_text()), artifact_sha256='d' * 64))),
        ]:
            with self.subTest(tamper=label):
                parent, run, resolved = self.fixture()
                tamper(parent, run, resolved)
                with self.assertRaises(ValueError):
                    runner.completed_run(parent, self.digest, resolved)

    def test_rejects_failed_runs_wrong_digest_and_ambiguous_parents(self):
        parent, run, resolved = self.fixture('known-answer-infeasible', complete=False)
        with self.assertRaises(ValueError):
            runner.completed_run(parent, self.digest, resolved)
        parent, run, resolved = self.fixture()
        with self.assertRaises(ValueError):
            runner.completed_run(parent, 'd' * 64, resolved)
        (parent/'scenario-extra').mkdir()
        with self.assertRaises(ValueError):
            runner.completed_run(parent, self.digest, resolved)
        self.assertEqual(runner.feasibilities(resolved)[0]['kind'], 'CONSTRUCTIBLE')

    def test_rejects_a_directory_that_is_not_a_run(self):
        parent = self.base/'empty'
        parent.mkdir()
        with self.assertRaises(ValueError):
            runner.completed_run(parent, self.digest, runner.resolve_config(runner.preset_config('known-answer-tight')))


class ArgumentParsing(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='scenario args ')
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)

    def parse(self, argv):
        error = io.StringIO()
        with redirect_stderr(error):
            try:
                return runner.parse_args(argv), error.getvalue()
            except SystemExit as exit:
                return exit.code, error.getvalue()

    def test_exactly_one_of_config_or_preset(self):
        for argv in [[], ['--config', str(self.base/'x.json'), '--preset', 'known-answer-tight'],
                     ['--preset', 'known-answer-tight', '--config', str(self.base/'x.json')]]:
            with self.subTest(argv=argv):
                code, error = self.parse(argv)
                self.assertEqual(code, 2)
                self.assertIn('exactly one of --config or --preset is required', error)

    def test_rejects_stress_flags_duplicates_unknowns_and_build_conflicts(self):
        for argv, fragment in [
            (['--preset', 'known-answer-tight', '--vms', '10'], '--vms configures the stress profile'),
            (['--preset', 'known-answer-tight', '--hosts=3'], '--hosts configures the stress profile'),
            (['--preset', 'known-answer-tight', '--population', '4'], '--population configures the stress profile'),
            (['--preset', 'known-answer-tight', '--iterations', '2'], '--iterations configures the stress profile'),
            (['--preset', 'known-answer-tight', '--seed', '1'], '--seed configures the stress profile'),
            (['--preset', 'known-answer-tight', '--replications', '2'], '--replications configures the stress profile'),
            (['--preset', 'known-answer-tight', '--preset', 'resource-pressure'], 'duplicate options are not supported'),
            (['--preset', 'known-answer-tight', '--bogus'], 'unrecognized arguments'),
            (['--preset', 'known-answer-tight', '--skip-build', '--force-build'],
             '--force-build and --skip-build are incompatible'),
            (['--preset', 'known-answer-tight', '--heap-mib', '1'], 'at least 512'),
            (['--preset', 'known-answer-tight', '--workers', '33'], 'workers must be auto or an integer from 1 to 32'),
            (['--preset', 'known-answer-tight', '--time-limit', '0'], 'time limit must be positive'),
            (['--preset', 'known-answer-tight', '--output-dir', ' '], 'output directory is required'),
        ]:
            with self.subTest(argv=argv):
                code, error = self.parse(argv)
                self.assertEqual(code, 2)
                self.assertIn(fragment, error)

    def test_cross_profile_presets_are_rejected(self):
        for name in ['micro', 'tiny', 'small', 'medium', 'large', 'xlarge']:
            with self.subTest(preset=name):
                code, error = self.parse(['--preset', name])
                self.assertEqual(code, 2)
                self.assertIn('invalid choice', error)

    def test_missing_file_is_an_io_error_and_content_errors_exit_two(self):
        missing = self.base/'absent.json'
        code, error = self.parse(['--config', str(missing)])
        self.assertEqual(code, 1)
        self.assertEqual(error.strip(), f'IO_ERROR: config file is missing or unreadable: {missing}')
        for label, text, fragment in [
            ('unknown field', '{"config_version":1,"bogus":1}', 'unknown field: bogus'),
            ('invalid JSON', '{"config_version":', 'invalid JSON: '),
            ('duplicate', '{"a":1,"a":2}', 'duplicate field: a'),
            ('seed', json.dumps(dict(runner.preset_config('known-answer-tight'), master_seed=1.5)),
             'master_seed must be a signed 64-bit integer'),
        ]:
            with self.subTest(case=label):
                path = self.base/f'{label}.json'
                path.write_text(text)
                code, error = self.parse(['--config', str(path)])
                self.assertEqual(code, 2)
                self.assertIn(fragment, error)
        path = self.base/'bad-utf8.json'
        path.write_bytes(b'{"name":"\xff"}')
        code, error = self.parse(['--config', str(path)])
        self.assertEqual(code, 2)
        self.assertIn('config must contain valid UTF-8', error)

    def test_accepts_both_sources_and_reports_resolved_values(self):
        path = self.base/'tight.json'
        path.write_text(json.dumps(runner.preset_config('known-answer-tight')))
        args, error = self.parse(['--config', str(path), '--time-limit', '30m', '--workers', '2', '--heap-mib', '2048'])
        self.assertEqual(error, '')
        self.assertEqual(args.config_path, path)
        self.assertEqual(args.source, str(path))
        self.assertIsNone(args.preset)
        self.assertEqual(args.time_limit, 1800.0)
        self.assertEqual(args.workers, 2)
        self.assertEqual(args.heap_mib, 2048)
        self.assertEqual(args.expected_cases, 4)
        self.assertEqual(args.resolved['name'], 'known-answer-tight')
        self.assertEqual(args.request['name'], 'known-answer-tight')
        self.assertIsNone(runner.parse_args(['--preset', 'known-answer-tight']).time_limit)
        self.assertEqual(runner.parse_args(['--preset', 'known-answer-tight']).source, 'preset:known-answer-tight')
        self.assertIsNone(runner.parse_args(['--preset', 'known-answer-tight']).config_path)
        self.assertEqual(runner.parse_args(['--preset', 'known-answer-tight', '--time-limit', 'none']).time_limit, None)
        self.assertEqual(runner.parse_args(['--preset', 'known-answer-tight', '--dry-run']).dry_run, True)


class EngineCommand(unittest.TestCase):
    def test_java_command_argv(self):
        command = runner.java_command(Path('/jdk/bin/java'), Path('/cfg/request.json'), Path('/jars/scenario.jar'),
                                      Path('/out/artifacts'), 4, heap_mib=2048)
        self.assertEqual(command[0], '/jdk/bin/java')
        self.assertIn('-Xmx2048m', command)
        self.assertIn('-Dcloudsim.workers=4', command)
        self.assertEqual(command[command.index('-jar') + 1], '/jars/scenario.jar')
        self.assertEqual(command[command.index('--profile') + 1], 'scenario')
        self.assertEqual(command[command.index('--output-dir') + 1], '/out/artifacts')
        self.assertNotIn('/out/artifacts/research', command)
        self.assertEqual(command[-2:], ['--config', '/cfg/request.json'])
        single = runner.java_command(Path('/jdk/bin/java'), Path('/cfg/request.json'), Path('/jars/scenario.jar'),
                                     Path('/out/artifacts'), 1)
        self.assertIn('-Xmx1024m', single)
        self.assertNotIn('-Dcloudsim.workers=1', single)


class ProgressAndPreview(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='scenario progress ')
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.resolved = runner.resolve_config(runner.preset_config('known-answer-tight'))

    def test_progress_projects_the_engine_snapshot(self):
        parent = self.base/'artifacts'
        run = parent/'scenario-test'
        run.mkdir(parents=True)
        (run/'progress.json').write_text(json.dumps(
            {'experiment_kind': 'static_scenario', 'scenario_schema_version': 1, 'run_id': 'scenario-test',
             'state': 'RUNNING', 'expected_cases': 4, 'attempted_cases': 2, 'successful_cases': 1, 'failed_cases': 0,
             'unattempted_cases': 2, 'expected_evaluations': 262, 'completed_evaluations': 130, 'error': None,
             'current_case': {'phase': 'scenario', 'scenario': 'known-answer-tight', 'replication': 0, 'algorithm': 'GA',
                              'population': 10, 'iterations': 4, 'evaluation': 0}}))
        snapshot = runner.progress(parent, self.resolved, None)
        self.assertEqual(snapshot['done'], 1)
        self.assertEqual(snapshot['total'], 4)
        self.assertEqual((snapshot['evaluations'], snapshot['evaluations_total']), (130, 262))
        self.assertEqual(snapshot['algorithm'], 'GA')
        self.assertIsNone(snapshot['deadline_remaining'])
        self.assertTrue(snapshot['deadline_unlimited'])
        self.assertIn('evaluations 130/262', snapshot['detail'])

    def test_progress_without_a_snapshot_stays_unavailable(self):
        snapshot = runner.progress(self.base/'absent', self.resolved, None)
        self.assertIsNone(snapshot['done'])
        self.assertEqual(snapshot['total'], 4)
        self.assertIn('Progress unavailable', snapshot['detail'])
        broken = self.base/'broken'/'artifacts'
        (broken/'scenario-test').mkdir(parents=True)
        (broken/'scenario-test'/'progress.json').write_text('{"successful_cases":99,"completed_evaluations":0,"expected_evaluations":1}')
        self.assertIn('Progress unavailable', runner.progress(broken, self.resolved, None)['detail'])


class DryRun(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='scenario dry ')
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)

    def test_dry_run_previews_every_contract_field_and_creates_nothing(self):
        output = io.StringIO()
        directory = self.base/'dry'
        with redirect_stdout(output):
            code = runner.main(['--preset', 'resource-pressure', '--dry-run', '--plain', '--output-dir', str(directory)])
        self.assertEqual(code, 0)
        self.assertFalse(directory.exists())
        self.assertEqual(list(self.base.iterdir()), [])
        text = output.getvalue()
        payload = json.JSONDecoder().raw_decode(text[text.index('{'):])[0]
        self.assertEqual(sorted(payload), ['action', 'commands', 'config', 'execution', 'feasibility', 'memory_evidence',
                                           'planned_work', 'profile', 'resolved_config', 'time_limit_scope', 'warnings'])
        self.assertEqual(payload['action'], 'dry-run')
        self.assertEqual(payload['profile'], 'scenario')
        self.assertEqual(payload['config']['name'], 'resource-pressure')
        self.assertEqual(payload['resolved_config']['workload']['length_seconds'], list(range(60, 121)))
        self.assertEqual(payload['planned_work'], {'expected_cases': 4, 'evaluation_budget': 130,
                                                   'expected_evaluations': 262})
        self.assertEqual([entry['kind'] for entry in payload['feasibility']], ['CONSTRUCTIBLE'])
        self.assertEqual(payload['time_limit_scope'], 'production + validation; excludes build/tests')
        self.assertEqual(len(payload['commands']), 1)
        self.assertIn('--profile scenario', payload['commands'][0])
        self.assertIn('<CONFIG>', payload['commands'][0])
        self.assertEqual(payload['warnings'], ['No --time-limit: production and its validation may run unbounded.'])
        self.assertIn('Production: 4 cases / 262 evaluations', text)
        self.assertIn('Replication 0: CONSTRUCTIBLE', text)
        self.assertEqual(json.loads(json.dumps(payload)), payload)

    def test_dry_run_with_a_file_reports_the_request_and_applies_the_time_limit(self):
        path = self.base/'tight.json'
        path.write_text(json.dumps(runner.preset_config('known-answer-tight')))
        output = io.StringIO()
        with redirect_stdout(output):
            code = runner.main(['--config', str(path), '--dry-run', '--plain', '--time-limit', '90s',
                                '--output-dir', str(self.base/'dry')])
        self.assertEqual(code, 0)
        text = output.getvalue()
        payload = json.JSONDecoder().raw_decode(text[text.index('{'):])[0]
        self.assertEqual(payload['config']['workload']['length_seconds'], {'min': 60, 'max': 60})
        self.assertEqual(payload['resolved_config']['workload']['length_seconds'], [60])
        self.assertEqual(payload['warnings'], [])
        self.assertFalse((self.base/'dry').exists())

    def test_resolve_time_evaluation_and_workload_bounds_are_enforced(self):
        document = dict(runner.PRESETS['known-answer-tight'])
        document['replications'] = 10000
        document['optimizer'] = {'population': 2, 'iterations': 357913940}
        with self.assertRaisesRegex(runner.ConfigError,
                                    'total expected evaluations must not exceed 2147483647'):
            runner.resolve_config(runner.request_json(document))
        overflowing = dict(runner.PRESETS['known-answer-tight'],
                           hosts=[{'count': 4, 'pes': 2147483647, 'mips_per_pe': 2147483640, 'ram_mib': 65536,
                                   'bw_mbps': 20000, 'storage_mib': 2000000, 'idle_w': 220, 'max_w': 380}],
                           vms={'count': 1, 'pes': [2147483647], 'mips_per_pe': [2147483640],
                                'ram_mib': [1024], 'bw_mbps': [1000], 'storage_mib': [10000]})
        with self.assertRaisesRegex(runner.ConfigError,
                                    "a VM's workload length in MI does not fit a signed 64-bit integer"):
            runner.resolve_config(runner.request_json(overflowing))
        document = dict(runner.PRESETS['known-answer-tight'], vms={'count': 2, 'pes': [1],
                           'mips_per_pe': [3000], 'ram_mib': [8192], 'bw_mbps': [2000], 'storage_mib': [20000]})
        try:
            runner.resolve_config(runner.request_json(dict(document, vms=dict(document['vms'], pes={'min': 1, 'max': 2147483647}))))
            self.fail('expected the declared-values cap')
        except runner.ConfigError as error:
            self.assertIn('vms.pes must not declare more than 4096 values', str(error))

    def test_documented_presets_match_the_shipped_table(self):
        # A user copying SCENARIOS.md must get exactly the shipped preset.
        document = (runner.ROOT/'SCENARIOS.md').read_text(encoding='utf-8')
        blocks = [json.loads(block) for block in re.findall(r'```json\n(.*?)```', document, re.S)]
        for name, preset in runner.PRESETS.items():
            matches = [block for block in blocks if block.get('name') == name]
            self.assertEqual(len(matches), 1, name)
            self.assertEqual(json.loads(json.dumps(matches[0], sort_keys=True)), json.loads(json.dumps(preset, sort_keys=True)), name)
        self.assertEqual({block.get('name') for block in blocks}, set(runner.PRESETS)|{'my-scenario'})


if __name__ == '__main__':
    unittest.main()
