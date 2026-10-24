"""Scenario validator contracts: a synthesized complete run validates; every tamper is caught."""
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
CASE=ID+',scenario_seed,workload_seed,optimizer_seed,scenario_sha256,status,error_code,error_message,vm_count,host_count,evaluations,objective_w,energy_j,energy_kwh,sla_violations,sla_rate,completed_cloudlets,failed_cloudlets,censored_cloudlets,horizon_s,release_s,allocation_wall_ns'
PLACE=ID+',vm_id,host_id'
TRACE=ID+',evaluation,iteration,stage,candidate_index,feasible,fitness_w,accepted,best_fitness_w'
SUMMARY='scenario_schema_version,run_id,phase,scenario,algorithm,n,objective_mean_w,energy_mean_j,sla_mean,allocation_mean_ns'


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
        hosts=spec['inputs']['hosts']; vms=spec['inputs']['vms']; seconds=spec['reference_seconds']
        placements=[0,1]
        watts=hosts[0]['idleW']+(hosts[0]['maxW']-hosts[0]['idleW'])*(vms[0]['pes']*vms[0]['mipsPerPe'])/(hosts[0]['pes']*hosts[0]['mipsPerPe'])
        watts+=hosts[1]['idleW']+(hosts[1]['maxW']-hosts[1]['idleW'])*(vms[1]['pes']*vms[1]['mipsPerPe'])/(hosts[1]['pes']*hosts[1]['mipsPerPe'])
        horizon=max(seconds)
        energy=0.0
        for host,vm,duration in zip(hosts,vms,seconds):
            energy+=host['idleW']*horizon+(host['maxW']-host['idleW'])/(host['pes']*host['mipsPerPe'])*(vm['pes']*vm['mipsPerPe'])*duration

        seeds=spec['inputs']['seeds']
        optimizer=resolved['optimizer']
        cases=[]; places=[]; traces=[]; summaries=[]
        for index,algorithm in enumerate(resolved['algorithms']):
            search=algorithm in runner.SEARCH_ALGORITHMS
            identity=[1,'scenario-fixture','scenario',resolved['name'],0,algorithm,
                      optimizer['population'] if search else '', optimizer['iterations'] if search else '']
            cases.append(identity+[str(seeds['scenarioSeed']),str(seeds['workloadSeed']),
                        str(seed(resolved['master_seed'],'scenario',resolved['name'],0,'optimizer',algorithm)) if search else '', spec['scenario_sha256'],
                        'RUN_OK','','',len(vms),len(hosts),
                        runner.planned_work(resolved)['evaluation_budget'] if search else 1,
                        watts,energy,energy/3600000,0,0,len(vms),0,0,horizon,.125,1000])
            places.append(identity+[0,placements[0]])
            places.append(identity+[1,placements[1]])
            if search:
                for evaluation,(iteration,stage,member) in enumerate(
                        validator.trace_order(algorithm,optimizer['population'],optimizer['iterations']),1):
                    traces.append(identity+[evaluation,iteration,stage,member,'true',watts,
                                            'true' if stage in ('INITIAL','CHILD') else 'false',watts])
            summaries.append([1,'scenario-fixture','scenario',resolved['name'],algorithm,1,watts,energy,0,1000])
        self.write_csv('raw/cases.csv',CASE,cases)
        self.write_csv('raw/placements.csv',PLACE,places)
        self.write_csv('raw/evaluations.csv',TRACE,traces)
        self.write_csv('analysis/summary.csv',SUMMARY,summaries)

        plan=runner.planned_work(resolved)
        counters=dict(expected_cases=plan['expected_cases'],attempted_cases=plan['expected_cases'],
                     successful_cases=plan['expected_cases'],failed_cases=0,unattempted_cases=0,
                     expected_evaluations=plan['expected_evaluations'],completed_evaluations=plan['expected_evaluations'])
        manifest=dict(experiment_kind='static_scenario',scenario_schema_version=1,protocol=runner.PROTOCOL,
                      profile='scenario',run_id='scenario-fixture',config_name=resolved['name'],config_sha256=digest,
                      state='COMPLETE',started_at='2026-10-01T00:00:00Z',finished_at='2026-10-01T00:00:01Z',
                      master_seed=resolved['master_seed'],resolved_config=resolved,effective_config=effective,
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
        path.write_text(path.read_text().replace('400.0','401.0'))
        with self.assertRaises(ValueError): validator.validate(self.directory)

    def test_tampered_resolved_and_requested_documents_are_rejected(self):
        self.mutate_json('config/resolved.json',lambda data:data['vms'].__setitem__('count',3))
        with self.assertRaises(ValueError): validator.validate(self.directory)
        self.build()
        self.mutate_json('config/requested.json',lambda data:data.__setitem__('replications',2))
        with self.assertRaises(ValueError): validator.validate(self.directory)

    def test_altered_feasibility_and_effective_map_are_rejected(self):
        self.mutate_json('run.json',lambda data:data['feasibility'][0].__setitem__('kind','UNPROVED'))
        with self.assertRaises(ValueError): validator.validate(self.directory)
        self.build()
        text=(self.directory/'effective.properties').read_text()
        (self.directory/'effective.properties').write_text(text.replace('vm.count=2','vm.count=3'))
        with self.assertRaises(ValueError): validator.validate(self.directory)

    def test_mixed_schema_keys_are_rejected(self):
        self.mutate_json('run.json',lambda data:data.__setitem__('stress_schema_version',1))
        with self.assertRaises(ValueError): validator.validate(self.directory)
        self.build()
        self.mutate_json('run.json',lambda data:data.__setitem__('schema_version',2))
        with self.assertRaises(ValueError): validator.validate(self.directory)

    def test_rehashed_objective_energy_and_placement_tampers_are_rejected(self):
        for column,value in [('objective_w','401.0'),('energy_j','24001.0'),('energy_kwh','0.01'),
                             ('horizon_s','61.0'),('release_s','0'),('sla_rate','1'),('completed_cloudlets','1'),
                             ('evaluations','131'),('scenario_seed','1'),('scenario_sha256','0'*64)]:
            self.mutate_csv('raw/cases.csv',lambda rows:rows[1].__setitem__(rows[0].index(column),value))
            with self.subTest(column=column),self.assertRaises(ValueError): validator.validate(self.directory)
        self.build()
        self.mutate_csv('raw/cases.csv',lambda rows:rows[1].__setitem__(rows[0].index('objective_w'),'400.5'))
        with self.assertRaises(ValueError): validator.validate(self.directory)

    def test_rehashed_trace_order_and_summary_mean_are_rejected(self):
        self.mutate_csv('raw/evaluations.csv',lambda rows:rows.__setitem__(slice(1,3),rows[1:3][::-1]))
        with self.assertRaises(ValueError): validator.validate(self.directory)
        self.build()
        self.mutate_csv('analysis/summary.csv',lambda rows:rows[1].__setitem__(rows[0].index('objective_mean_w'),'401.0'))
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
