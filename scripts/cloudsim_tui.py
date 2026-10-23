"""Task-oriented terminal frontend; scientific execution stays in supervised workers."""
import asyncio
import contextlib
import io
import json
import os
from pathlib import Path
import signal
import sys
import time

from rich.table import Table
from rich.text import Text
from textual import on, work
from textual.app import App, ComposeResult
from textual.binding import Binding
from textual.containers import Horizontal, Vertical, VerticalScroll
from textual.screen import ModalScreen
from textual.theme import Theme
from textual.widgets import (Button, Checkbox, Collapsible, ContentSwitcher, DataTable,
                             Footer, Input, Label, ProgressBar, RichLog,
                             Select, Static, TabbedContent, TabPane, TextArea)

import cloudsim
import lattora_context
import lattora_install
import cloudsim_runtime as runtime
from cloudsim_results import build_result_summary
from run_validation import campaign_selection_error

ROOT = cloudsim.ROOT
PROFILE_NAMES = {'smoke':'Smoke · 4 cases', 'explore':'Explore · 40 cases',
                 'research':'Research · 450 cases', 'stress':'Static stress'}
DESCRIPTIONS = {'smoke':'A short check of the frozen protocol. No research claims.',
                'explore':'A broader frozen protocol check. No research claims.',
                'research':'The frozen main and sensitivity matrix, with independent validation.',
                'stress':'Static placement · descriptive results'}
FIELDS = ('heap', 'output', 'config-path', 'vms', 'hosts', 'population', 'iterations',
          'replications', 'seed', 'deadline')


def clean(value): return '\n'.join(cloudsim.shared.sanitize(line) for line in str(value).splitlines())


def log_tail(path, max_bytes=65536, max_lines=500):
    if not path: return ''
    try:
        with Path(path).open('rb') as stream:
            stream.seek(0, os.SEEK_END)
            stream.seek(max(0, stream.tell()-max_bytes))
            data = stream.read(max_bytes)
        return '\n'.join(clean(line) for line in data.decode('utf-8', 'replace').splitlines()[-max_lines:])
    except OSError: return ''


class Confirm(ModalScreen[bool]):
    DEFAULT_CSS = '''
    Confirm { align: center middle; background: $background 70%; }
    Confirm > Vertical { width: 90%; max-width: 64; height: auto; padding: 1 2; border: round $panel; background: $surface; }
    Confirm Static { height: auto; margin-bottom: 1; }
    Confirm Horizontal { height: 3; }
    Confirm Button { width: 1fr; margin-right: 1; }
    '''
    BINDINGS = [('escape', 'back', 'Back')]

    def __init__(self, message, action):
        super().__init__(); self.message, self.action_name = message, action

    def compose(self):
        with Vertical():
            yield Static(self.message, markup=False)
            with Horizontal():
                yield Button('Keep working', id='back')
                yield Button(self.action_name, variant='error', id='confirm')

    def on_mount(self): self.query_one('#back').focus()
    def action_back(self): self.dismiss(False)
    def on_button_pressed(self, event): self.dismiss(event.button.id == 'confirm')


class LogView(ModalScreen):
    DEFAULT_CSS = '''
    LogView { align: center middle; background: $background 70%; }
    LogView > Vertical { width: 94%; height: 90%; padding: 1; border: round $panel; background: $surface; }
    LogView Static { height: auto; max-height: 3; }
    LogView TextArea { height: 1fr; }
    LogView Button { height: 3; }
    '''
    BINDINGS = [('escape', 'close', 'Close')]

    def __init__(self, title, text): super().__init__(); self.title_text, self.text = title, text
    def compose(self):
        with Vertical():
            yield Static(clean(self.title_text), markup=False)
            yield TextArea(self.text, read_only=True, show_line_numbers=False)
            yield Button('Close', id='close')
    def action_close(self): self.dismiss()
    def on_button_pressed(self, event): self.dismiss()


class CloudSimApp(App):
    TITLE = 'Lattora'
    SUB_TITLE = 'Experiment workbench'
    ENABLE_COMMAND_PALETTE = False
    CSS = '''
    Screen { background: $background; color: $foreground; }
    #masthead { height: 2; padding: 0 2; background: $surface; align-vertical: middle; }
    #brand { width: 27; height: 1; text-style: bold; }
    #readiness { width: 1fr; height: auto; max-height: 2; padding-right: 2; text-align: right; color: $text-muted; }
    #theme { width: 12; }
    #theme > SelectCurrent { background: $surface; color: $primary; }
    #readiness.ready { color: $success; }
    #readiness.blocked { color: $warning; }
    Footer { background: $surface; color: $text-muted; }
    FooterKey { background: $surface; color: $primary; }
    FooterLabel { background: $surface; color: $text-muted; }
    TabbedContent { height: 1fr; padding: 0 1; }
    Tabs { margin: 0 1; }
    Tab { padding: 0 2; color: $text-muted; }
    Tab.-active { color: $primary; background: $background; text-style: bold; }
    Tabs:focus Tab.-active { color: $primary; background: $surface; }
    Underline > .underline--background { background: $panel; }
    Underline > .underline--bar { color: $primary; background: $panel; }
    TabPane { height: 1fr; padding: 0 2; }
    ContentSwitcher, #configure, #progress, #result { height: 1fr; }
    ContentSwitcher { align-horizontal: center; }
    #configure, #progress, #result { width: 100%; max-width: 112; }
    #configure-body { height: 1fr; }
    VerticalScroll { height: 1fr; scrollbar-size: 1 1; }
    #form { width: 3fr; padding-right: 3; }
    #plan { width: 2fr; padding: 0 0 0 3; margin: 1 0 0 0; border-left: solid $panel; }
    .heading { height: auto; margin: 1 0 0 0; text-style: bold; color: $foreground; }
    #form > .heading { display: none; }
    .section-title { height: auto; margin: 1 0 0 0; text-style: bold; }
    Label { height: 1; margin: 0; color: $text-muted; }
    Collapsible Label, #tools Label, #setup Label { margin-top: 1; }
    Input { height: 1; padding: 0 1; background: $surface; }
    Input:focus { background: $primary 18%; color: $foreground; }
    Input.-invalid { background: $error 18%; }
    Input > .input--placeholder { color: $text-muted; }
    Select { height: 1; margin: 0; }
    Select > SelectCurrent { height: 1; padding: 0 1; background: $surface; }
    Select:focus > SelectCurrent { background: $primary 18%; }
    Select > SelectOverlay { border: round $panel; background: $surface; }
    SelectOverlay > .option-list--option-highlighted { background: $primary 22%; color: $foreground; }
    .pair { height: auto; margin: 1 0 0 0; }
    #profile-row, #search-row, #runtime-row { margin-top: 0; }
    .pair > Vertical { height: auto; width: 1fr; padding-right: 1; }
    .pair > Vertical:last-child { padding-right: 0; }
    .locked-value { height: 1; text-style: bold; }
    .muted, #run-description, #preset-note, #frozen-scope, #summary, #plan-copy { color: $text-muted; height: auto; }
    #run-description { margin: 0; }
    #preset-note { display: none; }
    #frozen-scope { margin: 1 0 0 0; }
    #summary { margin: 1 0; }
    #plan-copy { display: none; }
    .actions { height: 3; margin: 1 0 0 0; }
    .actions Button { width: 1fr; min-width: 10; margin-right: 1; }
    Button { height: 3; border: none; background: $surface; color: $foreground; text-style: none; }
    Button:hover { background: $panel; border: none; }
    Button:focus { text-style: bold reverse; }
    Button.-primary { background: $primary; color: $background; text-style: bold; border: none; }
    Button.-primary:hover { background: $primary-lighten-1; border: none; }
    Button.-error { background: $error 15%; color: $error; border: none; }
    Button.-error:hover { background: $error 25%; border: none; }
    #start { width: 24; }
    #start-hint { width: 1fr; height: 3; content-align: left middle; color: $text-muted; }
    #fix-setup { width: 14; }
    #form-error { height: auto; max-height: 3; padding: 0 1; background: $error 10%; color: $error; display: none; }
    #setup-status, #setup-copy, #result-details, #stage-detail, #measurements { height: auto; }
    #outcome, #stage-title { height: auto; text-style: bold; margin-top: 1; }
    #outcome.success { color: $success; }
    #outcome.failure { color: $error; }
    #result-details, #stage-detail { margin: 1 0; }
    #result-details { margin: 0; color: $text-muted; }
    #outcome.summary-unavailable { color: $warning; }
    #result-body { height: auto; margin-top: 1; }
    #result-main { height: auto; width: 3fr; padding-right: 3; }
    #result-checks { height: auto; width: 2fr; padding-left: 3; border-left: solid $panel; }
    #result-overview, #algorithm-means, #check-details, #result-notes, #result-path, #result-more-facts { height: auto; }
    #result-checks > .section-title { margin: 0 0 1 0; }
    #algorithm-title { margin-bottom: 1; }
    #result-notes { margin-top: 1; color: $text-muted; }
    #result-path { color: $text-muted; }
    #result > .actions { dock: bottom; margin-top: 0; }
    #stage-detail { max-height: 4; }
    #measurements { color: $text-muted; }
    #runtime-note { height: auto; max-height: 2; color: $text-muted; }
    ProgressBar { height: 1; margin: 1 0; }
    #live-log { height: 1fr; min-height: 1; min-width: 0; padding: 0 1; background: $surface; }
    #progress > .actions { dock: bottom; margin: 0; }
    #recent { height: 1fr; min-height: 5; margin-top: 1; }
    #results-detail { height: auto; max-height: 4; color: $text-muted; }
    #setup-copy { margin: 1 0; color: $text-muted; }
    Collapsible { padding: 0 0 1 0; border: none; margin-top: 1; background: $background; }
    Collapsible:focus-within { background-tint: $foreground 0%; }
    Collapsible > Contents { padding: 0 1; }
    Checkbox { height: auto; margin-top: 1; border: none; background: $background; }
    #stress-settings, #stress-seed, #frozen-settings, #frozen-config { height: auto; }
    Screen.narrow #configure-body { layout: vertical; }
    Screen.narrow #form { width: 100%; padding-right: 0; }
    Screen.narrow #plan { display: none; }
    Screen.narrow #result-body { layout: vertical; margin-top: 0; }
    Screen.narrow #result-main, Screen.narrow #result-checks { width: 100%; padding: 0; border: none; }
    Screen.narrow #result-checks { margin-top: 1; }
    Screen.narrow #algorithm-title { margin: 0; }
    Screen.compact #brand { width: 12; }
    Screen.compact #plan, Screen.compact #start-hint { display: none; }
    Screen.compact TabPane { padding: 0 1; }
    Screen.compact Tab { padding: 0 1; }
    Screen.compact #run-description { display: none; }
    Screen.compact #form .pair, Screen.compact #form .section-title { margin-top: 0; }
    Screen.compact #configure > .actions { margin: 0; }
    Screen.compact #outcome, Screen.compact #result-body { margin-top: 0; }
    Screen.compact #algorithm-title { margin: 0; }
    Screen.compact #stage-title { margin: 0; }
    Screen.compact #stage-detail { max-height: 3; margin: 0 0 1 0; }
    Screen.compact ProgressBar { margin: 0 0 1 0; }
    '''
    BINDINGS = [('ctrl+n', 'edit', 'New run'), ('ctrl+c', 'cancel', 'Cancel'),
                ('ctrl+q', 'quit_requested', 'Quit'), ('f1', 'help', 'Help'),
                Binding('f2', "tab('run')", 'Run', show=False), Binding('f3', "tab('results')", 'Results', show=False),
                Binding('f4', "tab('tools')", 'Tools', show=False), Binding('f5', "tab('setup')", 'Setup', show=False),
                Binding('ctrl+r', 'start', 'Start'), Binding('q', 'quit_requested', 'Quit', show=False),
                Binding('ctrl+1', "tab('run')", 'Run', show=False), Binding('ctrl+2', "tab('results')", 'Results', show=False),
                Binding('ctrl+3', "tab('tools')", 'Tools', show=False), Binding('ctrl+4', "tab('setup')", 'Setup', show=False)]

    def __init__(self, output_parent=None):
        super().__init__()
        self.context = lattora_context.get_context(ROOT)
        self.preferences = {}
        self.preferences_error = None
        if self.context.bundled and self.context.config.exists():
            try: self.preferences = lattora_context.load_json(self.context.config)
            except ValueError as error: self.preferences_error = str(error)
        self.output_parent = Path(output_parent or self.context.results).resolve()
        self.ready = False
        self.busy = True
        self.process = None
        self.child_pid = None
        self.child_identity = None
        self.pending_java = None
        self.exit_after_job = False
        self.exit_code = 0
        self.java_override = None
        self.current_profile = 'smoke'
        self.saved = {}
        self.preset_previous = 'small'
        self.last_args = []
        self.last_action = 'profile'
        self.outcome = {}
        self.result_summary = None
        self.outcome_log = None
        self.active_directory = None
        self.recent = {}
        self.log_path = None
        self.log_text = ''
        self.follow = True
        self.started = None
        self.progress = {}
        self.stage = ''
        self.signal_handlers = {}
        self.register_theme(Theme(name='cloudsim', primary='#99d8c1', secondary='#a6b9bd',
            accent='#99d8c1', foreground='#e3ebec', background='#12191c', surface='#1e292e',
            panel='#35454c', success='#99d8c1', warning='#e7c58d', error='#edaaa9', dark=True,
            variables={'text-muted':'#a6b9bd'}))
        self.theme = 'cloudsim'
        self.register_theme(Theme(name='cloudsim-ember', primary='#e8b18d', secondary='#c6b2a4',
            accent='#e8b18d', foreground='#f0e8e2', background='#211b19', surface='#302622',
            panel='#504039', success='#b2cfad', warning='#e9c77a', error='#efaaa4', dark=True,
            variables={'text-muted':'#c6b2a4'}))
        self.register_theme(Theme(name='cloudsim-paper', primary='#335e57', secondary='#5f706c',
            accent='#335e57', foreground='#293a36', background='#f4f1e8', surface='#e5e6da',
            panel='#c4cdc1', success='#335e57', warning='#785720', error='#983d39', dark=False,
            variables={'text-muted':'#5f706c'}))

    def field(self, title, name, value=''):
        yield Label(title)
        yield Input(value, id=name, compact=True)

    def compose(self) -> ComposeResult:
        with Horizontal(id='masthead'):
            yield Static('Lattora  /  Workbench', id='brand', markup=False)
            yield Static('Checking setup…', id='readiness', markup=False)
            yield Select([('Harbor','cloudsim'),('Ember','cloudsim-ember'),('Paper','cloudsim-paper')],
                         value=self.preferences.get('theme','cloudsim') if self.preferences.get('theme','cloudsim') in ('cloudsim','cloudsim-ember','cloudsim-paper') else 'cloudsim', allow_blank=False, id='theme', compact=True, tooltip='Color theme · appearance only')
        with TabbedContent(initial='run'):
            with TabPane('Run', id='run'):
                with ContentSwitcher(initial='configure', id='flow'):
                    with Vertical(id='configure'):
                        with Horizontal(id='configure-body'):
                            with VerticalScroll(id='form'):
                                yield Static('New experiment', classes='heading')
                                with Horizontal(classes='pair', id='profile-row'):
                                    with Vertical():
                                        yield Label('Profile')
                                        yield Select([(v,k) for k,v in PROFILE_NAMES.items()], value='smoke', allow_blank=False, id='profile', compact=True)
                                    with Vertical(id='preset-cell'):
                                        yield Label('Size preset')
                                        yield Select([(f'{k.title()} · {v:,} VMs',k) for k,(v,h) in cloudsim.stress.PRESETS.items()],
                                                     value='small', allow_blank=False, id='preset', compact=True,
                                                     tooltip='Runs only the selected full campaign. Pilots use up to 100 VMs, 500 VMs and the selected size; hosts scale to the selected VM/host ratio. Pilot effort: N10/T10/R1.')
                                yield Static(DESCRIPTIONS['smoke'], id='run-description', markup=False)
                                with Vertical(id='stress-settings'):
                                    with Horizontal(classes='pair'):
                                        with Vertical(): yield from self.field('Virtual machines', 'vms', '500')
                                        with Vertical(): yield from self.field('Hosts', 'hosts', '100')
                                    yield Static('One full campaign at this size. Low-effort calibrations run first.', id='preset-note', markup=False)
                                    yield Static('Search effort', classes='section-title')
                                    with Horizontal(classes='pair', id='search-row'):
                                        with Vertical(): yield from self.field('Population N', 'population', '30')
                                        with Vertical(): yield from self.field('Iterations T', 'iterations', '40')
                                        with Vertical(): yield from self.field('Replicates R', 'replications', '5')
                                        with Vertical(): yield from self.field('Master seed', 'seed', '123456')
                                with Vertical(id='frozen-settings'):
                                    yield Static('Frozen protocol · read-only', classes='section-title')
                                    with Horizontal(classes='pair'):
                                        for title, name in (('Population (N)','locked-population'),('Iterations (T)','locked-iterations'),('Replications (R)','locked-replications')):
                                            with Vertical():
                                                yield Label(title)
                                                yield Static('', id=name, classes='locked-value', markup=False)
                                    yield Static('', id='frozen-scope', markup=False)
                                yield Static('Runtime', classes='section-title')
                                with Horizontal(classes='pair', id='runtime-row'):
                                    with Vertical():
                                        yield Label('Case workers')
                                        yield Select([('Auto','auto'), *[(str(i),str(i)) for i in range(1,33)]],
                                                     value='auto', allow_blank=False, id='workers', compact=True,
                                                     tooltip='Independent cases run concurrently within the CPU and shared Java heap limits. Search settings stay unchanged.')
                                    with Vertical(): yield from self.field('Java heap · MiB', 'heap', str(self.preferences.get('heap_mib',1024)))
                                    with Vertical(id='stress-deadline'): yield from self.field('Deadline', 'deadline', '2h')
                                yield Static('', id='runtime-note', markup=False)
                                with Collapsible(title='Files' if self.context.bundled else 'Files & build options', id='advanced'):
                                    yield from self.field('Save results under', 'output', str(self.output_parent))
                                    with Vertical(id='frozen-config'):
                                        yield from self.field('Properties file · seed / log level only', 'config-path')
                                    yield Checkbox('Force fresh build and tests', id='force-build')
                                    yield Checkbox('Skip build / tests · diagnostic', id='skip-build')
                            with VerticalScroll(id='plan'):
                                yield Static('Run plan', classes='heading')
                                yield Static('', id='summary', markup=False)
                                yield Static('Every run retains its inputs, logs and validation evidence.\n\nSelect a profile, review the plan, then start.', id='plan-copy', markup=False)
                        yield Static('', id='form-error', markup=False)
                        with Horizontal(classes='actions'):
                            yield Static('Ctrl+R to start · Tab to move', id='start-hint', markup=False)
                            yield Button('Start experiment', variant='primary', id='start', disabled=True)
                            yield Button('Fix setup', id='fix-setup')
                    with Vertical(id='progress'):
                        yield Static('', id='stage-title', markup=False)
                        yield Static('', id='measurements', markup=False)
                        yield ProgressBar(total=None, show_eta=False, id='bar')
                        yield Static('', id='stage-detail', markup=False)
                        yield RichLog(max_lines=500, min_width=0, wrap=True, id='live-log')
                        with Horizontal(classes='actions'):
                            yield Button('Pause log', id='follow')
                            yield Button('Cancel job', variant='error', id='cancel-job')
                    with Vertical(id='result'):
                        with VerticalScroll(id='result-scroll'):
                            yield Static('', id='outcome', markup=False)
                            yield Static('', id='result-details', markup=False)
                            with Horizontal(id='result-body'):
                                with Vertical(id='result-main'):
                                    yield Static('', id='result-overview')
                                    yield Static('Algorithm means · production cases', id='algorithm-title', classes='section-title', markup=False)
                                    yield Static('', id='algorithm-means')
                                    with Collapsible(title='Experiment details', id='result-facts'):
                                        yield Static('', id='result-more-facts')
                                    yield Static('', id='result-notes', markup=False)
                                with Vertical(id='result-checks'):
                                    yield Static('Analysis & checks', classes='section-title', markup=False)
                                    yield Static('', id='check-details')
                            with Collapsible(title='Saved evidence', id='result-evidence'):
                                yield Static('', id='result-path', markup=False)
                                with Collapsible(title='Commands and raw metadata', id='result-raw'):
                                    yield Static('', id='metadata', markup=False)
                        with Horizontal(classes='actions'):
                            yield Button('Edit', id='edit')
                            yield Button('Retry', id='retry')
                            yield Button('Setup', id='result-setup')
                            yield Button('View log', id='view-log')
            with TabPane('Results', id='results'):
                yield Static('Run history', classes='heading')
                yield Static('Select an experiment to inspect its evidence or rerun validation.', classes='muted', markup=False)
                yield DataTable(id='recent', cursor_type='row', zebra_stripes=True)
                yield Static('', id='results-detail', markup=False)
                with Horizontal(classes='actions'):
                    yield Button('Refresh', id='refresh-results')
                    yield Button('Details', id='result-details-button')
                    yield Button('Validate selected', id='validate-selected')
            with TabPane('Tools', id='tools'):
                with VerticalScroll():
                    yield Static('Runtime & validation' if self.context.bundled else 'Project tools', classes='heading')
                    yield Static(('Lattora '+self.context.version+' · verified release engine. Local tests: NOT_RUN.' if self.context.bundled else 'Checks and build logs are retained.')+' Validation preserves the selected evidence.', markup=False)
                    with Horizontal(classes='actions'):
                        yield Button('Check setup', id='check')
                        yield Button('Build', id='build')
                        yield Button('Run tests', id='test')
                    yield from self.field('Existing run or campaign directory', 'validate-path')
                    yield Button('Validate directory', variant='primary', id='validate')
            with TabPane('Setup', id='setup'):
                with VerticalScroll():
                    yield Static('Environment setup', classes='heading')
                    yield Static('Checking Java 21 and usable memory…', id='setup-status', markup=False)
                    yield Static('Install a full JDK 21 inside this project, or select one you already have. '
                                 'The download is checksum-verified. System Java and shell settings are preserved.',
                                 id='setup-copy', markup=False)
                    yield Button('Install local JDK 21 (198 MiB)', variant='primary', id='install-jdk')
                    if not self.context.bundled: yield from self.field('Installed JDK 21 directory', 'jdk-path')
                    else: yield Input('',id='jdk-path', classes='hidden')
                    with Horizontal(classes='actions'):
                        yield Button('Use this JDK', id='select-jdk')
                        yield Button('Check again', id='check-again')
        yield Footer()

    def on_mount(self):
        if self.context.bundled:
            for selector in ('#build','#test','#force-build','#skip-build','#install-jdk','#select-jdk','#jdk-path'):
                self.query_one(selector).display = False
            self.query_one('#setup-copy', Static).update('Python, the terminal UI, engine and Java runtime are bundled. Updates are manual: lattora update.\nResults: '+str(self.context.results)+'\nSettings: '+str(self.context.config))
        if self.preferences_error: self.notify(self.preferences_error+'; settings were preserved.', severity='warning')
        self.query_one('#recent', DataTable).add_columns('Run / action', 'Started (UTC)', 'Outcome', 'Validation')
        self.profile_visibility()
        self.responsive_layout()
        self.update_summary()
        # One opening reveal; input and readiness work begin immediately. Textual's
        # reduced-animation setting and NO_COLOR keep the initial screen static.
        if self.animation_level == 'full' and not os.environ.get('NO_COLOR'):
            for selector, initial, duration in (('#brand', .45, .18), ('#configure-body', .75, .22)):
                widget = self.query_one(selector)
                widget.styles.opacity = initial
                widget.styles.animate('opacity', 1.0, duration=duration, easing='out_expo', level='full')
        self.set_interval(1, self.tick)
        loop = asyncio.get_running_loop()
        for number in (signal.SIGTERM, signal.SIGINT):
            self.signal_handlers[number] = signal.getsignal(number)
            loop.add_signal_handler(number, self.signal_stop, number)
        self.check_readiness()

    def responsive_layout(self, size=None):
        size = size or self.size
        screen = self.screen_stack[0]
        screen.set_class(size.width < 100, 'narrow')
        screen.set_class(size.width < 74 or size.height < 22, 'compact')
        self.query_one('#brand', Static).update('Lattora' if screen.has_class('compact') else 'Lattora  /  Workbench')

    def on_resize(self, event):
        if self.is_mounted:
            self.responsive_layout(event.size)
            if self.result_summary is not None: self.render_result_summary(self.result_summary)

    @on(Select.Changed, '#theme')
    def change_theme(self, event):
        if event.value is not Select.NULL:
            self.theme = str(event.value)
            if self.context.bundled and not self.preferences_error:
                self.preferences['theme'] = self.theme
                self.save_preferences()

    def save_preferences(self):
        try:
            self.context.config.parent.mkdir(parents=True, exist_ok=True)
            lattora_install._atomic_json(self.context.config,self.preferences)
        except OSError as error: self.notify('Could not save settings: '+clean(error),severity='warning')

    def set_busy(self, busy):
        self.busy = busy
        for name in ('check','build','test','validate','check-again','install-jdk','select-jdk',
                     'validate-selected','result-details-button','retry','edit'):
            self.query_one('#'+name, Button).disabled = busy
        self.query_one('#start', Button).disabled = busy or not self.ready
        self.update_result_selection()

    def signal_stop(self, number):
        if number == signal.SIGTERM: self.exit_after_job = True; self.exit_code = 143
        if self.busy and self.process and self.process.returncode is None: self.process.send_signal(number)
        elif number == signal.SIGTERM: self.exit(143)

    async def on_unmount(self):
        await self.stop_worker()
        loop = asyncio.get_running_loop()
        for number, handler in self.signal_handlers.items():
            loop.remove_signal_handler(number); signal.signal(number, handler)

    async def stop_worker(self):
        process = self.process
        if not process: return
        if process.returncode is None:
            try: process.send_signal(signal.SIGTERM)
            except ProcessLookupError: pass
            try: await asyncio.wait_for(process.wait(), 8)
            except asyncio.TimeoutError:
                self.stop_owned_child()
                process.kill(); await process.wait()
        self.stop_owned_child()

    def track_child(self, event):
        self.child_pid = event.get('pid')
        self.child_identity = None
        if not self.child_pid or not self.process: return
        identity = event.get('identity') or runtime.owned_child_identity(self.child_pid, self.process.pid)
        if identity and identity.get('pid')==self.child_pid and identity.get('parent')==self.process.pid:
            self.child_identity = identity

    def stop_owned_child(self):
        identity = self.child_identity
        runtime.stop_owned_child(identity)
        self.child_identity = None

    def action_tab(self, tab): self.query_one(TabbedContent).active = tab
    def action_setup(self): self.action_tab('setup')
    def action_edit(self):
        if self.busy: return
        self.action_tab('run')
        self.query_one('#flow', ContentSwitcher).current = 'configure'
        self.query_one('#profile').focus()

    def action_help(self):
        self.push_screen(LogView('Lattora help',
            'Run: choose a profile and start. Tab moves between controls; Enter activates; arrow keys select.\n'
            'Stress size, population N, iterations T, replications R and seed are editable on the main form.\n'
            'Frozen profiles display their fixed protocol. Workers and shared heap change runtime only.\n'
            'Files & build options hold output, seed properties, force build and diagnostic skip build.\n'
            'The header theme selector changes appearance. TEXTUAL_ANIMATIONS=none disables the opening reveal.\n'
            'F2 / F3 / F4 / F5: Run / Results / Tools / Setup. Ctrl+N: edit or start another run.\n'
            'Ctrl+C: confirm cancellation. Ctrl+Q: quit. Esc closes a dialog.\n'
            'Results are validated independently. Build/test success is a separate status.\n'
            'CLI automation: '+('lattora run --help' if self.context.bundled else './lattora.sh run --help')))

    def action_quit_requested(self):
        if not self.busy: self.exit(0); return
        def decision(confirmed):
            if confirmed:
                self.exit_after_job = True
                if self.process and self.process.returncode is None: self.process.send_signal(signal.SIGTERM)
        self.push_screen(Confirm('A job is running. Cancel it and close Lattora?', 'Cancel & quit'), decision)

    def action_cancel(self):
        if not self.busy or not self.process: return
        self.push_screen(Confirm('Cancel this job? Partial results and logs will be kept.', 'Cancel job'),
                         lambda confirmed: self.process.send_signal(signal.SIGINT) if confirmed and self.process and self.process.returncode is None else None)

    @on(Select.Changed, '#profile')
    def change_profile(self, event):
        if event.value == self.current_profile: return
        self.saved[self.current_profile] = {name:self.query_one('#'+name, Input).value for name in FIELDS}
        self.saved[self.current_profile].update(preset=str(self.query_one('#preset', Select).value),
            workers=str(self.query_one('#workers', Select).value), skip=self.query_one('#skip-build', Checkbox).value,
            force=self.query_one('#force-build', Checkbox).value)
        self.current_profile = str(event.value)
        values = self.saved.get(self.current_profile)
        if values:
            self.preset_previous = values['preset']
            self.query_one('#preset', Select).value = values['preset']
            self.query_one('#workers', Select).value = values['workers']
            self.query_one('#skip-build', Checkbox).value = values['skip']
            self.query_one('#force-build', Checkbox).value = values['force']
            for name in FIELDS: self.query_one('#'+name, Input).value = values[name]
        self.profile_visibility(); self.update_summary()

    @on(Select.Changed, '#preset')
    def change_preset(self, event):
        if event.value == self.preset_previous: return
        self.preset_previous = str(event.value)
        vms, hosts = cloudsim.stress.PRESETS[self.preset_previous]
        self.query_one('#vms', Input).value = str(vms)
        self.query_one('#hosts', Input).value = str(hosts)
        self.update_summary()

    @on(Select.Changed, '#workers')
    def change_workers(self, event): self.update_summary()
    def on_input_changed(self, event):
        if event.input.id in FIELDS: self.update_summary()
    def on_checkbox_changed(self, event):
        # These modes are mutually exclusive in the CLI, so choosing one replaces
        # the other immediately instead of leaving an impossible form state.
        if event.value and event.checkbox.id in ('skip-build','force-build'):
            other = 'force-build' if event.checkbox.id == 'skip-build' else 'skip-build'
            self.query_one('#'+other, Checkbox).value = False
        self.update_summary()

    def profile_visibility(self):
        stress = self.current_profile == 'stress'
        self.query_one('#stress-settings').display = stress
        self.query_one('#preset-cell').display = stress
        self.query_one('#stress-deadline').display = stress
        self.query_one('#frozen-settings').display = not stress
        self.query_one('#frozen-config').display = not stress
        self.query_one('#run-description', Static).update(DESCRIPTIONS[self.current_profile])
        if not stress:
            n,t,r,scenarios = cloudsim.FROZEN_SETTINGS[self.current_profile]
            for name,value in (('population',n),('iterations',t),('replications',r)):
                self.query_one('#locked-'+name, Static).update(str(value))
            self.query_one('#frozen-scope', Static).update('Scenarios: '+scenarios.replace(',',', '))

    def arguments(self):
        get = lambda name: self.query_one('#'+name, Input).value
        arguments = ['--profile', self.current_profile, '--heap-mib', get('heap'), '--output-dir', get('output'),
                     '--workers', str(self.query_one('#workers', Select).value), '--plain']
        if self.query_one('#skip-build', Checkbox).value: arguments.append('--skip-build')
        if self.query_one('#force-build', Checkbox).value: arguments.append('--force-build')
        if self.current_profile == 'stress':
            arguments += ['--preset', str(self.query_one('#preset', Select).value), '--vms', get('vms'),
                          '--hosts', get('hosts'), '--seed', get('seed'), '--time-limit', get('deadline')]
            for name in ('population','iterations','replications'): arguments += ['--'+name, get(name)]
        elif get('config-path'): arguments += ['--config', get('config-path')]
        return arguments

    def parsed(self, arguments):
        errors = io.StringIO()
        with contextlib.redirect_stderr(errors):
            try: return cloudsim.parse_args(arguments)
            except SystemExit:
                raise ValueError(errors.getvalue().split('error:')[-1].strip()) from None

    def update_summary(self):
        if not self.is_mounted: return
        try:
            options = self.parsed(self.arguments())
            preview = cloudsim.preview(options)
            execution = preview['execution']
            build = 'Verified release engine; local build/tests NOT_RUN' if self.context.bundled else 'Diagnostic: build / tests skipped' if options.skip_build else 'Fresh build + tests requested' if options.force_build else 'Reuse verified build; rebuild if changed'
            if self.current_profile == 'stress':
                plan = preview['planned_work']
                work = plan['combined_totals']['expected_cases']
                pilots = cloudsim.stress.calibrations(options.stress_options)
                lines = [f'Full campaign · {plan["production"]["expected_cases"]} cases',
                         f'{options.vms:,} VMs / {options.hosts:,} hosts', 'At this size only', '', 'Calibrations']
                lines += [f'{p.vms:,} VMs / {p.hosts:,} hosts' for p in pilots]
                lines += ['N10 / T10 / R1 · one worker', '', f'{work} total cases · then validation', '']
            else:
                work = cloudsim.shared.profile_total(self.current_profile)
                lines = [f'{work} cases · frozen protocol', 'Independent validation follows.', '']
                seed = preview['effective_config'].get('master.seed','protocol default')
                scenarios = cloudsim.FROZEN_SETTINGS[self.current_profile][3].replace(',',', ')
                self.query_one('#frozen-scope', Static).update(f'Scenarios: {scenarios}\nMaster seed: {seed}')
            lines += ['Runtime', f'{execution["workers"]} workers · {options.heap_mib:,} MiB shared heap']
            if self.current_profile == 'stress':
                lines += ['Deadline: '+self.query_one('#deadline', Input).value+' · after build/tests']
            lines += ['', 'Release verification' if self.context.bundled else 'Build and tests', build]
            self.query_one('#runtime-note', Static).update(
                f'Auto → {execution["workers"]} case workers · CPU / shared heap bound' if str(options.workers)=='auto'
                else f'{execution["workers"]} case workers · shared heap across all workers')
            self.query_one('#start-hint', Static).update(f'{work} cases · Ctrl+R to start')
            if preview['warnings']: lines += ['', *preview['warnings']]
            text = '\n'.join(lines)
        except (ValueError, OSError, KeyError): text = 'Complete the settings to preview the work. Start will check every value.'
        self.query_one('#summary', Static).update(clean(text))

    async def action_start(self): await self.start_action()
    async def start_action(self):
        if self.busy: return
        arguments = self.arguments()
        try: self.parsed(arguments)
        except (ValueError, OSError) as error:
            box = self.query_one('#form-error', Static); box.update(clean(error)); box.display = True
            flags = {'heap-mib':'heap', 'output-dir':'output', 'time-limit':'deadline', 'config':'config-path',
                     **{name:name for name in ('vms','hosts','seed','population','iterations','replications')}}
            message = str(error)
            # argparse names a bad CLI input as "argument --...". Properties
            # content errors come from the frozen config reader and may mention
            # master.seed or even a property named heap: those belong to the file,
            # never to a hidden stress control or an unrelated runtime input.
            if self.current_profile != 'stress' and self.query_one('#config-path', Input).value and not message.startswith('argument --'):
                field = 'config-path'
            else:
                field = next((name for flag,name in flags.items() if flag in message), 'heap')
            if field in ('output','config-path'): self.query_one('#advanced', Collapsible).collapsed = False
            self.query_one('#'+field, Input).focus(); return
        if not self.ready: self.action_setup(); return
        self.query_one('#form-error').display = False
        if self.context.bundled and not self.preferences_error:
            options = self.parsed(arguments)
            self.preferences.update(heap_mib=options.heap_mib)
            self.save_preferences()
        self.last_args = arguments; self.last_action = 'profile'
        self.begin(arguments)

    def begin(self, arguments):
        if self.busy: return
        self.set_busy(True)
        self.action_tab('run')
        self.query_one('#flow', ContentSwitcher).current = 'progress'
        self.started = time.monotonic(); self.stage = 'preflight'; self.progress = {}
        self.log_path = None; self.log_text = ''; self.follow = True
        self.query_one('#live-log', RichLog).clear()
        self.query_one('#follow', Button).label = 'Pause log'
        self.run_job(arguments)

    def check_readiness(self):
        if self.process and self.process.returncode is None: return
        self.set_busy(True)
        self.ready = False
        self.query_one('#start', Button).disabled = True
        self.query_one('#readiness', Static).update('Checking Java 21 and usable memory…')
        self.run_job(['--check','--plain','--output-dir',str(self.output_parent)], readiness=True)

    @work(exclusive=True, group='execution', exit_on_error=False)
    async def run_job(self, arguments, readiness=False):
        self.set_busy(True)
        self.active_directory = None
        stderr = bytearray(); result = None
        env = dict(os.environ)
        if self.java_override: env['JAVA_HOME'] = str(self.java_override)
        try:
            self.process = await asyncio.create_subprocess_exec(sys.executable, '-B', str(ROOT/'scripts/cloudsim_worker.py'),
                *arguments, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE,
                stdin=asyncio.subprocess.DEVNULL, env=env, limit=1024*1024)
            async def errors():
                while chunk := await self.process.stderr.read(8192):
                    stderr.extend(chunk); del stderr[:-65536]
            error_task = asyncio.create_task(errors())
            try:
                async for line in self.process.stdout:
                    event = json.loads(line)
                    kind = event.get('event')
                    if kind == 'progress' and not readiness:
                        self.stage = event['stage']; self.progress = event.get('progress', {}); self.tick()
                    elif kind == 'child': self.track_child(event)
                    elif kind == 'log':
                        self.active_directory = Path(event['output_directory']) if event.get('output_directory') else self.active_directory
                        if not readiness: self.log_path = Path(event['path'])
                    elif kind == 'complete': result = event['metadata']
                code = await self.process.wait()
                if code < 0: code = 128-code
                await self.stop_worker()
            finally:
                if self.process.returncode is None: error_task.cancel()
                await asyncio.gather(error_task, return_exceptions=True)
            if not result:
                result = self.failed_worker(code or 1, 'Runner completion is missing. '+clean(stderr.decode('utf-8','replace')))
            if code != result.get('exit_code'):
                result.update(status='failed', exit_code=code or 1, validation='NOT_RUN',
                              error='Runner exit and completion record disagree. Inspect the retained logs.')
        except asyncio.CancelledError:
            await self.stop_worker(); raise
        except Exception as error:
            await self.stop_worker()
            result = self.failed_worker(1, clean(error))
        finally:
            self.set_busy(False); self.child_pid = None; self.child_identity = None
        self.record_result(result)
        if readiness:
            self.ready = result.get('status')=='checked' and result.get('exit_code')==0
            if self.pending_java:
                if self.ready: runtime.save_java_home(self.java_override)
                else: self.java_override = self.pending_java[0]
                self.pending_java = None
            message = ('Ready · Java 21 · '+str(result.get('usable_memory_bytes',0)//cloudsim.shared.MIB)+' MiB usable') if self.ready else 'Setup needs attention · open Setup'
            banner = self.query_one('#readiness', Static); banner.update(message)
            banner.set_class(self.ready, 'ready'); banner.set_class(not self.ready, 'blocked')
            self.query_one('#setup-status', Static).update(clean(result.get('error') or
                'Java 21 is ready.\nSelected Java: '+str(result.get('java'))+'\nUsable memory: '+str(result.get('usable_memory_bytes',0)//cloudsim.shared.MIB)+' MiB'))
            self.query_one('#start', Button).disabled = not self.ready
            self.query_one('#fix-setup').display = not self.ready
        else:
            self.show_result(result)
            self.query_one('#start', Button).disabled = not self.ready
            if result.get('action') == 'setup' and result.get('exit_code')==0:
                self.pending_java = (self.java_override,)
                self.java_override = ROOT/'.cloudsim/jdk'
                self.call_later(self.check_readiness)
        if self.exit_after_job: self.exit(self.exit_code)

    def failed_worker(self, code, error):
        result = {}
        directory = self.active_directory
        if directory:
            path = directory/'runner.json'
            try:
                if path.stat().st_size <= 1024*1024:
                    saved = json.loads(path.read_text())
                    if isinstance(saved,dict): result = saved
            except (OSError,ValueError): pass
            result['output_directory'] = str(directory)
        result.update(status='failed',exit_code=code,validation='NOT_RUN',error=error,finished_at=cloudsim.shared.stamp())
        if directory:
            try: cloudsim.shared.save_metadata(directory,result)
            except OSError as failure: result['error'] += '\nCould not save the outcome: '+clean(failure)
        return result

    def tick(self):
        if not self.is_mounted or not self.started: return
        title = self.query('#stage-title')
        if not title: return  # Screen children can unmount before the app's timer stops.
        elapsed = int(time.monotonic()-self.started)
        title.first(Static).update(f'{self.stage.replace("-", " ").title()} · {elapsed//60:02}:{elapsed%60:02} elapsed')
        p = self.progress
        values = []
        if p.get('done') is not None and p.get('total'):
            values.append(f'{p["done"]}/{p["total"]} cases')
            self.query_one('#bar', ProgressBar).update(total=p['total'], progress=p['done'])
        else: self.query_one('#bar', ProgressBar).update(total=None)
        if p.get('evaluations') is not None: values.append(f'{p["evaluations"]} evaluations')
        for name in ('algorithm','scenario','replication'):
            if p.get(name) is not None: values.append(f'{name}: {p[name]}')
        if p.get('deadline_remaining') is not None: values.append(f'{int(p["deadline_remaining"])}s left')
        self.query_one('#measurements', Static).update(clean(' · '.join(values)))
        self.query_one('#stage-detail', Static).update(clean(p.get('detail', 'Checking prerequisites…')))
        if self.follow and self.log_path:
            text = log_tail(self.log_path)
            if text != self.log_text:
                self.log_text = text
                log = self.query_one('#live-log', RichLog); log.clear(); log.write(text)

    def show_result(self, metadata, recorded=False):
        self.outcome = metadata
        directory = Path(metadata['output_directory']) if metadata.get('output_directory') else None
        candidate = metadata.get('log') or (None if recorded else self.log_path)
        candidate = Path(candidate) if candidate else None
        self.outcome_log = candidate if candidate and directory and candidate.resolve().is_relative_to(directory.resolve()) and candidate.is_file() else directory/'console.log' if directory else None
        self.query_one('#flow', ContentSwitcher).current = 'result'
        okay = metadata.get('exit_code')==0 and metadata.get('status') in ('complete','checked')
        profile = metadata.get('profile')
        experiment = bool(profile) and metadata.get('status')!='checked' and metadata.get('action')!='validate'
        validates = experiment or metadata.get('action')=='validate'
        if validates: okay = okay and metadata.get('validation')=='PASS'
        summary = build_result_summary(metadata)
        self.result_summary = summary
        summary_error = summary.get('error')
        title = (profile.title()+' complete' if experiment else 'Validated' if validates else 'Setup ready' if metadata.get('status')=='checked' else 'Action complete') if okay else 'Cancelled' if metadata.get('status')=='interrupted' else 'Could not complete the job'
        if okay and experiment and summary_error:
            title = 'Completed · summary unavailable'
        heading = self.query_one('#outcome', Static); heading.update(title)
        heading.set_class(okay and not summary_error,'success'); heading.set_class(not okay,'failure')
        heading.set_class(okay and bool(summary_error),'summary-unavailable')
        detail = []
        if metadata.get('error'): detail.append(metadata['error'])
        elif not okay and not recorded and self.progress.get('detail'): detail.append(self.progress['detail'])
        status = ('Independent validation: ' if metadata.get('action')=='validate' else 'Recorded validation: ')+str(metadata.get('validation','NOT_RUN')) if validates else ''
        if metadata.get('tests') not in (None,'NOT_RUN'):
            status += (' · ' if status else '')+'Build/tests: '+str(metadata['tests'])
        if status: detail.append(status)
        if summary_error: detail.append(str(summary_error))
        validation = metadata.get('validation_result')
        if metadata.get('action')=='validate' and isinstance(validation,dict) and not (summary.get('overview') or summary.get('checks')):
            detail += [validation[name] for name in ('scope','artifact_binding','limitations') if validation.get(name)]
        self.query_one('#result-details', Static).update('\n'.join(clean(line) for line in detail))
        self.query_one('#result-details').display = bool(detail)
        self.render_result_summary(summary)
        self.query_one('#result-path', Static).update(clean(metadata.get('output_directory','No output directory recorded.')))
        self.query_one('#result-evidence', Collapsible).collapsed = True
        self.query_one('#result-raw', Collapsible).collapsed = True
        self.query_one('#result-facts', Collapsible).collapsed = True
        self.query_one('#metadata', Static).update(clean(json.dumps(metadata, indent=2)))
        self.query_one('#result-scroll').scroll_home(animate=False)
        self.query_one('#retry', Button).disabled = recorded or not self.last_args
        if not okay and 'Java' in str(metadata.get('error')):
            self.ready = False
            self.query_one('#readiness', Static).update('Setup needs attention · open Setup to resolve it')
        self.tick()

    def render_result_summary(self, summary):
        overview = summary.get('overview', [])
        compact = self.screen_stack[0].has_class('compact')
        visible = [(label,value) for label,value in overview if str(label).lower() in ('experiment','completed','production')][:2] if compact else overview
        if compact and not visible: visible = overview[:2]
        table = Table.grid(expand=True, padding=(0,1))
        if not compact: table.add_column(style='bold', no_wrap=True)
        table.add_column(ratio=1)
        for label, value in visible:
            if compact: table.add_row(Text(clean(value)))
            else: table.add_row(Text(clean(label)), Text(clean(value)))
        self.query_one('#result-overview', Static).update(table)
        self.query_one('#result-overview').display = bool(overview)
        full = Table.grid(expand=True, padding=(0,1))
        full.add_column(style='bold', no_wrap=True);full.add_column(ratio=1)
        for label, value in overview: full.add_row(Text(clean(label)), Text(clean(value)))
        self.query_one('#result-more-facts', Static).update(full)
        self.query_one('#result-facts').display = compact and bool(overview)

        # Evidence integrity failures suppress descriptive numbers. The stored
        # verdict remains explicitly recorded; loading a summary is no rerun of
        # the independent validators or optimizer replay.
        algorithms = [] if summary.get('error') else summary.get('algorithms', [])
        means = Table(box=None, expand=True, padding=(0,1), pad_edge=False,
                      header_style='bold', show_edge=False)
        for name in ('Algorithm','Cases','Energy J','SLA %','Time ms'):
            means.add_column(name, justify='left' if name=='Algorithm' else 'right', no_wrap=True)
        number = lambda value, decimals: '—' if value is None else f'{value:,.{decimals}f}'
        scenarios = {str(row.get('scenario','')) for row in algorithms}
        for row in algorithms:
            sla = row.get('sla')
            label = row['algorithm']+(' / '+str(row.get('scenario','')) if len(scenarios)>1 else '')
            means.add_row(Text(clean(label)), Text(str(row['cases'])),
                          Text(number(row.get('energy_j'),2)), Text(number(None if sla is None else sla*100,3)),
                          Text(number(row.get('runtime_ms'),1)))
        self.query_one('#algorithm-means', Static).update(means)
        self.query_one('#algorithm-title', Static).update('Algorithm means' if compact else
            'Algorithm means · '+('production cases' if self.outcome.get('profile') in ('stress','scenario') else 'main cases'))
        self.query_one('#algorithm-means').display = bool(algorithms)
        self.query_one('#algorithm-title').display = bool(algorithms)

        checks = summary.get('checks', [])
        text = Text()
        for index, check in enumerate(checks):
            if index: text.append('\n')
            text.append(clean(check['label']), style='bold')
            text.append(' · '+clean(check['status']).replace('_',' '), style='bold')
            if check.get('detail'): text.append('\n'+clean(check['detail'])+'\n')
        self.query_one('#check-details', Static).update(text)
        self.query_one('#result-checks').display = bool(checks)
        notes = summary.get('notes', [])
        self.query_one('#result-notes', Static).update('\n'.join(clean(note) for note in notes))
        self.query_one('#result-notes').display = bool(notes)
        self.query_one('#result-main').display = bool(overview or algorithms or notes)
        self.query_one('#result-body').display = bool(overview or algorithms or notes or checks)

    def record_result(self, metadata):
        directory = metadata.get('output_directory')
        if directory: self.recent[str(directory)] = metadata

    def refresh_results(self):
        selected=self.selected_result()
        selected_path=selected.get('output_directory') if selected else None
        parents = {self.output_parent, self.context.results, self.context.results/'stress'}
        value = self.query_one('#output', Input).value
        if value: parents.add(Path(value).expanduser().resolve())
        records = []
        for parent in parents:
            try:
                paths = sorted(parent.glob('*/runner.json'), key=lambda p:p.stat().st_mtime, reverse=True)[:100]
                for path in paths:
                    if path.is_symlink() or path.stat().st_size>1024*1024: continue
                    try:
                        data = json.loads(path.read_text())
                        if isinstance(data,dict):
                            data['output_directory'] = str(path.parent.resolve()); records.append(data)
                    except (OSError, ValueError): continue
            except OSError: continue
        for data in records: self.record_result(data)
        table = self.query_one('#recent', DataTable); table.clear()
        rows=sorted(self.recent.items(),key=lambda item:str(item[1].get('started_at','')),reverse=True)[:100]
        for path,data in rows:
            profile=data.get('profile')
            action=data.get('action')
            label='Setup check'+(f' ({profile})' if profile else '') if data.get('status')=='checked' else 'Validation report' if action=='validate' else profile or action or Path(path).name
            started=str(data.get('started_at','')).replace('T',' ')[:19]
            table.add_row(clean(label),clean(started),clean(data.get('status','unknown')),clean(data.get('validation','NOT_RUN')),key=path)
        keys={path for path,_ in rows}
        if selected_path not in keys:
            selected_path=next((path for path,data in rows if campaign_selection_error(data) is None),rows[0][0] if rows else None)
        if selected_path: table.move_cursor(row=table.get_row_index(selected_path))
        self.update_result_selection()

    def update_result_selection(self):
        selected=self.selected_result()
        reason=campaign_selection_error(selected) if selected else 'Select a retained experiment to inspect or validate it.'
        if self.busy and reason is None: reason='A job is running. Validation can start when it finishes.'
        self.query_one('#validate-selected',Button).disabled = self.busy or reason is not None
        directory=selected.get('output_directory','') if selected else ''
        self.query_one('#results-detail',Static).update(clean((reason or 'Completed experiment. Validate selected reruns the independent checks.')+'\n'+directory))

    @on(DataTable.RowHighlighted,'#recent')
    def result_highlighted(self,event): self.update_result_selection()

    def selected_result(self):
        table = self.query_one('#recent', DataTable)
        if not table.row_count: return None
        key = table.coordinate_to_cell_key(table.cursor_coordinate).row_key.value
        return self.recent.get(key)

    @on(TabbedContent.TabActivated)
    def activated(self, event):
        if event.pane.id == 'results': self.refresh_results()

    def tool(self, action, directory=None):
        if self.busy: self.notify('Wait for the current job, or cancel it first.'); return
        arguments = ['--'+action]
        if directory: arguments.append(str(directory))
        arguments += ['--plain','--output-dir',str(self.output_parent)]
        if action not in ('validate','setup'): arguments += ['--heap-mib',self.query_one('#heap', Input).value]
        try: self.parsed(arguments)
        except (ValueError, OSError) as error: self.notify(clean(error), severity='error'); return
        self.last_args = arguments; self.last_action = action; self.begin(arguments)

    async def on_button_pressed(self, event):
        button = event.button.id
        if button == 'start': await self.start_action()
        elif button in ('fix-setup','result-setup'): self.action_setup()
        elif button == 'edit': self.action_edit()
        elif button == 'retry' and not self.busy and self.last_args: self.begin(self.last_args)
        elif button == 'follow':
            self.follow = not self.follow
            event.button.label = 'Pause log' if self.follow else 'Follow log'; self.tick()
        elif button == 'cancel-job': self.action_cancel()
        elif button in ('check','build','test'): self.tool(button)
        elif button == 'validate':
            value = self.query_one('#validate-path', Input).value
            if value: self.tool('validate', Path(value).expanduser())
            else: self.notify('Enter an existing run or campaign directory.', severity='warning')
        elif button == 'check-again' and not self.busy: self.check_readiness()
        elif button == 'install-jdk' and not self.busy:
            self.push_screen(Confirm('Download and install a verified JDK 21 (198 MiB) inside .cloudsim? System Java settings will be preserved.', 'Install JDK 21'),
                lambda confirmed: self.tool('setup') if confirmed else None)
        elif button == 'select-jdk' and not self.busy:
            value = self.query_one('#jdk-path', Input).value
            if not value:
                self.notify('Enter the JDK directory containing bin/java and bin/javac.', severity='warning'); return
            self.pending_java = (self.java_override,)
            self.java_override = Path(value).expanduser().resolve()
            self.check_readiness()
        elif button == 'refresh-results': self.refresh_results()
        elif button in ('result-details-button','validate-selected'):
            selected = self.selected_result()
            if not selected: self.notify('Refresh and select a retained run first.'); return
            if button == 'validate-selected':
                reason=campaign_selection_error(selected)
                if reason: self.notify(reason,severity='warning');return
                self.tool('validate', Path(selected['output_directory']))
            else:
                self.action_tab('run'); self.show_result(selected, recorded=True)
        elif button == 'view-log':
            path = self.outcome_log
            self.push_screen(LogView(str(path or 'No retained log available'), log_tail(path)))


def run():
    if os.environ.get('TERM', 'dumb') in ('', 'dumb'):
        print('A capable terminal is required. Use ./lattora.sh --plain --check or --profile smoke|explore|research|stress.', file=sys.stderr)
        return 2
    return CloudSimApp().run() or 0
