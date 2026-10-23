#!/usr/bin/env python3
"""Independent, streaming static scenario-schema-1 verification (stdlib only).

Nothing recorded is trusted: the request digest, the resolved document, the
effective map, per-replication generation, feasibility classes, the case matrix
and its seeds, the final placements with the host-relative power/energy model,
scalar trace order and the eligible-best chain, the summary means and every
counter are re-derived from the request bytes and the configuration model.
Scalar traces establish budgets/order/eligible-best consistency, not optimizer
replay; coherently changed discarded-candidate scalars may remain undetectable.
Provenance metadata records artifact identity, not artifact authenticity.
Numerical tolerance: abs=1e-10, rel=2e-12, as in frozen schema-2 verification.
"""
import argparse
import csv
from datetime import datetime
import hashlib
import json
import re
import sys
from pathlib import Path

import scenario_runner
from statistics_validator import close, finite, require, seed
from stress_validator import digest, integer, load, rows, take, trace_order

SCENARIO_SCHEMA_VERSION = 1
PROTOCOL = 'static_scenario_v1'
PHASE = 'scenario'
# The engine's minimum event interval fixes the observed common workload release.
RELEASE_SECONDS = 0.125
PHASES = ('INITIAL', 'CHILD', 'RIVER_MALE', 'RIVER_FEMALE', 'PREDATOR', 'DEFENSE', 'ESCAPE')
ID = 'scenario_schema_version,run_id,phase,scenario,replication,algorithm,population,iterations'
CASE = ID + ',scenario_seed,workload_seed,optimizer_seed,scenario_sha256,status,error_code,error_message,vm_count,host_count,evaluations,objective_w,energy_j,energy_kwh,sla_violations,sla_rate,completed_cloudlets,failed_cloudlets,censored_cloudlets,horizon_s,release_s,allocation_wall_ns'
PLACE = ID + ',vm_id,host_id'
TRACE = ID + ',evaluation,iteration,stage,candidate_index,feasible,fitness_w,accepted,best_fitness_w'
SUMMARY = 'scenario_schema_version,run_id,phase,scenario,algorithm,n,objective_mean_w,energy_mean_j,sla_mean,allocation_mean_ns'
EVIDENCE_LIMIT = ('Scalar traces check order, budgets and eligible-best consistency; they cannot '
                  'independently replay discarded candidates or guarantee detection of coherent non-best scalar tampering.')
ALLOCATION_TIMING = ('End-to-end search including synchronous buffered scalar trace writing; '
                     'not pure CPU or directly comparable to frozen allocation timing.')
MODEL_LIMITS = ('Only constant full utilization (1.0), one cloudlet per VM, VmSchedulerSpaceShared, '
                'CloudletSchedulerSpaceShared, no migrations, no overcommit, a single simultaneous release and a linear '
                "idle-to-max power curve over the host's own total MIPS are simulated. PE, MIPS, RAM, bandwidth and storage "
                'limits are static reservation admission checks; they are not simulated network or storage contention.')


def placement_metrics(spec, placements):
    """Objective/energy from the host's own total MIPS: capacity is never assumed frozen."""
    inputs = spec['inputs']
    hosts, vms, references = inputs['hosts'], inputs['vms'], spec['reference_seconds']
    require(len(placements) == len(vms), 'Wrong final placement count')
    used = [[0] * 5 for _ in hosts]
    work = [0.0] * len(hosts)
    for vm, host_id, duration in zip(vms, placements, references):
        require(0 <= host_id < len(hosts), 'Unknown final host')
        host = hosts[host_id]
        require(vm['mipsPerPe'] <= host['mipsPerPe'], 'Final per-PE MIPS infeasible')
        demand = [vm['pes'], vm['pes'] * vm['mipsPerPe'], vm['ramMiB'], vm['bwMbps'], vm['storageMiB']]
        capacity = [host['pes'], host['pes'] * host['mipsPerPe'], host['ramMiB'], host['bwMbps'], host['storageMiB']]
        for dimension in range(5):
            used[host_id][dimension] += demand[dimension]
            require(used[host_id][dimension] <= capacity[dimension], 'Final placement infeasible')
        work[host_id] += demand[1] * duration
    horizon = max(references)
    watts = energy = 0.0
    for host, reserved, integral in zip(hosts, used, work):
        if reserved[1]:
            slope = host['maxW'] - host['idleW']
            total = host['pes'] * host['mipsPerPe']
            watts += host['idleW'] + slope * (reserved[1] / total)
            energy += host['idleW'] * horizon + slope / total * integral
    return watts, energy, horizon


def identity(row, expected):
    require([row[key] for key in ID.split(',')] == expected, 'Wrong/duplicate/reordered case identity')


def effective_bytes(resolved, config_sha256):
    """SORTED key=value lines re-derived from the resolution, not read from the artifact."""
    values = scenario_runner.effective(resolved, config_sha256)
    return ''.join(f'{key}={values[key]}\n' for key in sorted(values)).encode()


def validate(directory):
    """Raise ValueError unless the exact complete matrix and durable evidence validate."""
    try:
        _validate(Path(directory))
    except (OSError, KeyError, TypeError, OverflowError, UnicodeError, json.JSONDecodeError) as error:
        raise ValueError(f'Invalid or missing scenario evidence: {error}') from error


def _validate(directory):
    require((scenario_runner.PROTOCOL, scenario_runner.SCENARIO_SCHEMA_VERSION, scenario_runner.PHASE)
            == (PROTOCOL, SCENARIO_SCHEMA_VERSION, PHASE), 'Scenario protocol drifted from the configuration model')
    m = load(directory / 'run.json')
    require(m.get('experiment_kind') == 'static_scenario' and m.get('scenario_schema_version') == SCENARIO_SCHEMA_VERSION
            and not ({'schema_version', 'stress_schema_version'} & m.keys()), 'Requires isolated scenario schema 1')
    require(m.get('protocol') == PROTOCOL and m.get('profile') == PHASE, 'Wrong scenario protocol or profile')
    require(m['state'] == 'COMPLETE' and m['error'] is None, 'Requires COMPLETE successful scenario run')
    run_id = m['run_id']
    require(isinstance(run_id, str) and re.fullmatch(r'scenario-[A-Za-z0-9-]+', run_id), 'Invalid run identity')
    requested = (directory / 'config/requested.json').read_bytes()
    config_sha256 = hashlib.sha256(requested).hexdigest()
    require(m['config_sha256'] == config_sha256, 'Configuration digest does not match config/requested.json')
    resolved = scenario_runner.resolve_config(scenario_runner.load_config(directory / 'config/requested.json'))
    require(m['resolved_config'] == resolved, 'Recorded resolution differs from the requested configuration')
    require(load(directory / 'config/resolved.json') == resolved, 'config/resolved.json differs from the requested configuration')
    name, master, replications = resolved['name'], resolved['master_seed'], resolved['replications']
    algorithms = resolved['algorithms']
    require(m['config_name'] == name and type(m['master_seed']) is int and m['master_seed'] == master,
            'Recorded configuration identity differs from the resolved configuration')
    plan = scenario_runner.planned_work(resolved)
    require(m['effective_config'] == scenario_runner.effective(resolved, config_sha256), 'Wrong effective configuration')
    require((directory / 'effective.properties').read_bytes() == effective_bytes(resolved, config_sha256),
            'Altered effective.properties')
    require(type(plan['expected_cases']) is int and type(plan['expected_evaluations']) is int
            and plan['expected_cases'] > 0, 'Invalid planned work')
    counters = dict(expected_cases=plan['expected_cases'], attempted_cases=plan['expected_cases'],
                    successful_cases=plan['expected_cases'], failed_cases=0, unattempted_cases=0,
                    expected_evaluations=plan['expected_evaluations'], completed_evaluations=plan['expected_evaluations'])
    for key, value in counters.items():
        require(type(m[key]) is int and m[key] == value, f'Wrong {key}')
    require(m['expected_cases'] == replications * len(algorithms), 'Wrong expected case count')
    require(m['feasibility'] == scenario_runner.feasibilities(resolved), 'Recorded feasibility differs from the derived classification')
    require(m['evidence_limit'] == EVIDENCE_LIMIT and m['allocation_timing'] == ALLOCATION_TIMING
            and m['model_limits'] == MODEL_LIMITS, 'Missing evidence/timing/model limits')
    progress = load(directory / 'progress.json')
    progress_expected = {key: m[key] for key in ['experiment_kind', 'scenario_schema_version', 'run_id', 'state', 'error']
                         + list(counters)}
    progress_expected['current_case'] = None
    require(progress == progress_expected, 'Wrong complete progress state')
    require(datetime.fromisoformat(m['finished_at']) >= datetime.fromisoformat(m['started_at']), 'Invalid run times')
    require(m['cloudsim_version'] == '8.5.7' and type(m['git_dirty']) is bool and type(m['max_heap_bytes']) is int
            and m['max_heap_bytes'] > 0 and type(m.get('execution_workers')) is int and m['execution_workers'] >= 1,
            'Invalid provenance')
    for field in ['source_version', 'java_version', 'os', 'arch']:
        require(isinstance(m[field], str) and m[field], f'Missing provenance {field}')
    if m['artifact_sha256'] is None:
        require(m['source_version'] == 'development (unpackaged)' and m['git_revision'] == 'unknown' and m['git_dirty'],
                'Unlabeled unpackaged artifact')
    else:
        require(isinstance(m['artifact_sha256'], str) and re.fullmatch('[a-f0-9]{64}', m['artifact_sha256'])
                and isinstance(m['git_revision'], str) and re.fullmatch('[a-f0-9]{40}', m['git_revision']),
                'Invalid artifact provenance')
    files = {'config/requested.json', 'config/resolved.json', 'effective.properties', 'raw/cases.csv',
             'raw/placements.csv', 'raw/evaluations.csv', 'analysis/summary.csv'} | {f'scenarios/replication-{r}.json'
                                                                                    for r in range(replications)}
    require(isinstance(m['files'], dict) and set(m['files']) == files, 'Wrong required files')
    for file in files:
        require(m['files'][file] == digest(directory / file), f'Hash mismatch: {file}')
    summaries = {algorithm: [0, 0.0, 0.0, 0.0, 0.0] for algorithm in algorithms}
    case_rows = rows(directory / 'raw/cases.csv', CASE)
    placement_rows = rows(directory / 'raw/placements.csv', PLACE)
    trace_rows = rows(directory / 'raw/evaluations.csv', TRACE)
    try:
        for replication in range(replications):
            spec = scenario_runner.generated_spec(resolved, replication)
            require(load(directory / f'scenarios/replication-{replication}.json') == spec,
                    'Seed/dimension/canonical specification mismatch')
            require(spec['scenario_sha256'] == hashlib.sha256(spec['canonical_text'].encode('utf-8')).hexdigest(),
                    'Scenario fingerprint does not match its canonical text')
            classification = scenario_runner.feasibility(spec['inputs'], replication)
            require(classification['kind'] != 'INFEASIBLE_PROVED', 'Completed run records an infeasibility proof')
            if classification['kind'] == 'CONSTRUCTIBLE':
                # A rejected constructive witness must not be silently reclassified as a successful run.
                placement_metrics(spec, [index % len(spec['inputs']['hosts']) for index in range(len(spec['inputs']['vms']))])
            seeds = spec['inputs']['seeds']
            v, h = len(spec['inputs']['vms']), len(spec['inputs']['hosts'])
            for algorithm in algorithms:
                search = algorithm in scenario_runner.SEARCH_ALGORITHMS
                expected_id = ['1', run_id, PHASE, name, str(replication), algorithm,
                               str(resolved['optimizer']['population']) if search else '',
                               str(resolved['optimizer']['iterations']) if search else '']
                row = take(case_rows, 'case')
                identity(row, expected_id)
                require(row['status'] == 'RUN_OK' and row['error_code'] == row['error_message'] == '', 'Invalid case status')
                require(row['scenario_seed'] == str(seeds['scenarioSeed']) and row['workload_seed'] == str(seeds['workloadSeed'])
                        and row['optimizer_seed'] == (str(seed(master, PHASE, name, replication, 'optimizer', algorithm))
                                                      if search else '')
                        and row['scenario_sha256'] == spec['scenario_sha256'], 'Seed/spec/hash mismatch')
                for field, value in [('vm_count', v), ('host_count', h),
                                     ('evaluations', plan['evaluation_budget'] if search else 1), ('sla_violations', 0),
                                     ('completed_cloudlets', v), ('failed_cloudlets', 0), ('censored_cloudlets', 0)]:
                    require(integer(row[field], field) == value, f'Wrong {field}')
                final = []
                for vm in range(v):
                    placement = take(placement_rows, 'placement')
                    identity(placement, expected_id)
                    require(integer(placement['vm_id'], 'vm_id') == vm, 'Wrong/duplicate/reordered VM placement')
                    final.append(integer(placement['host_id'], 'host_id'))
                watts, energy, horizon = placement_metrics(spec, final)
                for field, value in [('objective_w', watts), ('energy_j', energy), ('energy_kwh', energy / 3600000),
                                     ('horizon_s', horizon), ('release_s', RELEASE_SECONDS), ('sla_rate', 0)]:
                    close(row[field], value, field)
                nanos = integer(row['allocation_wall_ns'], 'allocation_wall_ns')
                require(0 <= nanos < 1 << 63, 'Invalid allocation time')
                if search:
                    best = None
                    incumbents = [None] * resolved['optimizer']['population']
                    for evaluation, (iteration, stage, member) in enumerate(
                            trace_order(algorithm, resolved['optimizer']['population'], resolved['optimizer']['iterations']), 1):
                        trace = take(trace_rows, 'evaluation')
                        identity(trace, expected_id)
                        require(stage in PHASES and (integer(trace['evaluation'], 'evaluation'),
                                                     integer(trace['iteration'], 'iteration'), trace['stage'],
                                                     integer(trace['candidate_index'], 'candidate_index'))
                                == (evaluation, iteration, stage, member), 'Wrong scalar evaluation sequence/budget')
                        require(trace['feasible'] in ('true', 'false') and trace['accepted'] in ('true', 'false'),
                                'Invalid scalar flags')
                        fitness = finite(trace['fitness_w']) if trace['fitness_w'] else None
                        require((fitness is not None) == (trace['feasible'] == 'true') and (fitness is None or fitness > 0),
                                'Invalid scalar fitness/feasibility')
                        accepted = trace['accepted'] == 'true'
                        if stage in ('INITIAL', 'CHILD'):
                            require(accepted, 'Population insertion must be accepted')
                        elif stage == 'PREDATOR':
                            require(not accepted, 'Predator cannot be eligible')
                        else:
                            require(accepted == (fitness is not None
                                                 and (incumbents[member] is None or fitness < incumbents[member])),
                                    'Wrong slot acceptance')
                        if stage == 'INITIAL' or accepted and algorithm == 'HO':
                            incumbents[member] = fitness
                        if accepted and fitness is not None and (best is None or fitness < best):
                            best = fitness
                        if best is None:
                            require(trace['best_fitness_w'] == '', 'Unexpected eligible best')
                        else:
                            close(trace['best_fitness_w'], best, 'eligible-best chain')
                    require(best is not None, 'Missing feasible eligible best')
                    close(row['objective_w'], best, 'Final eligible trace best')
                totals = summaries[algorithm]
                totals[0] += 1
                totals[1] += watts
                totals[2] += energy
                totals[4] += nanos
        for iterator, label in [(case_rows, 'cases'), (placement_rows, 'placements'), (trace_rows, 'evaluations')]:
            require(next(iterator, None) is None, f'Extra/duplicate {label}')
    finally:
        for iterator in [case_rows, placement_rows, trace_rows]:
            iterator.close()
    summary_rows = rows(directory / 'analysis/summary.csv', SUMMARY)
    try:
        for algorithm in algorithms:
            row = take(summary_rows, 'summary')
            totals = summaries[algorithm]
            require([row[key] for key in ['scenario_schema_version', 'run_id', 'phase', 'scenario', 'algorithm', 'n']]
                    == ['1', run_id, PHASE, name, algorithm, str(replications)], 'Wrong summary identity/count/order')
            for field, total in zip(['objective_mean_w', 'energy_mean_j', 'sla_mean', 'allocation_mean_ns'], totals[1:]):
                close(row[field], total / replications, field)
        require(next(summary_rows, None) is None, 'Extra summary')
    finally:
        summary_rows.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('run_directory', type=Path)
    args = parser.parse_args()
    try:
        validate(args.run_directory)
    except ValueError as error:
        print(f'INVALID: {error}', file=sys.stderr)
        return 1
    print('VALID static_scenario scenario_schema_version=1; ' + EVIDENCE_LIMIT)
    return 0


if __name__ == '__main__':
    sys.exit(main())
