#!/usr/bin/env python3
"""Lattora: reproducible VM placement experiments, in your terminal."""
import argparse
from contextlib import nullcontext
import importlib.metadata
import os
from pathlib import Path
import subprocess
import sys
import tarfile

import cloudsim
import lattora_context as context
import lattora_install as install

COMMANDS = 'run validate doctor update rollback uninstall completion'
PROFILES = 'smoke explore research stress scenario'


def completion(shell):
    words = COMMANDS+' '+PROFILES+' --help --version --profile --plain --output-dir --heap-mib --workers --config --dry-run --preset --vms --hosts --population --iterations --replications --seed --time-limit --check --yes'
    if shell == 'bash':
        return '_lattora() { COMPREPLY=( $(compgen -W "'+words+'" -- "${COMP_WORDS[COMP_CWORD]}") ); }\ncomplete -F _lattora lattora'
    if shell == 'zsh':
        return '#compdef lattora\n_lattora() { local -a choices; choices=('+words+'); compadd -- $choices; }\ncompdef _lattora lattora'
    return '\n'.join('complete -c lattora -f -a '+word for word in words.split())


def health(ctx):
    manifest = ctx.manifest()
    provenance = context.jar_provenance(ctx.root/'engine/app.jar')
    if (provenance.get('Implementation-Version') != manifest['version']
            or provenance.get('Git-Revision') != manifest['source_revision']
            or provenance.get('Git-Dirty') != str(manifest['source_dirty']).lower()):
        raise ValueError('Installed engine provenance mismatch')
    if sys.version_info[:3] != (3,14,8): raise ValueError('Bundled Python must be 3.14.8')
    if importlib.metadata.version('textual') != '8.2.8': raise ValueError('Bundled Textual must be 8.2.8')
    child = subprocess.run([str(ctx.java),'-version'],capture_output=True,text=True,timeout=20)
    if child.returncode or '21.0.12.1' not in child.stderr+child.stdout:
        raise ValueError('Bundled Java runtime health check failed')
    print('PASS: Lattora '+manifest['version']+'; Python, terminal UI, engine and Java runtime integrity verified.')




def _main(arguments):
    ctx = context.get_context()
    if arguments and arguments[0] in ('--version','-v','version'):
        if len(arguments) != 1: raise ValueError('Version accepts no additional arguments')
        print('Lattora '+ctx.version); return 0
    if arguments and arguments[0] in ('--help','-h'):
        print(__doc__+'\n\nUsage: lattora [COMMAND]\n\n'+
              '  lattora                       Open the terminal workbench\n'+
              '  lattora run --profile PROFILE  smoke | explore | research | stress | scenario\n'+
              '  lattora validate DIRECTORY    Independently validate retained evidence\n'+
              '  lattora doctor                Check runtime, memory and installation\n'+
              '  lattora update [VERSION]      Install a stable release (--check to inspect)\n'+
              '  lattora rollback              Activate the previously installed version\n'+
              '  lattora uninstall [--yes]     Remove app; retain results and settings\n'+
              '  lattora completion SHELL      bash | zsh | fish\n'+
              '  lattora --version             Print version\n\n'+
              'Use lattora run --help for experiment options. Updates are manual.\n'+
              'Engine: CloudSim Plus 8.5.7. Static experiments; no real datacenter saving is claimed.')
        return 0
    if arguments and arguments[0] == 'completion':
        parser = argparse.ArgumentParser(prog='lattora completion',allow_abbrev=False)
        parser.add_argument('shell',choices=('bash','zsh','fish'))
        options = parser.parse_args(arguments[1:]); print(completion(options.shell)); return 0
    if arguments and arguments[0] == 'update':
        parser = argparse.ArgumentParser(prog='lattora update',allow_abbrev=False)
        parser.add_argument('version',nargs='?'); parser.add_argument('--check',action='store_true')
        options = parser.parse_args(arguments[1:]); install.update(options.version,check=options.check); return 0
    if arguments and arguments[0] == 'rollback':
        if len(arguments) != 1: raise ValueError('Rollback accepts no additional arguments')
        print('Activated Lattora '+install.rollback()); return 0
    if arguments and arguments[0] == 'uninstall':
        parser = argparse.ArgumentParser(prog='lattora uninstall',allow_abbrev=False)
        parser.add_argument('--yes',action='store_true'); options = parser.parse_args(arguments[1:])
        if not options.yes:
            if not sys.stdin.isatty(): raise ValueError('Use --yes for noninteractive uninstall; experiments and settings are retained.')
            if input('Remove Lattora? Results and settings will be retained. [y/N] ').strip().lower() not in ('y','yes'): return 0
        install.uninstall(); return 0
    if arguments and arguments[0] == '_install':
        if not ctx.bundled: raise ValueError('Internal installation requires a standalone bundle')
        parser = argparse.ArgumentParser(allow_abbrev=False)
        parser.add_argument('--no-modify-path',action='store_true'); options = parser.parse_args(arguments[1:])
        destination = install.install_staged(ctx.root,modify_path=not options.no_modify_path)
        print('Installed Lattora '+destination.name+' at '+str(destination))
        print('Open a new terminal, or activate now: export PATH="'+str(install.command_path().parent)+':$PATH"')
        return 0
    if arguments and arguments[0] == '_health':
        if len(arguments) != 1 or not ctx.bundled: raise ValueError('Internal health check requires a standalone bundle')
        health(ctx); return 0
    with install.session_lock(ctx.root) if ctx.bundled else nullcontext():
        if ctx.bundled: ctx.manifest()
        if arguments and arguments[0] == 'doctor':
            print('Lattora '+ctx.version+' ('+('installed' if ctx.bundled else 'source')+')')
            print('Application: '+str(ctx.root)+'\nSettings: '+str(ctx.config)+'\nResults: '+str(ctx.results)+'\nCache: '+str(ctx.cache))
            if ctx.bundled: health(ctx)
            return cloudsim.main(['--check','--plain',*arguments[1:]])
        if arguments and arguments[0] == 'run':
            if not any(arg == '--profile' or arg.startswith('--profile=') for arg in arguments[1:]) and '--help' not in arguments[1:]:
                raise ValueError('Select --profile smoke|explore|research|stress|scenario; use lattora run --help.')
            return cloudsim.main(arguments[1:])
        if arguments and arguments[0] == 'validate': return cloudsim.main(['--validate',*arguments[1:]])
        if arguments and not arguments[0].startswith('-'): raise ValueError('Unknown command: '+arguments[0])
        return cloudsim.main(arguments)


def main(argv=None):
    try: return _main(list(sys.argv[1:] if argv is None else argv))
    except (OSError,ValueError,KeyError,tarfile.TarError,subprocess.SubprocessError) as error:
        print('Lattora: '+cloudsim.shared.sanitize(error),file=sys.stderr); return 1
    except KeyboardInterrupt:
        print('Lattora: interrupted; the active installation and retained results are preserved.',file=sys.stderr); return 130


if __name__ == '__main__': sys.exit(main())
