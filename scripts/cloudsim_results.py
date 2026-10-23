"""Small, read-only completion summaries; scientific validators stay independent."""
import csv
from datetime import datetime
import hashlib
import io
import json
import math
from pathlib import Path
import re
import statistics

PROFILES=('smoke','explore','research','stress','scenario')


def _require(condition,message):
    if not condition: raise ValueError(message)


def _number(value):
    _require(not isinstance(value,bool),'Boolean result metric')
    number=float(value)
    _require(math.isfinite(number),'Nonfinite result metric')
    return number


def _integer(value):
    number=_number(value)
    _require(number>=0 and number.is_integer(),'Invalid result counter')
    return int(number)


def _read(root,path,limit):
    path=Path(path).absolute()
    _require(path.resolve().is_relative_to(root),'Result evidence is outside its campaign')
    _require(not any(p.is_symlink() for p in [path,*path.parents] if p!=root and p.is_relative_to(root)),
             'Result evidence uses an unsupported symlink')
    _require(path.is_file() and path.stat().st_size<=limit,'Result evidence is missing or too large for a preview')
    with path.open('rb') as stream: data=stream.read(limit+1)
    _require(len(data)<=limit,'Result evidence is too large for a preview')
    return data


def _csv(root,run,manifest,name):
    data=_read(root,run/name,16*1024**2)
    _require(hashlib.sha256(data).hexdigest()==manifest['files'].get(name),'Result evidence changed: '+name)
    rows=list(csv.DictReader(io.StringIO(data.decode('utf-8')),strict=True))
    _require(all(None not in row and None not in row.values() for row in rows),'Malformed result CSV: '+name)
    return rows


def _elapsed(metadata):
    try:
        seconds=(datetime.fromisoformat(metadata['finished_at'])-datetime.fromisoformat(metadata['started_at'])).total_seconds()
        return f'{seconds:.2f}s total' if seconds>=0 else None
    except (KeyError,TypeError,ValueError): return None


def _unique(pairs):
    result={}
    for key,value in pairs:
        _require(key not in result,'Duplicate result manifest key')
        result[key]=value
    return result


def build_result_summary(metadata):
    """Return display facts from complete hash-bound outputs, or a clear preview error.

    This does not rerun the expensive scientific validators. Their stored outcome
    is identified separately from preview integrity and descriptive observations.
    """
    result={'overview':[],'algorithms':[],'checks':[],'notes':[],'error':None}
    profile=metadata.get('profile')
    if metadata.get('action') not in (None,'profile') or profile not in PROFILES or metadata.get('status')!='complete' or metadata.get('exit_code')!=0 or metadata.get('validation')!='PASS':
        return result
    result['checks'].append({'label':'Independent validation','status':'RECORDED_PASS',
                             'detail':'The per-run Python validator passed; recorded outcome.'})
    build=metadata.get('build')
    tests=metadata.get('tests','NOT_RUN')
    build_detail='Verified build reused' if build=='REUSED_VERIFIED' else 'Fresh build and tests' if build=='VERIFIED' else 'Recorded build/test outcome'
    receipt=metadata.get('build_receipt',{})
    tested=receipt.get('tested_at') if isinstance(receipt,dict) else None
    if tested: build_detail+='; originally tested '+str(tested)
    if build == 'RELEASE_VERIFIED':
        release = metadata.get('release_verification', {})
        passed = isinstance(release,dict) and release.get('java')=='PASS' and release.get('python')=='PASS'
        result['checks'].append({'label':'Release verification','status':'RECORDED_PASS' if passed else 'UNKNOWN',
                                'detail':'Release CI verification recorded; local build/tests '+str(tests)+'.'})
    else:
        result['checks'].append({'label':'Build verification','status':str(tests),'detail':build_detail})
    if profile in ('stress','scenario'):
        result['checks'].append({'label':'Hypothesis tests','status':'NOT_APPLICABLE',
                                 'detail':'This static campaign is descriptive; BCa, wild-bootstrap and Holm run in frozen Research.'})
        result['notes'].append('Descriptive results for this static workload; no statistical superiority claim.')
    elif profile in ('smoke','explore'):
        result['checks'].append({'label':'Hypothesis tests','status':'NOT_APPLICABLE',
                                 'detail':'Frozen protocol check; inferential tests belong to Research.'})
    try:
        _require(isinstance(receipt,dict),'Invalid build receipt metadata')
        production=metadata.get('production',{})
        execution=metadata.get('execution',{})
        _require(isinstance(production,dict) and isinstance(execution,dict),'Invalid execution metadata')
        root=Path(metadata['output_directory'])
        _require(root.is_dir() and not root.is_symlink(),'Campaign directory is unavailable')
        root=root.resolve();run=Path(metadata['run_directory'])
        manifest=json.loads(_read(root,run/'run.json',8*1024**2),object_pairs_hook=_unique,
                            parse_constant=lambda value:_require(False,'Nonfinite result manifest'))
        _require(manifest['state']=='COMPLETE' and manifest.get('error') is None and manifest['profile']==profile,
                 'Result manifest is incomplete or has a different profile')
        _require(isinstance(metadata.get('artifact_sha256'),str) and re.fullmatch('[a-f0-9]{64}',metadata['artifact_sha256'])
                 and manifest.get('artifact_sha256')==metadata['artifact_sha256'] and isinstance(manifest['files'],dict),
                 'Result artifact does not match its campaign')
        stress=profile=='stress'
        scenario=profile=='scenario'
        if scenario:
            _require(manifest.get('scenario_schema_version')==1 and manifest.get('experiment_kind')=='static_scenario'
                     and manifest.get('protocol')=='static_scenario_v1'
                     and 'stress_schema_version' not in manifest and 'schema_version' not in manifest,
                     'Unsupported result schema')
        elif stress:
            _require(manifest.get('stress_schema_version')==1 and manifest.get('experiment_kind')=='static_stress','Unsupported result schema')
        else:
            _require(manifest.get('schema_version')==2,'Unsupported result schema')
        raw=_csv(root,run,manifest,'raw/cases.csv' if stress or scenario else 'raw/main_results.csv')
        if profile=='research': raw+=_csv(root,run,manifest,'raw/sensitivity_results.csv')
        expected=_integer(manifest['expected_cases'])
        _require(len(raw)==expected==_integer(manifest['successful_cases']) and _integer(manifest['failed_cases'])==0,
                 'Result case counts disagree')
        _require(all(r['status']=='RUN_OK' and r['run_id']==manifest['run_id'] for r in raw),'Result case identities or statuses disagree')
        for row in raw:
            for field in ('energy_j','sla_rate','allocation_wall_ns'):
                value=_number(row[field]);_require(value>=0,'Negative result metric')
            _require(_number(row['energy_j'])>0 and _number(row['sla_rate'])<=1,'Invalid energy or SLA metric')
        data=raw if stress or scenario else [r for r in raw if r['phase']=='main']
        if stress or scenario:
            means=_csv(root,run,manifest,'analysis/summary.csv')
        elif profile=='smoke':
            means=[]
            for scenario_name,algorithm in dict.fromkeys((r['scenario'],r['algorithm']) for r in data):
                group=[r for r in data if (r['scenario'],r['algorithm'])==(scenario_name,algorithm)]
                means.append({'run_id':manifest['run_id'],'scenario':scenario_name,'algorithm':algorithm,'n':len(group),
                              **{target:statistics.fmean(_number(r[source]) for r in group) for source,target in
                                 [('energy_j','energy_mean_j'),('sla_rate','sla_mean'),('allocation_wall_ns','allocation_mean_ns')]}})
        else: means=_csv(root,run,manifest,'analysis/scenario_summary.csv')
        identities={(r['scenario'],r['algorithm']) for r in data}
        _require(len(means)==len(identities) and {(r['scenario'],r['algorithm']) for r in means}==identities,
                 'Result summary groups disagree')
        algorithms=[]
        for mean in means:
            group=[r for r in data if (r['scenario'],r['algorithm'])==(mean['scenario'],mean['algorithm'])]
            _require(mean['run_id']==manifest['run_id'] and _integer(mean['n'])==len(group),'Result summary identity or sample count disagrees')
            for source,target in [('energy_j','energy_mean_j'),('sla_rate','sla_mean'),('allocation_wall_ns','allocation_mean_ns')]:
                _require(math.isclose(_number(mean[target]),statistics.fmean(_number(r[source]) for r in group),rel_tol=2e-12,abs_tol=1e-10),
                         'Result mean disagrees with raw cases: '+target)
            if profile in ('explore','research'):
                for source,target in [('energy_j','energy_sd_j'),('sla_rate','sla_sd'),('allocation_wall_ns','allocation_sd_ns')]:
                    deviation=_number(mean[target])
                    expected_sd=statistics.stdev(_number(r[source]) for r in group) if len(group)>1 else 0
                    _require(deviation>=0 and math.isclose(deviation,expected_sd,rel_tol=2e-12,abs_tol=1e-10),
                             'Result sample standard deviation disagrees with raw cases: '+target)
            row={'algorithm':mean['algorithm'],'scenario':mean['scenario'],'cases':len(group),
                 'energy_j':_number(mean['energy_mean_j']),'sla':_number(mean['sla_mean']),
                 'runtime_ms':_number(mean['allocation_mean_ns'])/1_000_000}
            if stress or scenario:
                _require(math.isclose(_number(mean['objective_mean_w']),statistics.fmean(_number(r['objective_w']) for r in group),rel_tol=2e-12,abs_tol=1e-10),
                         'Result mean objective disagrees')
                row['objective_w']=_number(mean['objective_mean_w'])
            algorithms.append(row)
        config=manifest['effective_config']
        if scenario:
            classes=int(config['host.classes'])
            effort=f'R{config["replication.count"]} · {classes} host class{"es" if classes>1 else ""} · {config["algorithms"]}'
        else:
            effort=f'N{config["population"]} / T{config["iterations"]} / R{config["replications"]}'
        if stress or scenario:
            result['overview'].append(('Experiment',f'{config["vm.count"]} VMs / {config["host.count"]} hosts · '+effort))
        else: result['overview'].append(('Experiment',profile.capitalize()+' · '+effort))
        result['overview'].append(('Master seed',str(config['master.seed'])))
        counts=f'{expected:,}/{expected:,} cases'
        if stress or scenario:
            evaluations=sum(_integer(r['evaluations']) for r in raw)
            _require(evaluations==_integer(manifest['completed_evaluations'])==_integer(manifest['expected_evaluations']),
                     'Result evaluation counts disagree')
            counts+=f' · {evaluations:,} evaluations'
        result['overview'].append(('Completed',counts))
        if all('failed_cloudlets' in r and 'censored_cloudlets' in r for r in raw):
            failures=sum(_integer(r['failed_cloudlets'])+_integer(r['censored_cloudlets']) for r in raw)
            result['overview'].append(('Cloudlets',f'{failures:,} failed or censored'))
        timing=_elapsed(metadata)
        if timing:
            if production.get('wall_seconds') is not None: timing+=f' · {_number(production["wall_seconds"]):.2f}s simulation'
            if production.get('validation_wall_seconds') is not None: timing+=f' · {_number(production["validation_wall_seconds"]):.2f}s validation'
            result['overview'].append(('Elapsed',timing))
        if execution.get('workers') is not None:
            runtime=f'{_integer(execution["workers"])} workers / {_integer(execution["shared_heap_mib"]):,} MiB shared heap'
            if production.get('sampled_peak_rss_bytes') is not None: runtime+=f' · {_number(production["sampled_peak_rss_bytes"])/1024**2:,.0f} MiB sampled peak RSS'
            result['overview'].append(('Runtime',runtime))
        result['checks'].append({'label':'Result summary','status':'PASS','detail':'Stored hashes and means agree with completed raw cases.'})
        if profile in ('stress','scenario','explore','research'):
            result['checks'].append({'label':'Descriptive analysis','status':'PASS','detail':'Observed means'+(' and sample standard deviations' if not (stress or scenario) else '')+' retained and checked.'})
        if profile=='research':
            decisions=_csv(root,run,manifest,'analysis/pairwise_primary.csv')
            expected_keys={(scenario,baseline,claim) for scenario in config['scenarios'].split(',')
                           for baseline in ('GA','FirstFit','BestFit') for claim in ('energy_benefit','sla_benefit')}
            _require(len(decisions)==len(expected_keys)==18
                     and {(r['scenario'],r['baseline'],r['claim']) for r in decisions}==expected_keys
                     and all(r['decision'] in ('CLAIM','NO_CLAIM') and r['run_id']==manifest['run_id']
                             and _integer(r['n'])==30 and r['eligible'] in ('true','false')
                             and 0<=_number(r['p_holm'])<=1 for r in decisions),
                     'Research decision matrix is incomplete')
            _require(all(r['decision']!='CLAIM' or r['eligible']=='true' and _number(r['p_holm'])<=0.05 for r in decisions),
                     'Research claim contradicts its recorded eligibility or Holm result')
            claims=sum(r['decision']=='CLAIM' for r in decisions)
            _require(claims==_integer(metadata['claims']) and 18-claims==_integer(metadata['no_claim']),
                     'Research claim counts disagree with campaign metadata')
            result['checks'].append({'label':'Hypothesis tests','status':'RECORDED_PASS',
                                     'detail':f'Recorded BCa/wild-bootstrap/Holm analysis: {claims} CLAIM / {18-claims} NO_CLAIM.'})
        if stress or scenario:
            lowest=min(r['energy_j'] for r in algorithms)
            names=', '.join(r['algorithm'] for r in algorithms if math.isclose(r['energy_j'],lowest,rel_tol=2e-12))
            result['notes'].insert(0,f'Lowest observed mean energy: {names} · {lowest/1000:,.2f} kJ.')
            if all(r['sla']==0 for r in algorithms): result['notes'].append('Zero SLA is expected for this strict static workload without contention.')
            result['notes'].append('Case time includes scalar trace writing; parallel cases overlap.')
        if metadata.get('diagnostic'): result['notes'].append('Diagnostic provenance: this is not clean-source research evidence.')
        result['algorithms']=algorithms
    except (OSError,ValueError,KeyError,TypeError,OverflowError,csv.Error) as error:
        result['overview']=[];result['algorithms']=[]
        result['checks']=[c for c in result['checks'] if c['label'] in ('Independent validation','Build verification','Release verification')
                          or c['label']=='Hypothesis tests' and c['status']=='NOT_APPLICABLE']
        result['error']='Result summary unavailable: '+str(error)
    return result
