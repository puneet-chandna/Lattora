"""Existing-output validation: routing is not a substitute for independent checks."""
import importlib
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import research_runner as shared
import stress_runner as stress


class ExistingValidationTests(unittest.TestCase):
    def setUp(self):
        self.assertTrue((Path(__file__).parent/'run_validation.py').exists(), 'validation backend missing')
        self.validation = importlib.import_module('run_validation')
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name).resolve()
        self.root = self.base/'campaign'
        self.root.mkdir()

    def write(self, path, data):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(data))

    def campaign(self):
        args = stress.parse_args(['--preset', 'tiny', '--population', '2', '--iterations', '1', '--replications', '1'])
        jar = self.root/'stress.jar'; jar.write_bytes(b'fixture jar')
        digest = shared.sha256(jar)
        metadata = dict(profile='stress', status='complete', exit_code=0, validation='PASS',
                        artifact_sha256=digest, effective_config=stress.effective(args), calibration=[])
        for config, phase, name in [(p, 'stress_calibration', f'calibration-{p.vms}') for p in stress.calibrations(args)] + [(args, 'stress', 'production')]:
            run = self.root/name/'artifacts'/'stress-fixture'
            count = stress.estimate(config, [])
            manifest = dict(experiment_kind='static_stress', stress_schema_version=1, profile='stress',
                            experiment_phase=phase, state='COMPLETE', error=None, artifact_sha256=digest,
                            expected_cases=count['expected_cases'], attempted_cases=count['expected_cases'],
                            successful_cases=count['expected_cases'], failed_cases=0, unattempted_cases=0,
                            expected_evaluations=count['expected_evaluations'], completed_evaluations=count['expected_evaluations'],
                            effective_config={key: str(getattr(config, field)) for key, field in
                                              [('vm.count','vms'), ('host.count','hosts'), ('population','population'),
                                               ('iterations','iterations'), ('replications','replications'), ('master.seed','seed')]})
            self.write(run/'run.json', manifest)
            record = dict(phase=phase, effective_config=stress.effective(config), validation='PASS',
                          directory=str(run.parent.parent), run_directory=str(run))
            if phase == 'stress': metadata.update(production=record, run_directory=str(run))
            else: metadata['calibration'].append(record)
        self.write(self.root/'runner.json', metadata)
        return metadata

    def test_campaign_routes_every_required_pilot_and_production(self):
        self.campaign()
        targets = self.validation.validation_targets(self.root)
        self.assertEqual([(p.relative_to(self.root).parts[0], kind) for p,kind in targets],
                         [('calibration-100','stress'), ('production','stress')])

    def test_nonexperiment_directories_explain_the_selection_error(self):
        for metadata,reason in (
            (dict(profile='research',status='checked',exit_code=0,validation='NOT_RUN'),'setup check'),
            (dict(action='validate',status='complete',exit_code=0,validation='PASS'),'validation report'),
            (dict(action='build',status='complete',exit_code=0,validation='NOT_RUN'),'build action')):
            with self.subTest(metadata=metadata):
                self.write(self.root/'runner.json',metadata)
                with self.assertRaisesRegex(ValueError,reason): self.validation.validation_targets(self.root)

    def test_incomplete_forged_or_mismatched_campaign_is_rejected(self):
        for case in ('missing', 'duplicate', 'failed', 'config', 'external', 'jar', 'extra', 'forged'):
            with self.subTest(case=case):
                # Each mutation starts with a separate campaign.
                self.root = self.base/case; self.root.mkdir()
                data = self.campaign()
                if case == 'missing': data['calibration'] = []
                if case == 'duplicate': data['calibration'] *= 2
                if case == 'failed': data['production']['validation'] = 'NOT_RUN'
                if case == 'config': data['production']['effective_config']['hosts'] += 1
                if case == 'external': data['production']['run_directory'] = str(self.base/'outside')
                if case == 'jar': (self.root/'stress.jar').write_bytes(b'changed')
                if case == 'extra': self.write(self.root/'unrelated/run.json', {'schema_version':2})
                if case == 'forged':
                    manifest = Path(data['production']['run_directory'])/'run.json'
                    m = json.loads(manifest.read_text()); m['successful_cases']=0; self.write(manifest,m)
                self.write(self.root/'runner.json', data)
                with self.assertRaises(ValueError): self.validation.validation_targets(self.root)

    def test_scenario_campaign_routes_its_single_production_block(self):
        import scenario_runner as scenario
        jar = self.root/'scenario.jar'; jar.write_bytes(b'fixture jar')
        digest = shared.sha256(jar)
        resolved = scenario.resolve_config(scenario.request_json(scenario.PRESETS['known-answer-tight']))
        request = json.dumps(scenario.request_json(scenario.PRESETS['known-answer-tight']), indent=2).encode() + b'\n'
        recorded = hashlib.sha256(request).hexdigest()
        run = self.root/'production'/'artifacts'/'scenario-fixture'
        plan = scenario.planned_work(resolved)
        manifest = dict(experiment_kind='static_scenario', scenario_schema_version=2,
                        protocol=scenario.PROTOCOL, profile='scenario', config_name=resolved['name'],
                        config_sha256=recorded, state='COMPLETE', error=None, artifact_sha256=digest,
                        master_seed=resolved['master_seed'], objective=resolved['objective'],
                        expected_cases=plan['expected_cases'], attempted_cases=plan['expected_cases'],
                        successful_cases=plan['expected_cases'], failed_cases=0, unattempted_cases=0,
                        expected_evaluations=plan['expected_evaluations'],
                        completed_evaluations=plan['expected_evaluations'],
                        expected_simulations=plan['expected_cases'],
                        completed_simulations=plan['expected_cases'], search_simulations=0,
                        resolved_config=resolved, effective_config=scenario.effective(resolved, recorded),
                        feasibility=scenario.feasibilities(resolved))
        self.write(run/'run.json', manifest)
        metadata = dict(profile='scenario', status='complete', exit_code=0, validation='PASS',
                        artifact_sha256=digest, resolved_config=resolved, config_sha256=recorded,
                        effective_config=scenario.effective(resolved, recorded),
                        feasibility=scenario.feasibilities(resolved), calibration=None,
                        production=dict(phase='scenario', validation='PASS', directory=str(run.parent.parent),
                                        run_directory=str(run)), run_directory=str(run))
        self.write(self.root/'runner.json', metadata)
        self.write(run/'config/resolved.json', resolved)
        (run/'config/requested.json').write_bytes(request)
        self.assertEqual(self.validation.validation_targets(self.root), [(run, 'scenario')])
        for case, mutate in [
            ('calibration', lambda data: data.__setitem__('calibration', [{}])),
            ('profile', lambda data: data.__setitem__('profile', 'stress')),
            ('resolution', lambda data: data['resolved_config']['vms'].__setitem__('count', 3)),
            ('effective', lambda data: data['effective_config'].__setitem__('vm.count', '3')),
            ('feasibility', lambda data: data['feasibility'][0].__setitem__('kind', 'UNPROVED')),
            ('validation', lambda data: data['production'].__setitem__('validation', 'NOT_RUN'))]:
            with self.subTest(case=case):
                self.root = self.base/f'scenario-{case}'; self.root.mkdir()
                jar = self.root/'scenario.jar'; jar.write_bytes(b'fixture jar')
                data = json.loads(json.dumps(metadata)); data['artifact_sha256'] = shared.sha256(jar)
                run2 = self.root/'production'/'artifacts'/'scenario-fixture'
                manifest2 = json.loads(json.dumps(manifest)); manifest2['artifact_sha256'] = data['artifact_sha256']
                self.write(run2/'run.json', manifest2)
                self.write(run2/'config/resolved.json', resolved)
                (run2/'config/requested.json').write_bytes(request)
                data['production']['run_directory'] = str(run2); data['run_directory'] = str(run2)
                mutate(data)
                self.write(self.root/'runner.json', data)
                with self.assertRaises(ValueError): self.validation.validation_targets(self.root)

    def test_rejects_ambiguous_schema_duplicate_keys_and_nonobjects(self):
        for content in ('[]', '{bad', '{"schema_version":2,"schema_version":2}',
                        '{"schema_version":2,"stress_schema_version":1}',
                        '{"schema_version":true}', '{"schema_version":99}'):
            (self.root/'run.json').write_text(content)
            with self.subTest(content=content), self.assertRaises(ValueError):
                self.validation.validation_targets(self.root)
        with self.assertRaises(ValueError): self.validation.validation_targets(self.root/'run.json')

    def test_symlinked_evidence_is_rejected(self):
        self.campaign()
        (self.root/'external').symlink_to(self.base, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, 'symlink'):
            self.validation.validation_targets(self.root)

    def test_frozen_campaign_rejects_wrong_profile_or_count(self):
        jar = self.root/'smoke.jar'; jar.write_bytes(b'fixture jar')
        digest = shared.sha256(jar)
        run = self.root/'smoke/run-fixture'
        m = dict(schema_version=2, profile='smoke', state='COMPLETE', error=None, artifact_sha256=digest,
                 expected_cases=4, attempted_cases=4, successful_cases=4, failed_cases=0, unattempted_cases=0)
        self.write(run/'run.json',m)
        self.write(self.root/'runner.json', dict(profile='smoke', status='complete', exit_code=0, validation='PASS',
                                               artifact_sha256=digest, run_directory=str(run)))
        self.assertEqual(self.validation.validation_targets(self.root),[(run,'statistics')])
        m['profile']='research'; self.write(run/'run.json',m)
        with self.assertRaises(ValueError): self.validation.validation_targets(self.root)

    def test_real_validator_rejects_forged_complete_and_keeps_artifacts_unchanged(self):
        self.campaign()
        before = {p: p.read_bytes() for p in self.root.rglob('*') if p.is_file()}
        logs = self.base/'logs'; logs.mkdir()
        control = shared.ProcessControl(shared.Dashboard(plain=True, stream=io.StringIO()), self.root)
        with self.assertRaisesRegex(ValueError, 'validation failed'):
            self.validation.validate_existing(self.root,control,logs)
        self.assertEqual(before,{p:p.read_bytes() for p in self.root.rglob('*') if p.is_file()})
        self.assertTrue(list(logs.glob('*.log')))

    def test_real_direct_run_validation_is_java_free_and_marks_binding_unavailable(self):
        # Reuse the independently hand-built scientific fixture, not a fake validator.
        from test_stress_validator import StressValidatorTest
        fixture = StressValidatorTest(); fixture.setUp(); self.addCleanup(fixture.doCleanups)
        logs = self.base/'logs'; logs.mkdir()
        control = shared.ProcessControl(shared.Dashboard(plain=True, stream=io.StringIO()), self.root)
        with patch.dict('os.environ', {'JAVA_HOME':'/missing'}):
            result = self.validation.validate_existing(fixture.directory,control,logs)
        self.assertEqual(result['validation'],'PASS')
        self.assertEqual(result['scope'],'individual_run')
        self.assertIn('unavailable',result['artifact_binding'])
        with self.assertRaisesRegex(ValueError, 'outside'):
            self.validation.validate_existing(fixture.directory,control,fixture.directory/'logs')

    def test_jar_changed_during_validator_cannot_pass(self):
        self.campaign()
        logs=self.base/'logs'; logs.mkdir()
        class MutatingControl:
            def run(inner,*args,**kwargs):
                (self.root/'stress.jar').write_bytes(b'tampered during validation')
                return 0
        with self.assertRaises(ValueError): self.validation.validate_existing(self.root,MutatingControl(),logs)

    def test_unlimited_deadline_and_malformed_config(self):
        data = stress.effective(stress.parse_args(['--time-limit','none']))
        self.assertIsNone(self.validation._stress_config(data).time_limit)
        for value in (True, -1, 0, 10**1000, float('inf'), '7200'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                self.validation._stress_config(dict(data,time_limit=value))

    def test_launcher_validation_logs_are_outside_selected_evidence(self):
        import cloudsim
        from test_stress_validator import StressValidatorTest
        fixture = StressValidatorTest(); fixture.setUp(); self.addCleanup(fixture.doCleanups)
        before = {p:p.read_bytes() for p in fixture.directory.rglob('*') if p.is_file()}
        outcomes=[]
        # An explicit output parent inside evidence is rejected without mutation.
        options=cloudsim.parse_args(['--validate',str(fixture.directory),'--plain',
                                    '--output-dir',str(fixture.directory/'new-logs')])
        with patch('sys.stdout',new=io.StringIO()): code=cloudsim.execute(options,on_complete=outcomes.append)
        self.assertNotEqual(code,0)
        self.assertEqual(before,{p:p.read_bytes() for p in fixture.directory.rglob('*') if p.is_file()})


if __name__ == '__main__': unittest.main()
