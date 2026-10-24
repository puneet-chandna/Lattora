"""Scenario schema-2 validator contracts: a synthesized complete run validates; every tamper is caught."""
import csv
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

import scenario_runner as runner
import scenario_validator as validator
from statistics_validator import seed

ID='scenario_schema_version,run_id,phase,scenario,replication,algorithm,population,iterations'
CASE=ID+',scenario_seed,workload_seed,optimizer_seed,scenario_sha256,status,error_code,error_message,vm_count,host_count,evaluations,objective_kind,objective_version,objective_score,predicted_power_w,predicted_reserved_peak_utilization,normalized_power,normalized_utilization,observed_utilization,observed_energy_j,observed_energy_kwh,observed_sla_violations,observed_sla_rate,observed_completed_cloudlets,observed_failed_cloudlets,observed_censored_cloudlets,observed_horizon_s,release_s,simulations,search_simulations,allocation_wall_ns'
PLACE=ID+',vm_id,host_id'
TRACE=ID+',evaluation,iteration,stage,candidate_index,feasible,objective_score,predicted_power_w,predicted_reserved_peak_utilization,normalized_power,normalized_utilization,accepted,best_objective_score,best_power_w'
SUMMARY='scenario_schema_version,run_id,phase,scenario,algorithm,n,objective_kind,objective_version,objective_mean,predicted_power_mean_w,predicted_reserved_peak_utilization_mean,normalized_power_mean,normalized_utilization_mean,observed_utilization_mean,observed_energy_mean_j,observed_sla_mean,allocation_mean_ns'


class ScenarioValidatorTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.directory=Path(self.temp.name)/'scenario-fixture'
        self.build()

    def write_csv(self,path,header,rows):
        file=self.directory/path; file.parent.mkdir(parents=True,exist_ok=True)
        with file.open('w',newline='') as stream:
            writer=csv.writer(stream,lineterminator='\n'); writer.writerow(header.split(',')); writer.writerows(rows)

    def rehash(self):
        manifest=json.loads((self.directory/'run.json').read_text())
        manifest['files']={str(path.relative_to(self.directory)):hashlib.sha256(path.read_bytes()).hexdigest()
                            for path in sorted(self.directory.rglob('*'))
                            if path.is_file() and path.name not in ('run.json','progress.json')}
        (self.directory/'run.json').write_text(json.dumps(manifest)+'\n')

    def build(self):
        """A complete single-replication known-answer-tight campaign derived only from the configuration model."""
        resolved=runner.resolve_config(runner.request_json(runner.PRESETS['known-answer-tight']))
        self.resolved=resolved
        request=json.dumps(runner.PRESETS['known-answer-tight'],indent=2).encode()
        digest=hashlib.sha256(request).hexdigest()
        self.directory.mkdir(parents=True,exist_ok=True)
        (self.directory/'config').mkdir(exist_ok=True)
        (self.directory/'config/requested.json').write_bytes(request)
        (self.directory/'config/resolved.json').write_text(json.dumps(resolved)+'\n')
        effective=runner.effective(resolved,digest)
        (self.directory/'effective.properties').write_text(''.join(f'{key}={effective[key]}\n' for key in sorted(effective)))
        for name in ('raw','scenarios','analysis'):
            (self.directory/name).mkdir(exist_ok=True)

        spec=runner.generated_spec(resolved,0)
        (self.directory/'scenarios/replication-0.json').write_text(json.dumps(spec)+'\n')
        hosts=spec['inputs']['hosts']; vms=spec['inputs']['vms']
        placements=[0,1]
        metrics=validator.placement_metrics(spec,placements,resolved['objective'])

        seeds=spec['inputs']['seeds']
        optimizer=resolved['optimizer']
        objective=resolved['objective']
        cases=[]; places=[]; traces=[]; summaries=[]
        for algorithm in resolved['algorithms']:
            search=algorithm in runner.SEARCH_ALGORITHMS
            identity=[2,'scenario-fixture','scenario',resolved['name'],0,algorithm,
                      optimizer['population'] if search else '', optimizer['iterations'] if search else '']
            cases.append(identity+[str(seeds['scenarioSeed']),str(seeds['workloadSeed']),
                        str(seed(resolved['master_seed'],'scenario',resolved['name'],0,'optimizer',algorithm)) if search else '', spec['scenario_sha256'],
                        'RUN_OK','','',len(vms),len(hosts),
                        runner.planned_work(resolved)['evaluation_budget'] if search else 1,
                        objective['kind'],1,metrics['objective'],metrics['power'],metrics['reserved_peak_utilization'],
                        metrics['normalized_power'],metrics['normalized_utilization'],metrics['observed_utilization'],
                        metrics['energy'],metrics['energy']/3600000,0,0,len(vms),0,0,metrics['horizon'],.125,1,0,1000])
            places.append(identity+[0,placements[0]])
            places.append(identity+[1,placements[1]])
            if search:
                for evaluation,(iteration,stage,member) in enumerate(
                        validator.trace_order(algorithm,optimizer['population'],optimizer['iterations']),1):
                    traces.append(identity+[evaluation,iteration,stage,member,'true',metrics['objective'],
                                            metrics['power'],metrics['reserved_peak_utilization'],
                                            metrics['normalized_power'],metrics['normalized_utilization'],
                                            'true' if stage in ('INITIAL','CHILD') else 'false',
                                            metrics['objective'],metrics['power']])
            summaries.append([2,'scenario-fixture','scenario',resolved['name'],algorithm,1,objective['kind'],1,
                              metrics['objective'],metrics['power'],metrics['reserved_peak_utilization'],
                              metrics['normalized_power'],metrics['normalized_utilization'],
                              metrics['observed_utilization'],metrics['energy'],0,1000])
        self.write_csv('raw/cases.csv',CASE,cases)
        self.write_csv('raw/placements.csv',PLACE,places)
        self.write_csv('raw/evaluations.csv',TRACE,traces)
        self.write_csv('analysis/summary.csv',SUMMARY,summaries)

        plan=runner.planned_work(resolved)
        counters=dict(expected_cases=plan['expected_cases'],attempted_cases=plan['expected_cases'],
                     successful_cases=plan['expected_cases'],failed_cases=0,unattempted_cases=0,
                     expected_evaluations=plan['expected_evaluations'],completed_evaluations=plan['expected_evaluations'],
                     expected_simulations=plan['expected_cases'],completed_simulations=plan['expected_cases'],
                     search_simulations=0)
        manifest=dict(experiment_kind='static_scenario',scenario_schema_version=2,protocol=runner.PROTOCOL,
                      profile='scenario',run_id='scenario-fixture',config_name=resolved['name'],config_sha256=digest,
                      objective=objective,state='COMPLETE',started_at='2026-10-01T00:00:00Z',
                      finished_at='2026-10-01T00:00:01Z',master_seed=resolved['master_seed'],
                      resolved_config=resolved,effective_config=effective,
                      feasibility=runner.feasibilities(resolved),error=None,source_version='development (unpackaged)',
                      artifact_sha256=None,git_revision='unknown',git_dirty=True,java_version='21',cloudsim_version='8.5.7',
                      os='Linux',arch='amd64',max_heap_bytes=33554432,execution_workers=1,
                      evidence_limit=validator.EVIDENCE_LIMIT,allocation_timing=validator.ALLOCATION_TIMING,
                      model_limits=validator.MODEL_LIMITS,**counters)
        (self.directory/'run.json').write_text(json.dumps(manifest)+'\n')
        progress={key:manifest[key] for key in ['experiment_kind','scenario_schema_version','run_id','state','error']+list(counters)}
        progress['current_case']=None
        (self.directory/'progress.json').write_text(json.dumps(progress)+'\n')
        self.rehash()

    def mutate_csv(self,path,mutator):
        file=self.directory/path
        with file.open(newline='') as stream: rows=list(csv.reader(stream))
        mutator(rows)
        with file.open('w',newline='') as stream: csv.writer(stream,lineterminator='\n').writerows(rows)
        self.rehash()

    def mutate_json(self,path,mutator):
        file=self.directory/path
        data=json.loads(file.read_text()); mutator(data)
        file.write_text(json.dumps(data)+'\n'); self.rehash()

    def test_synthesized_fixture_validates(self):
        validator.validate(self.directory)

    def test_tampered_file_hash_is_rejected(self):
        path=self.directory/'raw/cases.csv'
        path.write_text(path.read_text().replace(',400.0,',',401.0,'))
        with self.assertRaises(ValueError): validator.validate(self.directory)

    def test_tampered_resolved_and_requested_documents_are_rejected(self):
        self.mutate_json('config/resolved.json',lambda data:data['vms'].__setitem__('count',3))
        with self.assertRaises(ValueError): validator.validate(self.directory)
        self.build()
        self.mutate_json('config/requested.json',lambda data:data.__setitem__('replications',2))
        with self.assertRaises(ValueError): validator.validate(self.directory)

    def test_altered_feasibility_effective_map_and_objective_are_rejected(self):
        self.mutate_json('run.json',lambda data:data['feasibility'][0].__setitem__('kind','UNPROVED'))
        with self.assertRaises(ValueError): validator.validate(self.directory)
        self.build()
        text=(self.directory/'effective.properties').read_text()
        (self.directory/'effective.properties').write_text(text.replace('vm.count=2','vm.count=3'))
        with self.assertRaises(ValueError): validator.validate(self.directory)
        self.build()
        self.mutate_json('run.json',lambda data:data['objective'].__setitem__('kind','utilization'))
        with self.assertRaises(ValueError): validator.validate(self.directory)

    def test_mixed_schema_keys_are_rejected(self):
        self.mutate_json('run.json',lambda data:data.__setitem__('stress_schema_version',1))
        with self.assertRaises(ValueError): validator.validate(self.directory)
        self.build()
        self.mutate_json('run.json',lambda data:data.__setitem__('schema_version',2))
        with self.assertRaises(ValueError): validator.validate(self.directory)

    def test_rehashed_predicted_and_observed_tampers_are_rejected(self):
        for column,value in [('objective_score','400.0'),('predicted_power_w','401.0'),
                             ('predicted_reserved_peak_utilization','0.5'),('normalized_power','0.5'),
                             ('normalized_utilization','0.5'),('observed_utilization','0.5'),
                             ('observed_energy_j','24001.0'),('observed_energy_kwh','0.01'),
                             ('observed_horizon_s','61.0'),('release_s','0'),('observed_sla_rate','1'),
                             ('observed_completed_cloudlets','1'),('evaluations','131'),('scenario_seed','1'),
                             ('scenario_sha256','0'*64),('simulations','0')]:
            self.mutate_csv('raw/cases.csv',lambda rows:rows[1].__setitem__(rows[0].index(column),value))
            with self.subTest(column=column),self.assertRaises(ValueError): validator.validate(self.directory)
        self.build()
        self.mutate_csv('raw/cases.csv',lambda rows:rows[1].__setitem__(rows[0].index('objective_score'),'0.5'))
        with self.assertRaises(ValueError): validator.validate(self.directory)
        self.build()
        self.mutate_csv('raw/cases.csv',lambda rows:rows[1].__setitem__(rows[0].index('objective_kind'),'utilization'))
        with self.assertRaises(ValueError): validator.validate(self.directory)

    def test_rehashed_trace_components_order_and_summary_mean_are_rejected(self):
        self.mutate_csv('raw/evaluations.csv',lambda rows:rows[1].__setitem__(rows[0].index('normalized_power'),'0.5'))
        with self.assertRaises(ValueError): validator.validate(self.directory)
        self.build()
        self.mutate_csv('raw/evaluations.csv',lambda rows:rows[1].__setitem__(rows[0].index('best_power_w'),'401.0'))
        with self.assertRaises(ValueError): validator.validate(self.directory)
        self.build()
        self.mutate_csv('raw/evaluations.csv',lambda rows:rows.__setitem__(slice(1,3),rows[1:3][::-1]))
        with self.assertRaises(ValueError): validator.validate(self.directory)
        self.build()
        self.mutate_csv('analysis/summary.csv',lambda rows:rows[1].__setitem__(rows[0].index('objective_mean'),'0.5'))
        with self.assertRaises(ValueError): validator.validate(self.directory)

    def test_unlisted_evidence_files_are_rejected(self):
        (self.directory/'notes.txt').write_text('stray payload')
        with self.assertRaisesRegex(ValueError, 'Unlisted evidence files'):
            validator.validate(self.directory)

    def test_rehashed_placement_tampers_are_rejected(self):
        # Both tight VMs saturate their own host, so any merge is infeasible.
        self.mutate_csv('raw/placements.csv',lambda rows:rows[2].__setitem__(-1,'0'))
        with self.assertRaises(ValueError): validator.validate(self.directory)
        self.build()
        self.mutate_csv('raw/placements.csv',lambda rows:rows[1].__setitem__(-1,'2'))
        with self.assertRaises(ValueError): validator.validate(self.directory)

    def test_missing_file_missing_spec_and_counters_are_rejected(self):
        (self.directory/'raw/placements.csv').unlink()
        with self.assertRaises(ValueError): validator.validate(self.directory)
        self.build()
        (self.directory/'scenarios/replication-0.json').unlink()
        with self.assertRaises(ValueError): validator.validate(self.directory)
        self.build()
        self.mutate_json('run.json',lambda data:data.__setitem__('completed_evaluations',data['completed_evaluations']+1))
        with self.assertRaises(ValueError): validator.validate(self.directory)


if __name__=='__main__':
    unittest.main()
