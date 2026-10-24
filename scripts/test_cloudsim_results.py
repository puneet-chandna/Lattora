"""Completion summaries expose artifact-backed facts without inferential claims."""
import csv
import hashlib
import importlib
import json
from pathlib import Path
import tempfile
import unittest


class ResultSummaryTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name); self.run=self.root/'production/artifacts/run'
        self.run.mkdir(parents=True); (self.run/'raw').mkdir(); (self.run/'analysis').mkdir()
        self.manifest={'experiment_kind':'static_stress','stress_schema_version':1,'profile':'stress',
                       'run_id':'run','state':'COMPLETE','error':None,'expected_cases':4,
                       'successful_cases':4,'failed_cases':0,'completed_evaluations':262,
                       'expected_evaluations':262,'artifact_sha256':'0'*64,
                       'effective_config':{'vm.count':'100','host.count':'20','population':'10',
                                           'iterations':'4','replications':'1','master.seed':'123456'},'files':{}}
        self.rows=[]
        for algorithm,energy in [('HO',100.),('GA',120.),('FirstFit',130.),('BestFit',130.)]:
            self.rows.append({'run_id':'run','phase':'stress','scenario':'Static-V100-H20','algorithm':algorithm,
                              'replication':'0','status':'RUN_OK','energy_j':energy,'sla_rate':0,
                              'objective_w':10,'allocation_wall_ns':2000000,'completed_cloudlets':100,
                              'failed_cloudlets':0,'censored_cloudlets':0,'evaluations':130 if algorithm in ('HO','GA') else 1})
        self.write_csv('raw/cases.csv',self.rows)
        self.means=[{'run_id':'run','phase':'stress','scenario':'Static-V100-H20','algorithm':r['algorithm'],
                     'n':1,'objective_mean_w':10,'energy_mean_j':r['energy_j'],'sla_mean':0,'allocation_mean_ns':2000000} for r in self.rows]
        self.write_csv('analysis/summary.csv',self.means)
        self.write_manifest()
        self.meta={'profile':'stress','status':'complete','exit_code':0,'validation':'PASS','tests':'PASS',
                   'output_directory':str(self.root),'run_directory':str(self.run),'artifact_sha256':'0'*64,
                   'effective_config':{'vms':100,'hosts':20,'population':10,'iterations':4,'replications':1,'seed':123456,'heap_mib':1024},
                   'started_at':'2026-10-04T01:00:00+00:00','finished_at':'2026-10-04T01:00:12+00:00',
                   'execution':{'workers':2,'shared_heap_mib':1024},'build':'REUSED_VERIFIED',
                   'build_receipt':{'tested_at':'2026-10-04T00:00:00+00:00'},
                   'production':{'wall_seconds':8,'sampled_peak_rss_bytes':300*1024**2},'calibration':[{}]}

    def write_csv(self,name,rows):
        file=self.run/name
        with file.open('w',newline='') as stream:
            writer=csv.DictWriter(stream,fieldnames=list(rows[0]),lineterminator='\n')
            writer.writeheader();writer.writerows(rows)
        self.manifest['files'][name]=hashlib.sha256(file.read_bytes()).hexdigest()

    def write_manifest(self): (self.run/'run.json').write_text(json.dumps(self.manifest))

    def summary(self,metadata=None):
        try: module=importlib.import_module('cloudsim_results')
        except ModuleNotFoundError: module=None
        self.assertIsNotNone(module,'Artifact-backed completion summary is missing')
        return module.build_result_summary(self.meta if metadata is None else metadata)

    def test_stress_summary_uses_observed_means_and_explicit_analysis_scope(self):
        result=self.summary();self.assertIsNone(result['error'])
        self.assertEqual([r['algorithm'] for r in result['algorithms']],['HO','GA','FirstFit','BestFit'])
        self.assertEqual(result['algorithms'][0]['energy_j'],100.)
        self.assertEqual(result['algorithms'][0]['runtime_ms'],2.)
        self.assertIn('262',str(result['overview']))
        self.assertIn(('Master seed','123456'),result['overview'])
        check=next(c for c in result['checks'] if 'Hypothesis' in c['label'])
        self.assertEqual(check['status'],'NOT_APPLICABLE')
        self.assertIn('descriptive',' '.join(result['notes']).lower())
        self.assertIn('reused',str(result['checks']).lower())

    def scenario(self):
        """A minimal complete scenario campaign mirroring the stress fixture shape."""
        self.manifest.update(experiment_kind='static_scenario',profile='scenario')
        self.manifest.pop('stress_schema_version')
        self.manifest.update(scenario_schema_version=2,protocol='static_scenario_v2',
                             objective={'kind':'power','version':1})
        self.manifest['effective_config']={'vm.count':'2','host.count':'2','host.classes':'1',
                                            'replication.count':'1','algorithms':'HO,GA,FirstFit,BestFit',
                                            'master.seed':'123456','objective.kind':'power','objective.version':'1'}
        self.manifest.update(expected_cases=4,successful_cases=4,completed_evaluations=262,expected_evaluations=262)
        for row in self.rows:
            row['phase']='scenario';row['predicted_power_w']=row.pop('objective_w')
            row['objective_kind']='power';row['objective_version']=1
        self.write_csv('raw/cases.csv',self.rows)
        for mean in self.means:
            mean['phase']='scenario';mean['predicted_power_mean_w']=mean.pop('objective_mean_w')
        self.write_csv('analysis/summary.csv',self.means)
        self.write_manifest()
        self.meta['profile']='scenario'

    def test_scenario_summary_reports_host_classes_and_objective_means(self):
        self.scenario();result=self.summary()
        self.assertIsNone(result['error'])
        self.assertEqual(len(result['algorithms']),4)
        self.assertEqual(result['algorithms'][0]['objective_w'],10.)
        self.assertIn(('Experiment','2 VMs / 2 hosts · R1 · 1 host class · HO,GA,FirstFit,BestFit'),result['overview'])
        self.assertIn('262 evaluations',str(result['overview']))
        check=next(c for c in result['checks'] if 'Hypothesis' in c['label'])
        self.assertEqual(check['status'],'NOT_APPLICABLE')

    def test_scenario_with_stress_schema_keys_is_rejected(self):
        self.scenario();self.manifest['stress_schema_version']=1;self.write_manifest()
        self.assertTrue(self.summary()['error'])

    def test_changed_summary_hides_means_and_reports_unavailable(self):
        file=self.run/'analysis/summary.csv';file.write_text(file.read_text().replace('100.0','90.0'))
        result=self.summary();self.assertTrue(result['error']);self.assertEqual(result['algorithms'],[])

    def test_coherent_altered_summary_is_recomputed_from_case_rows(self):
        self.means[0]['energy_mean_j']=90.;self.write_csv('analysis/summary.csv',self.means);self.write_manifest()
        result=self.summary();self.assertTrue(result['error']);self.assertEqual(result['algorithms'],[])

    def test_case_counter_mismatch_cannot_be_presented_as_complete(self):
        self.manifest['successful_cases']=3;self.write_manifest()
        result=self.summary();self.assertTrue(result['error']);self.assertEqual(result['algorithms'],[])

    def test_outside_evidence_path_is_rejected(self):
        with tempfile.TemporaryDirectory() as outside:
            self.meta['run_directory']=outside
            self.assertTrue(self.summary()['error'])

    def test_nonfinite_metric_is_rejected_even_with_matching_hash(self):
        self.rows[0]['energy_j']='NaN';self.write_csv('raw/cases.csv',self.rows);self.write_manifest()
        self.assertTrue(self.summary()['error'])

    def test_failure_and_setup_do_not_claim_available_scientific_analysis(self):
        for metadata in [{'status':'failed','exit_code':1,'profile':'stress','validation':'NOT_RUN'},
                         {'status':'checked','exit_code':0,'profile':'research','action':'check'}]:
            with self.subTest(metadata=metadata):
                result=self.summary(metadata);self.assertEqual(result['algorithms'],[])
                self.assertFalse(any(c['status']=='PASS' for c in result['checks']))

    def test_symlink_to_external_summary_is_rejected(self):
        with tempfile.TemporaryDirectory() as outside:
            file=self.run/'analysis/summary.csv';target=Path(outside)/'summary.csv'
            target.write_bytes(file.read_bytes());file.unlink();file.symlink_to(target)
            self.assertTrue(self.summary()['error'])

    def test_validation_report_with_profile_is_not_an_experiment(self):
        self.meta['action']='validate'
        result=self.summary();self.assertEqual(result['algorithms'],[]);self.assertEqual(result['overview'],[])

    def test_boolean_counter_and_duplicate_manifest_keys_are_rejected(self):
        self.manifest['failed_cases']=False;self.write_manifest()
        self.assertTrue(self.summary()['error'])
        self.manifest['failed_cases']=0
        text=json.dumps(self.manifest)
        (self.run/'run.json').write_text('{"failed_cases":0,'+text[1:])
        self.assertTrue(self.summary()['error'])

    def frozen(self,profile):
        self.meta['profile']=profile
        self.manifest.pop('stress_schema_version');self.manifest.pop('experiment_kind')
        self.manifest.update(schema_version=2,profile=profile)
        self.manifest['effective_config']['scenarios']='Micro,Small,Medium' if profile=='research' else 'Small,Medium' if profile=='explore' else 'Micro'
        scenarios=self.manifest['effective_config']['scenarios'].split(',')
        reps=30 if profile=='research' else 5 if profile=='explore' else 1
        main=[{**r,'phase':'main','scenario':scenario,'replication':str(rep)} for scenario in scenarios for rep in range(reps) for r in self.rows]
        self.write_csv('raw/main_results.csv',main)
        if profile=='research':
            sensitivity=[{**self.rows[0],'phase':'sensitivity','scenario':'Small','replication':str(rep)} for rep in range(90)]
            self.write_csv('raw/sensitivity_results.csv',sensitivity)
        expected=len(main)+(90 if profile=='research' else 0)
        self.manifest.update(expected_cases=expected,successful_cases=expected)
        means=[{**m,'scenario':scenario,'n':reps,'energy_sd_j':0,'sla_sd':0,'allocation_sd_ns':0} for scenario in scenarios for m in self.means]
        if profile!='smoke': self.write_csv('analysis/scenario_summary.csv',means)
        if profile=='research':
            decisions=[{'run_id':'run','scenario':scenario,'baseline':baseline,'claim':claim,'n':30,
                        'eligible':'false','p_holm':1,'decision':'NO_CLAIM'}
                       for scenario in scenarios for baseline in ('GA','FirstFit','BestFit') for claim in ('energy_benefit','sla_benefit')]
            self.write_csv('analysis/pairwise_primary.csv',decisions)
            self.meta.update(claims=0,no_claim=18)
        self.write_manifest()

    def test_smoke_shows_raw_means_without_inventing_saved_analysis(self):
        self.frozen('smoke');result=self.summary()
        self.assertIsNone(result['error']);self.assertEqual(len(result['algorithms']),4)
        self.assertEqual(result['algorithms'][0]['energy_j'],100.)
        self.assertFalse(any(c['label']=='Descriptive analysis' for c in result['checks']))

    def test_explore_keeps_scenarios_distinct_and_has_no_hypothesis_tests(self):
        self.frozen('explore');result=self.summary()
        self.assertIsNone(result['error']);self.assertEqual(len(result['algorithms']),8)
        self.assertEqual({r['scenario'] for r in result['algorithms']},{'Small','Medium'})
        self.assertEqual(next(c['status'] for c in result['checks'] if c['label']=='Hypothesis tests'),'NOT_APPLICABLE')

    def test_research_reads_hashed_decisions_and_keeps_sensitivity_out_of_main_means(self):
        self.frozen('research');result=self.summary()
        self.assertIsNone(result['error']);self.assertEqual(len(result['algorithms']),12)
        self.assertTrue(all(r['cases']==30 for r in result['algorithms']))
        self.assertIn('450/450',str(result['overview']))
        check=next(c for c in result['checks'] if c['label']=='Hypothesis tests')
        self.assertEqual(check['status'],'RECORDED_PASS');self.assertIn('0 CLAIM / 18 NO_CLAIM',check['detail'])

    def test_research_missing_decisions_hides_inference_and_numeric_summary(self):
        self.frozen('research');(self.run/'analysis/pairwise_primary.csv').unlink()
        result=self.summary();self.assertTrue(result['error']);self.assertEqual(result['algorithms'],[])
        self.assertFalse(any(c['label']=='Hypothesis tests' for c in result['checks']))

    def test_frozen_sample_standard_deviations_are_checked(self):
        self.frozen('explore')
        path=self.run/'analysis/scenario_summary.csv'
        with path.open() as stream: rows=list(csv.DictReader(stream))
        rows[0]['energy_sd_j']='NaN';self.write_csv('analysis/scenario_summary.csv',rows);self.write_manifest()
        self.assertTrue(self.summary()['error'])

    def test_null_optional_metadata_reports_unavailable_instead_of_crashing(self):
        for field in ('build_receipt','production','execution'):
            with self.subTest(field=field):
                result=self.summary({**self.meta,field:None})
                self.assertTrue(result['error']);self.assertEqual(result['algorithms'],[])

    def test_research_manifest_matches_actual_embedded_scenario_size(self):
        self.frozen('research');self.manifest['embedded_scenario_fixture']='x'*(3*1024**2);self.write_manifest()
        self.assertIsNone(self.summary()['error'])

    def test_research_duplicate_decisions_cannot_be_presented_as_claims(self):
        self.frozen('research')
        with (self.run/'analysis/pairwise_primary.csv').open() as stream: first=next(csv.DictReader(stream))
        self.write_csv('analysis/pairwise_primary.csv',[{**first,'decision':'CLAIM'}]*18);self.write_manifest()
        result=self.summary();self.assertTrue(result['error']);self.assertEqual(result['algorithms'],[])

    def test_research_claim_count_and_eligibility_must_agree(self):
        self.frozen('research')
        self.meta.update(claims=1,no_claim=17)
        self.assertTrue(self.summary()['error'])
        with (self.run/'analysis/pairwise_primary.csv').open() as stream: rows=list(csv.DictReader(stream))
        rows[0]['decision']='CLAIM';self.write_csv('analysis/pairwise_primary.csv',rows);self.write_manifest()
        self.assertTrue(self.summary()['error'])


if __name__=='__main__': unittest.main()
