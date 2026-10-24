"""Launcher contracts; no scientific campaigns or Maven runs in these tests."""
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from contextlib import redirect_stderr, redirect_stdout
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]


class LauncherTests(unittest.TestCase):
    def setUp(self):
        self.assertTrue((ROOT/'scripts/cloudsim.py').is_file(), 'launcher implementation is missing')
        spec = importlib.util.spec_from_file_location('cloudsim', ROOT/'scripts/cloudsim.py')
        self.cli = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.cli)
        self.temp = tempfile.TemporaryDirectory(prefix='launcher checks ')
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name).resolve()

    def rejected(self, arguments):
        with redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as caught:
            self.cli.parse_args(arguments)
        self.assertEqual(caught.exception.code, 2)

    def config(self, content):
        path = self.base/'config.properties'
        path.write_text(content, encoding='utf-8')
        return path

    def test_all_actions_and_empty_argv(self):
        for arguments, action in [([], None), (['--check'], 'check'), (['--build'], 'build'),
                                  (['--test'], 'test'), (['--validate', str(self.base)], 'validate')]:
            self.assertEqual(self.cli.parse_args(arguments).action, action)
        for profile in ('smoke', 'explore', 'research', 'stress', 'scenario'):
            args = self.cli.parse_args(['--profile', profile, '--preset', 'known-answer-tight'] if profile == 'scenario'
                                       else ['--profile', profile])
            self.assertEqual((args.action, args.profile), ('profile', profile))

    def test_workers_are_runtime_controls_and_force_build_is_explicit(self):
        for profile in ('smoke', 'explore', 'research', 'stress'):
            args = self.cli.parse_args(['--profile', profile, '--workers', '3', '--force-build', '--dry-run'])
            self.assertEqual(args.workers, 3)
            self.assertIn('--force-build', self.cli.runner_args(args))
            with patch.object(self.cli.shared, 'available_cpus', return_value=12):
                preview = self.cli.preview(args)
            self.assertEqual(preview['execution']['workers'], 2)
            self.assertIn('-Dcloudsim.workers=2', preview['commands']['java'][-1])
            self.assertEqual(preview['execution']['requested_workers'], 3)
        for arguments in (['--check', '--workers', '2'], ['--build', '--force-build'],
                          ['--profile', 'smoke', '--force-build', '--skip-build'],
                          ['--profile', 'smoke', '--workers', '0'],
                          ['--profile', 'stress', '--workers', '33']):
            self.rejected(arguments)

    def test_worker_bounds_use_cpu_and_shared_heap(self):
        with patch.object(self.cli.shared, 'available_cpus', return_value=12):
            for requested, heap, expected in [('auto', 512, 1), ('auto', 1024, 2),
                                               (1, 4096, 1), (32, 4096, 8), ('auto', 32768, 12)]:
                self.assertEqual(self.cli.shared.execution_settings(requested, heap)['workers'], expected)
        for value in ('', '-1', '1.0', '١', 'AUTO'):
            with self.assertRaises(Exception): self.cli.shared.worker_value(value)

    def test_profile_setup_failures_retain_diagnostics_before_launch(self):
        runtime = self.base/'runtime only'/'bin'
        runtime.mkdir(parents=True)
        (runtime/'java').write_text('#!/bin/sh\nexit 99\n')
        (runtime/'java').chmod(0o755)
        env = {**os.environ, 'JAVA_HOME': str(runtime.parent)}
        for name in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'):
            env.pop(name, None)
        for profile in ('smoke', 'explore', 'research', 'stress'):
            with self.subTest(profile=profile):
                output = self.base/profile
                result = subprocess.run([str(ROOT/'cloudsim.sh'), '--profile', profile,
                    '--plain', '--output-dir', str(output)], env=env,
                    capture_output=True, text=True, timeout=15)
                self.assertEqual(result.returncode, 1, result.stderr)
                retained = list(output.glob('*/runner.json'))
                self.assertEqual(len(retained), 1, result.stdout)
                metadata = json.loads(retained[0].read_text())
                self.assertEqual(metadata['status'], 'failed')
                self.assertEqual(metadata['exit_code'], 1)
                self.assertEqual(metadata['validation'], 'NOT_RUN')
                self.assertEqual(metadata['tests'], 'NOT_RUN')
                self.assertIn('javac', metadata['error'])
                self.assertIn(metadata['error'], (retained[0].parent/'console.log').read_text())
                self.assertEqual(metadata['output_directory'], str(retained[0].parent))
                self.assertFalse(list(retained[0].parent.glob('*.jar')))

    def test_incomplete_jdk_error_identifies_selection_and_recovery(self):
        runtime = self.base/'runtime only'/'bin'
        runtime.mkdir(parents=True)
        (runtime/'java').touch()
        with patch.dict(os.environ, {'JAVA_HOME': str(runtime.parent)}, clear=True):
            args = self.cli.parse_args(['--check', '--plain', '--output-dir', str(self.base/'logs')])
            outcomes = []
            with redirect_stdout(io.StringIO()):
                self.assertEqual(self.cli.execute(args, on_complete=outcomes.append), 1)
        self.assertIn(str(runtime/'java'), outcomes[0]['error'])
        self.assertIn('--check', outcomes[0]['error'])
        self.assertIn('experiment settings', outcomes[0]['error'])

    def test_invalid_invocations_are_side_effect_free(self):
        invalid = [['--plain'], ['--profile', 'smoke', '--check'], ['--check', '--build'],
                   ['--profile', 'research', '--profile=smoke'], ['--plain', '--plain', '--check'],
                   ['--pro', 'smoke'], ['--wat'], ['--build', '--dry-run'],
                   ['--check', '--skip-build'], ['--validate', str(self.base), '--heap-mib', '512'],
                   ['--profile', 'stress', '--config', 'missing'], ['--profile', 'smoke', '--seed', '2'],
                   ['--profile', 'scenario'], ['--profile', 'scenario', '--preset', 'micro'],
                   ['--profile', 'scenario', '--preset', 'known-answer-tight', '--vms', '4'],
                   ['--profile', 'stress', '--preset', 'known-answer-tight'],
                   ['--profile', 'smoke', '--preset', 'known-answer-tight'],
                   ['--profile', 'scenario', '--preset', 'known-answer-tight', '--preset', 'resource-pressure'],
                   ['--profile', 'scenario', '--config', 'small'],
                   ['--profile', 'explore', '--preset', 'small'], ['--profile', 'research', '--vms', '3'],
                   ['--profile', 'stress', '--population', '3'], ['--profile', 'smoke', '--heap-mib', '511'],
                   ['--profile', 'smoke', '--output-dir', ' '], ['--check', '--config', 'missing']]
        with patch.object(self.cli.subprocess, 'Popen', side_effect=AssertionError('child launched')):
            for arguments in invalid:
                with self.subTest(arguments=arguments): self.rejected(arguments)
        self.assertEqual(list(self.base.iterdir()), [])

    def test_real_help_and_nonterminal_noargs(self):
        for arguments, expected in [(['--help'], 0), ([], 2), (['--plain'], 2)]:
            result = subprocess.run([sys.executable, '-B', str(ROOT/'scripts/cloudsim.py'), *arguments],
                                    cwd=self.base, capture_output=True, text=True,
                                    env={**os.environ, 'JAVA_HOME': '/does-not-exist'})
            self.assertEqual(result.returncode, expected, result.stderr)
            if not arguments: self.assertIn('--profile', result.stderr)
            if arguments == ['--help']: self.assertIn('--validate', result.stdout)
        self.assertEqual(list(self.base.iterdir()), [])

    def test_shell_preserves_caller_cwd_and_literal_arguments(self):
        result = subprocess.run([str(ROOT/'cloudsim.sh'), '--profile', 'smoke', '--dry-run',
                                 '--output-dir', 'space ; $(false)'], cwd=self.base, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        data = json.loads(result.stdout)
        self.assertEqual(data['effective_config']['output_dir'], str(self.base/'space ; $(false)'))
        self.assertEqual(list(self.base.iterdir()), [])

    def test_java_properties_grammar_and_defaults(self):
        self.assertEqual(self.cli.read_frozen_config(None), {'master.seed': '123456', 'log.level': 'INFO'})
        accepted = [('# comment\n ! other\n master.seed : +00012\nlog.level DEBUG\n', '12', 'DEBUG'),
                    ('master\\u002eseed=\\u002d7\nlog\\.level=DE\\\n \t BUG\n', '-7', 'DEBUG'),
                    ('master.seed=0000000000000000000000000000000000001\n', '1', 'INFO'),
                    ('master.seed=-9223372036854775808\rlog.level=INFO\r', '-9223372036854775808', 'INFO'),
                    ('master.seed=2\\', '2', 'INFO'),
                    ('master.seed=\\\n  12\n', '12', 'INFO')]
        for content, seed, level in accepted:
            with self.subTest(content=content):
                self.assertEqual(self.cli.read_frozen_config(self.config(content)),
                                 {'master.seed': seed, 'log.level': level})

    def test_java_properties_reject_decoded_duplicates_unknowns_and_invalid_values(self):
        invalid = ['master.seed=1\nmaster\\u002eseed=2', 'log\\.level=INFO\nlog.level=DEBUG',
                   'master\\ seed=1', 'x=1', 'clé=1', 'log.level=warn', 'log.level=INFO ',
                   'master.seed=9223372036854775808', 'master.seed=-9223372036854775809',
                   'master.seed=0x1', 'master.seed=١', 'master.seed=', 'master.seed=\\uZZZZ',
                   'master.seed=\\uu0031', 'master.seed=\\\n#1', 'master.seed=1\\t']
        for content in invalid:
            with self.subTest(content=content), self.assertRaises(ValueError):
                self.cli.read_frozen_config(self.config(content))
        path = self.base/'invalid-utf8.properties'; path.write_bytes(b'master.seed=1\xff')
        with self.assertRaises(ValueError): self.cli.read_frozen_config(path)

    def test_bad_config_rejected_before_any_dispatch(self):
        path = self.config('x=1')
        self.rejected(['--profile', 'research', '--config', str(path)])
        self.rejected(['--profile', 'research', '--config', str(self.base/'missing')])

    def test_empty_continuation_restarts_comment_and_blank_line_detection(self):
        for content, seed in [('\\\n# comment\nmaster.seed=3', '3'),
                              ('\\\n! comment\nmaster.seed=4', '4'),
                              ('\\\n\nmaster.seed=6', '6'), ('\\\n \t', '123456')]:
            self.assertEqual(self.cli.read_frozen_config(self.config(content))['master.seed'], seed)

    def test_frozen_previews_show_exact_config_counts_and_commands_without_children(self):
        for profile, cases in [('smoke', 4), ('explore', 40), ('research', 450)]:
            args = self.cli.parse_args(['--profile', profile, '--dry-run', '--config',
                                        str(self.config('master.seed=-9\nlog.level=DEBUG'))])
            with patch.object(self.cli.shared, 'physical_memory', return_value=8*1024**3), \
                 patch.object(self.cli.shared, 'usable_memory', return_value=4*1024**3), \
                 patch.object(self.cli.subprocess, 'Popen', side_effect=AssertionError('child launched')):
                data = self.cli.preview(args)
            self.assertEqual(data['planned_work']['expected_cases'], cases)
            self.assertEqual(data['effective_config']['master.seed'], '-9')
            self.assertEqual(data['effective_config']['log.level'], 'DEBUG')
            self.assertEqual(data['effective_config']['profile'], profile)
            self.assertIn('--profile', data['commands']['java'][0])
            self.assertIn(profile, data['commands']['java'][0])
            self.assertNotIn('estimated_wall_seconds', data['planned_work'])

    def test_stress_preview_reuses_resolved_pilots_and_memory_warning(self):
        args = self.cli.parse_args(['--profile', 'stress', '--preset', 'xlarge', '--time-limit', '4h', '--dry-run'])
        with patch.object(self.cli.shared, 'physical_memory', return_value=4*1024**3), \
             patch.object(self.cli.shared, 'usable_memory', side_effect=ValueError('unknown')):
            data = self.cli.preview(args)
        self.assertEqual(data['effective_config']['vms'], 20000)
        self.assertEqual(data['effective_config']['time_limit'], 14400)
        self.assertEqual(len(data['planned_work']['calibration']), 3)
        self.assertEqual(data['planned_work']['combined_totals']['expected_cases'], 32)
        self.assertIsNone(data['memory_evidence']['usable_memory_bytes'])
        self.assertTrue(data['warnings'])
        self.assertEqual(len(data['commands']['java']), 4)

    def test_scenario_preview_reports_resolution_planned_work_and_feasibility(self):
        args = self.cli.parse_args(['--profile', 'scenario', '--preset', 'known-answer-tight', '--dry-run',
                                    '--output-dir', str(self.base)])
        with patch.object(self.cli.shared, 'available_cpus', return_value=12):
            data = self.cli.preview(args)
        self.assertEqual(data['profile'], 'scenario')
        self.assertEqual(data['planned_work'], {'expected_cases': 4, 'evaluation_budget': 130,
                                                'expected_evaluations': 262})
        self.assertEqual(data['feasibility'], [{'replication': 0, 'kind': 'CONSTRUCTIBLE', 'reason': None}])
        self.assertEqual(data['resolved_config']['workload']['length_seconds'], [60])
        self.assertEqual(data['effective_config']['config.name'], 'known-answer-tight')
        self.assertEqual(data['effective_config']['experiment.kind'], 'static_scenario')
        self.assertEqual(data['effective_config']['scenario.schema.version'], '1')
        self.assertEqual(data['effective_config']['workload.length.seconds'], '60,60')
        self.assertEqual(len(data['commands']['java']), 1)
        command=data['commands']['java'][0]
        self.assertEqual(command[command.index('--profile')+1], 'scenario')
        self.assertEqual(command[-2:], ['--config', '<CONFIG>'])
        self.assertEqual(data['time_limit_scope'], 'production + validation; excludes build/tests')
        self.assertEqual(self.cli.runner_args(args)[-2:], ['--preset', 'known-answer-tight'])

    def test_scenario_config_errors_stop_before_output(self):
        path = self.base/'scenario.json'
        path.write_text('{"config_version": 2}', encoding='utf-8')
        for arguments in (['--profile', 'scenario', '--config', str(path)],
                          ['--profile', 'scenario', '--config', str(self.base/'absent.json')]):
            with self.subTest(arguments=arguments):
                with redirect_stderr(io.StringIO()) as errors, self.assertRaises(SystemExit) as caught:
                    self.cli.parse_args(arguments)
                # The runner parser prints its own usage; only the message and exit code are contractual.
                self.assertEqual(caught.exception.code, 2 if 'absent' not in str(arguments) else 1)
                self.assertIn('config_version must be the integer 1' if 'absent' not in str(arguments)
                              else 'config file is missing or unreadable', errors.getvalue())
        self.assertFalse((self.base/'config.json').exists())

    def test_runner_dispatch_once_and_original_invocation(self):
        for profile in ('smoke', 'explore', 'research', 'stress', 'scenario'):
            args = self.cli.parse_args((['--profile', profile, '--preset', 'known-answer-tight']
                                        if profile == 'scenario' else ['--profile', profile])
                                       + ['--skip-build', '--output-dir', str(self.base)])
            target = (self.cli.scenario if profile == 'scenario'
                      else self.cli.stress if profile == 'stress' else self.cli.shared)
            with patch.object(target, 'main', return_value=7) as run:
                self.assertEqual(self.cli.execute(args), 7)
            self.assertEqual(run.call_count, 1)
            called, = run.call_args.args
            self.assertEqual(called, self.cli.runner_args(args))
            self.assertEqual(run.call_args.kwargs['invocation']['arguments'], args.arguments)
            self.assertIn('effective_config', run.call_args.kwargs['invocation'])

    def test_interactive_invocation_is_not_fabricated(self):
        args = self.cli.parse_args(['--profile', 'smoke', '--skip-build'])
        invocation = {'arguments': [], 'interactive_choices': {'profile': 'smoke'}}
        with patch.object(self.cli.shared, 'main', return_value=0) as run:
            self.cli.execute(args, invocation=invocation)
        self.assertEqual(run.call_args.kwargs['invocation']['arguments'], [])
        self.assertEqual(run.call_args.kwargs['invocation']['interactive_choices'], {'profile': 'smoke'})
        self.assertEqual(invocation, {'arguments': [], 'interactive_choices': {'profile': 'smoke'}})

    def test_structured_invocation_retained_by_both_runners(self):
        invocation = {'arguments': [], 'interactive_choices': {'profile': 'smoke'}}
        for runner, argv, kwargs in [(self.cli.shared, ['--check'], {}),
                                     (self.cli.stress, ['--dry-run'], {})]:
            results = []
            with patch.object(self.cli.shared, 'check_environment', return_value=(Path('/java'), 4*1024**3)), \
                 redirect_stdout(io.StringIO()):
                code = runner.main(argv, invocation=invocation, on_complete=results.append, **kwargs)
            self.assertEqual(code, 0)
            self.assertEqual(results[0]['invocation'], invocation)

    def test_maintenance_commands_retained_and_stop_on_exact_failure(self):
        for action, exits, expected, count in [('build', [0], 0, 1), ('test', [19], 19, 2),
                                              ('test', [0, 23], 23, 3), ('test', [0, 0], 0, 3)]:
            args = self.cli.parse_args(['--'+action, '--output-dir', str(self.base)])
            project = self.base/(action+'-'+str(expected))
            (project/'target').mkdir(parents=True)
            (project/'target/lattora-2.0.0.jar').write_bytes(b'packaged fixture')
            completed = []
            def controlled(command, log, *positional, **keywords):
                if command[0] == 'git': log.write_text(''); return 0
                return exits.pop(0)
            with patch.object(self.cli.shared, 'check_environment', return_value=(Path('/java'), 4*1024**3)), \
                 patch.object(self.cli, 'ROOT', project), \
                 patch.object(self.cli.shared.ProcessControl, 'run', side_effect=controlled) as run, \
                 redirect_stdout(io.StringIO()):
                self.assertEqual(self.cli.execute(args, on_complete=completed.append), expected)
            self.assertEqual(run.call_count, count)
            commands = [call.args[0] for call in run.call_args_list if call.args[0][0] != 'git']
            expected_maven = [str(project/'mvnw'), '-B', '-Dmaven.repo.local='+str(project/'.cloudsim/maven/repository')]
            expected_maven += ['-Dmaven.test.skip=true', 'clean', 'package'] if action == 'build' else ['clean', 'verify']
            self.assertEqual(commands[0], expected_maven)
            record = json.loads((Path(completed[0]['output_directory'])/'runner.json').read_text())
            self.assertEqual(record['exit_code'], expected)
            self.assertEqual(completed[0]['validation'], 'NOT_RUN')
            if action == 'build': self.assertEqual(completed[0]['tests'], 'NOT_RUN')

    def test_check_calls_shared_preflight_without_build(self):
        args = self.cli.parse_args(['--check', '--output-dir', str(self.base)])
        completed = []
        with patch.object(self.cli.shared, 'check_environment', return_value=(Path('/java'), 4*1024**3)) as check, \
             patch.object(self.cli.shared.ProcessControl, 'run', side_effect=AssertionError('build run')), \
             redirect_stdout(io.StringIO()):
            self.assertEqual(self.cli.execute(args, on_complete=completed.append), 0)
        check.assert_called_once()
        self.assertEqual(completed[0]['status'], 'checked')

    def test_validation_is_java_free_and_missing_or_failed_backend_never_passes(self):
        args = self.cli.parse_args(['--validate', str(self.base/'evidence'), '--output-dir', str(self.base/'logs')])
        result = {'scope':'campaign','validation':'PASS','artifact_binding':'verified','limitations':'recorded evidence only'}
        for backend, expected in [(None, 1), (SimpleNamespace(validate_existing=lambda *args: result), 0)]:
            completed = []
            with patch.dict(sys.modules, {'run_validation': backend}), \
                 patch.object(self.cli.shared, 'check_environment', side_effect=AssertionError('Java required')), \
                 redirect_stdout(io.StringIO()):
                self.assertEqual(self.cli.execute(args, on_complete=completed.append), expected)
            self.assertEqual(completed[0]['validation'], 'PASS' if expected == 0 else 'NOT_RUN')
        def fail(*args): raise ValueError('incomplete pilot')
        completed = []
        with patch.dict(sys.modules, {'run_validation': SimpleNamespace(validate_existing=fail)}), \
             redirect_stdout(io.StringIO()):
            self.assertEqual(self.cli.execute(args, on_complete=completed.append), 1)
        self.assertEqual(completed[0]['validation'], 'NOT_RUN')
        self.assertIn('incomplete pilot', completed[0]['error'])

    def test_tui_startup_is_lazy_and_missing_module_is_explicit(self):
        with patch.object(self.cli.sys.stdin, 'isatty', return_value=True), \
             patch.object(self.cli.sys.stdout, 'isatty', return_value=True), \
             patch.object(self.cli.cloudsim_runtime, 'ensure_ui', return_value=True), \
             patch.dict(sys.modules, {'cloudsim_tui': None}), redirect_stderr(io.StringIO()) as errors:
            self.assertEqual(self.cli.main([]), 2)
        self.assertIn('interactive terminal application unavailable', errors.getvalue())
        with patch.object(self.cli.sys.stdin, 'isatty', return_value=True), \
             patch.object(self.cli.sys.stdout, 'isatty', return_value=True), \
             patch.object(self.cli.cloudsim_runtime, 'ensure_ui', return_value=True), \
             patch.dict(sys.modules, {'cloudsim_tui': SimpleNamespace(run=lambda: 9)}):
            self.assertEqual(self.cli.main([]), 9)

    def test_dry_run_prints_preview_without_dispatch(self):
        with patch.object(self.cli.shared, 'main', side_effect=AssertionError('dispatch')), \
             redirect_stdout(io.StringIO()) as output:
            self.assertEqual(self.cli.main(['--profile', 'smoke', '--dry-run']), 0)
        self.assertEqual(json.loads(output.getvalue())['planned_work']['expected_cases'], 4)


if __name__ == '__main__': unittest.main()
