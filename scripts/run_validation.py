"""Revalidate retained runs without changing their scientific evidence.

Campaign validation checks every required block and the retained JAR identity.
This is not authentication of the producer, or independent optimizer replay.
"""
import io
import math
import os
from contextlib import redirect_stderr
from pathlib import Path
import re
import sys

import research_runner as shared
import stress_runner as stress
import scenario_runner as scenario
from stress_validator import load, require

ROOT = Path(__file__).resolve().parents[1]


def campaign_selection_error(metadata):
    """Explain unsupported selections before inspecting any scientific evidence."""
    if metadata.get('status')=='checked' or metadata.get('action')=='check':
        return 'This is a setup check; no experiment was run. Select a completed experiment.'
    action=metadata.get('action')
    if action=='validate':
        return 'This is a validation report. Select the original experiment directory it checked.'
    if action and action!='profile':
        return f'This is a {shared.sanitize(action)} action; select a completed experiment.'
    if metadata.get('profile') not in (*shared.FROZEN_PROFILES,'stress','scenario'):
        return 'This directory has no supported experiment profile.'
    if metadata.get('status')!='complete' or type(metadata.get('exit_code')) is not int or metadata['exit_code']!=0 or metadata.get('validation')!='PASS':
        return 'The experiment is incomplete or unsuccessful. Select a completed experiment; partial evidence is available in Details.'
    return None


def _inside(root, value):
    require(isinstance(value, (str, Path)) and bool(str(value).strip()), 'Missing evidence path')
    path = Path(value)
    if not path.is_absolute(): path = root/path
    resolved = path.resolve()
    require(resolved.is_relative_to(root), f'Evidence path outside selected directory: {path}; moved campaigns with stale paths must be restored')
    require(resolved.exists(), f'Missing evidence: {path}')
    return resolved


def _safe_tree(root):
    # No following links to unrelated directories, giant external files or devices.
    def failed(error): raise error
    for parent, directories, files in os.walk(root, followlinks=False, onerror=failed):
        for name in directories+files:
            path = Path(parent)/name
            require(not path.is_symlink(), f'Unsupported evidence symlink: {path}')
            require(path.is_file() or path.is_dir(), f'Unsupported evidence file: {path}')


def _kind(run):
    data = load(run/'run.json')
    if 'scenario_schema_version' in data:
        require(type(data['scenario_schema_version']) is int and data['scenario_schema_version']==1
                and data.get('experiment_kind')=='static_scenario' and data.get('profile')=='scenario'
                and not {'schema_version','stress_schema_version'} & data.keys(), 'Unsupported scenario schema')
        kind = 'scenario'
    elif 'schema_version' in data:
        require(not {'stress_schema_version','scenario_schema_version','experiment_kind','experiment_phase'} & data.keys(), 'Mixed experiment schemas')
        require(type(data['schema_version']) is int and data['schema_version']==2, 'Unsupported research schema')
        require(isinstance(data.get('profile'),str) and data['profile'] in shared.FROZEN_PROFILES, 'Unsupported frozen profile')
        kind = 'statistics'
    else:
        require(type(data.get('stress_schema_version')) is int and data['stress_schema_version']==1
                and data.get('experiment_kind')=='static_stress' and data.get('profile')=='stress'
                and 'scenario_schema_version' not in data, 'Unsupported stress schema')
        kind = 'stress'
    require(data.get('state')=='COMPLETE' and data.get('error') is None, 'Run is not complete')
    return kind


def _stress_config(data):
    require(isinstance(data,dict), 'Missing stress effective config')
    for field in (*stress.FIELDS,'seed','heap_mib'):
        require(type(data.get(field)) is int, f'Invalid stress {field}')
    duration=data.get('time_limit')
    require(duration is None or type(duration) is int or
            (type(duration) is float and math.isfinite(duration) and duration.is_integer()), 'Invalid stress deadline')
    arguments=[]
    for field in (*stress.FIELDS,'seed','heap_mib'):
        arguments += ['--'+field.replace('_','-'),str(data[field])]
    arguments += ['--time-limit','none' if duration is None else str(int(duration))]
    try:
        with redirect_stderr(io.StringIO()): config=stress.parse_args(arguments)
    except SystemExit as error: raise ValueError('Invalid recorded stress configuration') from error
    require(stress.effective(config)==data, 'Unexpected recorded stress settings')
    return config


def _scenario_config(metadata):
    """Re-resolve the recorded scenario request through the Python configuration model."""
    resolved=metadata.get('resolved_config')
    require(isinstance(resolved,dict), 'Missing scenario resolved configuration')
    try:
        check=scenario.resolve_config(resolved)
    except scenario.ConfigError as error:
        raise ValueError(f'Invalid recorded scenario configuration: {error}') from error
    require(check==resolved, 'Recorded resolved configuration differs from the Python resolution')
    effective=metadata.get('effective_config')
    require(isinstance(effective,dict) and effective==scenario.effective(resolved,metadata.get('config_sha256')),
            'Recorded effective configuration differs from the Python resolution')
    require(metadata.get('feasibility')==scenario.feasibilities(resolved),
            'Recorded feasibility differs from the Python classification')
    return resolved


def _inspect(directory):
    directory=Path(directory)
    require(directory.is_dir() and not directory.is_symlink(), 'Select a run or campaign directory, not a file or symlink')
    root=directory.resolve(); _safe_tree(root)
    has_run=(root/'run.json').exists(); has_runner=(root/'runner.json').exists()
    require(has_run != has_runner, 'Select exactly one supported Java run or runner campaign')
    if has_run:
        require(set(root.rglob('run.json'))=={root/'run.json'}, 'Multiple unrelated runs in selected directory')
        return [(root,_kind(root))], None, None
    metadata=load(root/'runner.json')
    selection_error=campaign_selection_error(metadata)
    require(selection_error is None, selection_error)
    require(metadata.get('status')=='complete' and type(metadata.get('exit_code')) is int
            and metadata['exit_code']==0 and metadata.get('validation')=='PASS', 'Campaign is incomplete or unsuccessful')
    profile=metadata.get('profile')
    require(profile in (*shared.FROZEN_PROFILES,'stress','scenario'), 'Unsupported campaign profile')
    digest=metadata.get('artifact_sha256')
    require(isinstance(digest,str) and re.fullmatch('[0-9a-f]{64}',digest), 'Missing retained JAR hash')
    jar=_inside(root,profile+'.jar')
    require(shared.sha256(jar)==digest, 'Retained JAR hash mismatch')
    targets=[]
    if profile in shared.FROZEN_PROFILES:
        parent=_inside(root,profile)
        run=shared.completed_run(parent,digest,profile=profile)
        require(_inside(root,metadata.get('run_directory'))==run, 'Campaign run path mismatch')
        require(_kind(run)=='statistics', 'Wrong frozen experiment schema')
        targets.append((run,'statistics'))
    elif profile == 'scenario':
        resolved=_scenario_config(metadata)
        record=metadata.get('production')
        require(isinstance(record,dict) and record.get('phase')=='scenario' and record.get('validation')=='PASS',
                'Incomplete scenario campaign block')
        require(metadata.get('calibration') is None, 'Scenario campaigns do not run calibration blocks')
        block=_inside(root,'production')
        require(_inside(root,record.get('directory'))==block, 'Campaign block path mismatch')
        parent=_inside(root,block/'artifacts')
        run=scenario.completed_run(parent,digest,resolved)
        require(_inside(root,record.get('run_directory'))==run, 'Campaign run path mismatch')
        require(_kind(run)=='scenario', 'Wrong scenario experiment schema')
        targets.append((run,'scenario'))
        require(_inside(root,metadata.get('run_directory'))==targets[-1][0], 'Production run path mismatch')
    else:
        config=_stress_config(metadata.get('effective_config'))
        pilots=stress.calibrations(config)
        records=metadata.get('calibration')
        require(isinstance(records,list) and len(records)==len(pilots), 'Missing or duplicate calibration blocks')
        blocks=[(c,'stress_calibration',f'calibration-{c.vms}',r) for c,r in zip(pilots,records)]
        blocks.append((config,'stress','production',metadata.get('production')))
        for config,phase,name,record in blocks:
            require(isinstance(record,dict) and record.get('phase')==phase and record.get('validation')=='PASS', 'Incomplete campaign block')
            require(record.get('effective_config')==stress.effective(config), 'Campaign block config mismatch')
            block=_inside(root,name)
            require(_inside(root,record.get('directory'))==block, 'Campaign block path mismatch')
            parent=_inside(root,block/'artifacts')
            run=stress.completed_run(parent,digest,config,phase)
            require(_inside(root,record.get('run_directory'))==run, 'Campaign run path mismatch')
            require(_kind(run)=='stress', 'Wrong stress experiment schema')
            targets.append((run,'stress'))
        require(_inside(root,metadata.get('run_directory'))==targets[-1][0], 'Production run path mismatch')
    require(set(root.rglob('run.json'))=={p/'run.json' for p,_ in targets}, 'Multiple unrelated or unrecorded campaign runs')
    return targets,jar,digest


def validation_targets(directory: Path) -> list[tuple[Path,str]]:
    """Route complete supported evidence; this alone does not validate its science."""
    return _inspect(directory)[0]


def validate_existing(directory: Path, control: shared.ProcessControl, log_root: Path) -> dict:
    root=Path(directory).resolve(); logs=Path(log_root).resolve()
    require(not logs.is_relative_to(root), 'Validation logs must be outside selected evidence')
    targets,jar,digest=_inspect(directory)
    logs.mkdir(parents=True,exist_ok=True)
    for index,(run,kind) in enumerate(targets,1):
        command=[sys.executable,'-B',str(ROOT/'scripts'/f'{kind}_validator.py'),str(run)]
        log=logs/f'validation-{index}.log'
        code=control.run(command,log,'validation',cwd=ROOT)
        require(code==0, f'Independent validation failed with exit {code}; see {log}')
        if jar: require(shared.sha256(jar)==digest, 'Retained JAR changed during validation')
    # Recheck completeness and binding after validators, without rewriting input.
    require(_inspect(directory)==(targets,jar,digest), 'Evidence changed during validation')
    return {'scope':'campaign' if jar else 'individual_run','validation':'PASS',
            'validated_runs':[str(path) for path,_ in targets],
            'artifact_binding':'retained JAR SHA-256 verified (identity, not authenticity)' if jar
                               else 'unavailable: individual run has no campaign-supplied retained JAR',
            'limitations':'Independent validators retain their documented evidence limits; this is not optimizer replay.'}
