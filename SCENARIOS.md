# Configured static scenarios

`lattora run --profile scenario --config FILE.json` runs a static HO/GA/FirstFit/BestFit
campaign over a versioned configuration document instead of the frozen profiles or the
stress dimensions. A configuration declares host classes, VM demand distributions, the
workload length distribution, algorithms, replications, seeds and optimizer effort.

Configured campaigns are descriptive: they measure placements, the selected objective's
predicted components and simulated observed outcomes on the configured instance. They do
not change, extend or re-interpret the [frozen research protocol](docs/research-protocol-v2.md);
they record their own protocol identity (`static_scenario_v2`, `scenario_schema_version=2`,
`objective_version=1`).

```sh
lattora run --profile scenario --preset known-answer-tight
lattora run --profile scenario --preset known-answer-tradeoff
lattora run --profile scenario --config scenario.json --output-dir ./my-results
lattora run --profile scenario --preset heterogeneous-hosts --dry-run
lattora validate "/path/to/retained/scenario-<uuid>" --plain
```

`--preset NAME` writes the named bundled configuration and runs it through the same
path as `--config`, so the retained `config/requested.json` is always the exact request
document the engine resolves.

## Configuration document

UTF-8 JSON, one object, no trailing content. Every key is required unless marked
optional; unknown and duplicate keys are rejected.

```json
{
  "config_version": 1,
  "name": "my-scenario",
  "master_seed": 123456,
  "replications": 2,
  "algorithms": ["HO", "GA", "FirstFit", "BestFit"],
  "objective": {"kind": "power"},
  "optimizer": {"population": 10, "iterations": 4},
  "hosts": [
    {"count": 4, "pes": 32, "mips_per_pe": 1500, "ram_mib": 65536, "bw_mbps": 20000, "storage_mib": 2000000, "idle_w": 220, "max_w": 380}
  ],
  "vms": {
    "count": 48,
    "pes": [1, 2],
    "mips_per_pe": [1000, 1400],
    "ram_mib": [1024, 2048, 4096],
    "bw_mbps": [1000],
    "storage_mib": [10000]
  },
  "workload": {"length_seconds": {"min": 30, "max": 120}, "utilization": 1.0, "release_seconds": 0.0}
}
```

| Key | Unit | Required | Limits |
| --- | --- | --- | --- |
| `config_version` | – | yes | the integer `1` |
| `name` | – | yes | `[a-z][a-z0-9._-]{0,63}`; also the scenario identity in every recorded row |
| `master_seed` | – | yes | signed 64-bit integer |
| `replications` | – | yes | 1..10000; each replication has its own draws |
| `algorithms` | – | optional | non-empty, no duplicates, from `HO`,`GA`,`FirstFit`,`BestFit`; default all four, always recorded in canonical order |
| `objective` | – | optional | `{"kind":"power"}` (default), `{"kind":"utilization"}` or `{"kind":"weighted","weights":{"power":0.4,"utilization":0.6}}`; see [objectives](#objectives-and-normalization) |
| `objective.weights.power`, `objective.weights.utilization` | – | iff `objective.kind` is `weighted` | finite numbers from 0.0 to 1.0 whose exact decimal sum is 1.0; `sla` weights are rejected |
| `optimizer.population` | candidates | iff `HO`/`GA` | even integer ≥ 2 |
| `optimizer.iterations` | iterations | iff `HO`/`GA` | integer ≥ 1; `population + 3*population*iterations` ≤ 2147483647 |
| `hosts` | array of classes | yes | at most 16 classes, 1..100000 hosts in total, emitted in file order with sequential ids |
| `hosts[i].count` | hosts | yes | 1..100000 |
| `hosts[i].pes` | PEs | yes | 1..2147483647; `pes * mips_per_pe` must fit a signed 64-bit integer |
| `hosts[i].mips_per_pe` | MIPS per PE | yes | 1..2147483647 |
| `hosts[i].ram_mib` | MiB | yes | 1..2147483647 |
| `hosts[i].bw_mbps` | Mb/s | yes | 1..2147483647 |
| `hosts[i].storage_mib` | MiB | yes | 1..2147483647 |
| `hosts[i].idle_w` | W | yes | 0..2147483647 |
| `hosts[i].max_w` | W | yes | `idle_w`..2147483647; the total `count * max_w` over all classes must be positive (it is the power normalizer) |
| `vms.count` | VMs | yes | 1..100000; `vms.count * total host count` ≤ 2147483647 |
| `vms.pes` | PEs | yes | non-empty non-decreasing positive list (duplicates are weights) or `{"min":a,"max":b}`, at most 4096 values |
| `vms.mips_per_pe` | MIPS per PE | yes | same shape; every value must be a multiple of 8 |
| `vms.ram_mib`, `vms.bw_mbps`, `vms.storage_mib` | MiB, Mb/s, MiB | yes | same shape |
| `workload.length_seconds` | seconds | yes | non-empty non-decreasing integer list or `{"min":a,"max":b}` from 1 to 1440, at most 4096 values |
| `workload.utilization` | – | yes | `1.0` exactly |
| `workload.release_seconds` | seconds | yes | `0.0` exactly |

A declared `vms.<field>` maximum larger than every host class value for that field is
rejected, because such a demand could never be admitted. Every rejection is one
JSON-path-addressed sentence (for example `unknown field: hosts[0].bogus`,
`duplicate field: name`, `vms.ram_mib value 16384 exceeds every host class ram_mib (8192)`)
produced before any simulation starts, and every one of those rule sentences is
byte-identical in the Java engine and the independent Python model for the same input.
Raw parse failures are engine-specific: both report them as `invalid JSON: <engine detail>`
with no run directory created. A missing or unreadable file is an I/O error
(`IO_ERROR`, exit 1); invalid content is a configuration error (`CONFIG_ERROR`, exit 2).
Neither creates a run directory.

### Draw order and seed identity

Hosts are emitted verbatim from the classes and consume no randomness. Each VM `i` then
draws, from the `scenario` stream derived from `(master_seed, "scenario", name, replication)`:
`pes`, `mips_per_pe`, `ram_mib`, `bw_mbps`, `storage_mib`, each as
`values[nextInt(values.size())]`. From the independent `workload` stream it draws
`length_seconds`, and its cloudlet length is `mips_per_pe * length_seconds` MI. The
`optimizer` seed additionally includes the algorithm name, so all algorithms of one
replication share identical inputs and differ only in search randomness.

Two identities with the same `master_seed` never collide across the frozen, stress and
configured phases, and paired reproductions of the same document are byte-identical
apart from `run_id` and measured wall-clock columns.

### Units and numerical constraints

* `length_seconds` is the cloudlet's simulated service time: the length is
  `mips_per_pe * length_seconds` MI and a VM executes one cloudlet at `mips_per_pe`, so an
  isolated reference takes exactly that many seconds (the frozen `cloudlet.duration.seconds`
  relation). The evidence column is `horizon_s`.
* `vms.mips_per_pe` values must be multiples of 8. The simulator advances events in
  exactly representable 0.125 s intervals and truncates processed MI; values that are not
  multiples of 8 lose progress per event, so measured isolated references would no longer
  equal the declared workload length. The frozen protocol's own `1000/2000/3000` MIPS
  values satisfy this. The engine additionally refuses to build a specification whose
  measured reference differs from the declared length.
* Host power is linear from `idle_w` to `max_w` over the host's **own** total MIPS
  (`pes * mips_per_pe`), so heterogeneous host classes are physically consistent. The
  frozen profiles stay byte-identical because their single class is 16 x 3000 = 48000 MIPS.
* `hosts[i].mips_per_pe` only gates VM admission (`vms.mips_per_pe` may not exceed the
  host's); the cloudlet's execution rate is the VM's own per-PE MIPS.

## Objectives and normalization

The objective is part of the configuration, is versioned in the resolved document and the
effective map, and is recorded on every case and summary row. All components are
dimensionless and minimized; `objective_version = 1` fixes the definitions below.

| Choice | Search ranks a placement by | Weight vector |
| --- | --- | --- |
| `power` (default) | predicted steady watts | `(1, 0)` |
| `utilization` | largest reserved MIPS fraction on any host | `(0, 1)` |
| `weighted` | `w_power * normalized_power + w_utilization * peak_utilization` | declared, exact sum 1.0 |
| `sla` | rejected as unsupported | – |

For a placement, `reserved_h` is the sum of the reserved MIPS of every VM on host `h` and
`f_h = reserved_h / (pes_h * mips_per_pe_h)`. Only hosts with `reserved_h > 0` contribute
their idle power:

```text
predicted_power_w = sum over used hosts [ idle_w_h + (max_w_h - idle_w_h) * f_h ]
normalized_power  = predicted_power_w / sum over all hosts (max_w_h)
peak_utilization  = max over all hosts f_h
objective_score   = w_power * normalized_power + w_utilization * peak_utilization
```

`predicted_power_w` is steady reserved power, never simulated energy. The power
normalizer is the sum of every host's `max_w`, so the predicted power is in `(0, 1]` and
a placement that concentrates load on a small host can score worse than one that spreads
it over a large host. Search compares `objective_score`, then predicted power, then genes,
then candidate creation order. The fixed `FirstFit`/`BestFit` baselines remain
objective-agnostic planners but are scored under the configured objective.

SLA optimization is recognized in the schema but rejected before execution with
`objective.kind sla is unsupported: constant full utilization predicts zero SLA violations
for every feasible placement; use power, utilization or weighted until a contention model
is supported`. The constant-full-utilization model has no contention or queueing term, so
an SLA objective would not change any placement. `objective.weights.sla` is rejected for
the same reason. This is an explicit limitation, not a fabricated variation.

Predicted and observed quantities are separate evidence columns. `predicted_*` and
`normalized_*` are computed from the plan before simulation; `observed_utilization`,
`observed_energy_j`, `observed_energy_kwh`, `observed_sla_*` and the completion counters
come from the CloudSim run. `observed_utilization` is the integrated busy MIPS-seconds
over total MIPS-seconds across the horizon; it is not the reserved peak used by the
objective. `simulations` counts executed cases and `search_simulations` counts simulation
re-executions inside search (always 0: search evaluates placements, it does not simulate
candidates).

## Supported simulator models

* `VmSchedulerSpaceShared` and `CloudletSchedulerSpaceShared`, one cloudlet per VM, same
  PE count as its VM, full constant CPU/RAM/bandwidth utilization, no file-transfer delay.
* Static reservation admission in five dimensions: PEs, MIPS, RAM, bandwidth, storage.
  A VM is admitted only where the cumulative reservation fits and its per-PE MIPS does not
  exceed the host's.
* No migrations, no overcommit, one simultaneous release at the first event time
  (`release_s = 0.125`), censor at ten times the longest reference, SLA threshold 1.10.
* Only constant full utilization (`1.0`) is simulated; `utilization` exists in the schema
  to state that model explicitly and reject other values.

> Only constant full utilization (1.0), one cloudlet per VM, VmSchedulerSpaceShared, CloudletSchedulerSpaceShared, no migrations, no overcommit, a single simultaneous release and a linear idle-to-max power curve over the host's own total MIPS are simulated. PE, MIPS, RAM, bandwidth and storage limits are static reservation admission checks; they are not simulated network or storage contention.

## Feasibility classes

Every replication is classified before its cases run, from regenerated inputs
(`feasibility` in `run.json`), and the same classification is recomputed by the
independent validator:

| Class | Meaning | Effect |
| --- | --- | --- |
| `CONSTRUCTIBLE` | A constructive witness placed every VM: VM `i` on host `i mod hostCount`, with the same five-dimensional admission and per-PE rules as the planners. | The replication runs. |
| `UNPROVED` | The summed demand fits, but the witness placement was rejected. This is an explicit gap between the necessary condition and a proof, never a silent success. | The replication is attempted and fails with `SCENARIO_REJECTED`, naming the first VM the witness could not place; the first rejected host and dimension are recorded in `feasibility[].reason`. |
| `INFEASIBLE_PROVED` | A necessary condition already fails: total VM demand exceeds total host capacity in `pes`, `mips`, `ram_mib`, `bw_mbps`, `storage_mib` order, or a VM's per-PE MIPS exceeds the largest host class value. | The run stops before simulating that replication with `SCENARIO_INFEASIBLE` and the proof sentence. |

Because the witness is a fixed round-robin placement, a declared `vms.mips_per_pe` value
above the **slowest** host class cannot be placed, even when a smarter placement exists;
such a document is `UNPROVED`. Keep every declared VM per-PE value at or below the
slowest class, or make all classes at least as fast as the fastest declared demand.

## Bundled presets

`known-answer-feasible`, `known-answer-tight`, `known-answer-tradeoff` and
`known-answer-infeasible` are minimal known-answer instances (`master_seed` 123456, one
replication, N10/T4).

```json
{
  "config_version": 1,
  "name": "known-answer-feasible",
  "master_seed": 123456,
  "replications": 1,
  "algorithms": ["HO", "GA", "FirstFit", "BestFit"],
  "optimizer": {"population": 10, "iterations": 4},
  "hosts": [{"count": 2, "pes": 8, "mips_per_pe": 3000, "ram_mib": 16384, "bw_mbps": 4000, "storage_mib": 40000, "idle_w": 100, "max_w": 200}],
  "vms": {"count": 2, "pes": [1], "mips_per_pe": [3000], "ram_mib": [2048], "bw_mbps": [1000], "storage_mib": [10000]},
  "workload": {"length_seconds": {"min": 30, "max": 30}, "utilization": 1.0, "release_seconds": 0.0}
}
```

```json
{
  "config_version": 1,
  "name": "known-answer-tight",
  "master_seed": 123456,
  "replications": 1,
  "algorithms": ["HO", "GA", "FirstFit", "BestFit"],
  "optimizer": {"population": 10, "iterations": 4},
  "hosts": [{"count": 2, "pes": 2, "mips_per_pe": 3000, "ram_mib": 8192, "bw_mbps": 2000, "storage_mib": 20000, "idle_w": 100, "max_w": 200}],
  "vms": {"count": 2, "pes": [2], "mips_per_pe": [3000], "ram_mib": [8192], "bw_mbps": [2000], "storage_mib": [20000]},
  "workload": {"length_seconds": {"min": 60, "max": 60}, "utilization": 1.0, "release_seconds": 0.0}
}
```

```json
{
  "config_version": 1,
  "name": "known-answer-infeasible",
  "master_seed": 123456,
  "replications": 1,
  "algorithms": ["HO", "GA", "FirstFit", "BestFit"],
  "optimizer": {"population": 10, "iterations": 4},
  "hosts": [{"count": 1, "pes": 4, "mips_per_pe": 3000, "ram_mib": 4096, "bw_mbps": 1000, "storage_mib": 10000, "idle_w": 100, "max_w": 200}],
  "vms": {"count": 2, "pes": [1], "mips_per_pe": [3000], "ram_mib": [4096], "bw_mbps": [1000], "storage_mib": [10000]},
  "workload": {"length_seconds": {"min": 60, "max": 60}, "utilization": 1.0, "release_seconds": 0.0}
}
```

`known-answer-tradeoff` is the hand-checkable power/utilization conflict. Host 0 is a
small, cheap 2-PE host (`10 -> 20 W`); host 1 is a large, expensive 6-PE host
(`100 -> 200 W`). Two 1-PE/3000-MIPS VMs run for 30 s. Putting both on host 0 minimizes
predicted power (`20 W`) but reserves 100% of its MIPS; putting both on host 1 raises
predicted power to `133.33 W` but reserves only one third. FirstFit and BestFit are not
configured so the comparison stays within the two search planners.

```json
{
  "config_version": 1,
  "name": "known-answer-tradeoff",
  "master_seed": 123456,
  "replications": 1,
  "algorithms": ["HO", "GA"],
  "optimizer": {"population": 10, "iterations": 4},
  "objective": {"kind": "power"},
  "hosts": [
    {"count": 1, "pes": 2, "mips_per_pe": 3000, "ram_mib": 8192, "bw_mbps": 2000, "storage_mib": 20000, "idle_w": 10, "max_w": 20},
    {"count": 1, "pes": 6, "mips_per_pe": 3000, "ram_mib": 8192, "bw_mbps": 2000, "storage_mib": 20000, "idle_w": 100, "max_w": 200}
  ],
  "vms": {"count": 2, "pes": [1], "mips_per_pe": [3000], "ram_mib": [2048], "bw_mbps": [1000], "storage_mib": [10000]},
  "workload": {"length_seconds": {"min": 30, "max": 30}, "utilization": 1.0, "release_seconds": 0.0}
}
```

Changing only `objective` on that document selects a different placement. The power
normalizer is `20 + 200 = 220 W`, so both variants below are independently reconstructable
from the components (`observed_utilization` is `0.25` in every row):

| Objective | Placement (`host_id` per VM) | `predicted_power_w` | `peak` | `normalized_power` | `objective_score` | `observed_energy_j` |
| --- | --- | --- | --- | --- | --- | --- |
| `power` | `[0, 0]` | `20.0` | `1.0` | `0.090909...` | `0.090909...` | `600.0` |
| `utilization` | `[1, 1]` | `133.333...` | `0.333333...` | `0.606060...` | `0.333333...` | `4000.0` |
| `weighted` `power=0.4, utilization=0.6` | `[1, 1]` | `133.333...` | `0.333333...` | `0.606060...` | `0.442424...` | `4000.0` |
| `weighted` `power=0.7, utilization=0.3` | `[0, 0]` | `20.0` | `1.0` | `0.090909...` | `0.363636...` | `600.0` |

The weighted rows are `w_power * normalized_power + w_utilization * peak`; for
`0.4/0.6`, `[1,1]` scores `0.4*0.606060 + 0.6*0.333333 = 0.442424`, below `[0,0]`'s
`0.636364`. For `0.7/0.3`, the ordering reverses. No objective is "better" in the
scientific sense: the table is a descriptive tradeoff, and all observed outcomes remain
recorded next to the predictions.

`heterogeneous-hosts` varies three host classes and two demand levels over two
replications; `resource-pressure` fills one class to 90% of its RAM.

```json
{
  "config_version": 1,
  "name": "heterogeneous-hosts",
  "master_seed": 123456,
  "replications": 2,
  "algorithms": ["HO", "GA", "FirstFit", "BestFit"],
  "optimizer": {"population": 10, "iterations": 4},
  "hosts": [
    {"count": 4, "pes": 32, "mips_per_pe": 1500, "ram_mib": 65536, "bw_mbps": 20000, "storage_mib": 2000000, "idle_w": 220, "max_w": 380},
    {"count": 8, "pes": 16, "mips_per_pe": 3000, "ram_mib": 32768, "bw_mbps": 10000, "storage_mib": 1000000, "idle_w": 175, "max_w": 250},
    {"count": 6, "pes": 8, "mips_per_pe": 2000, "ram_mib": 16384, "bw_mbps": 4000, "storage_mib": 250000, "idle_w": 90, "max_w": 140}
  ],
  "vms": {"count": 48, "pes": [1, 2], "mips_per_pe": [1000, 1400], "ram_mib": [1024, 2048, 4096], "bw_mbps": [1000], "storage_mib": [10000]},
  "workload": {"length_seconds": {"min": 30, "max": 120}, "utilization": 1.0, "release_seconds": 0.0}
}
```

```json
{
  "config_version": 1,
  "name": "resource-pressure",
  "master_seed": 123456,
  "replications": 1,
  "algorithms": ["HO", "GA", "FirstFit", "BestFit"],
  "optimizer": {"population": 10, "iterations": 4},
  "hosts": [{"count": 10, "pes": 16, "mips_per_pe": 3000, "ram_mib": 32768, "bw_mbps": 10000, "storage_mib": 1000000, "idle_w": 175, "max_w": 250}],
  "vms": {"count": 36, "pes": [2], "mips_per_pe": [2000], "ram_mib": [8192], "bw_mbps": [1000], "storage_mib": [20000]},
  "workload": {"length_seconds": {"min": 60, "max": 120}, "utilization": 1.0, "release_seconds": 0.0}
}
```

## Known answers

Values below are reproduced by the pinned seed 123456 and are pinned again by the Java
and Python test suites. `predicted_power_w` is the final placement's steady reserved-power
estimate under the row's objective, `observed_energy_j` the integrated simulated energy of
the same case, and `peak`/`normalized_power` feed the objective score. Under the default
power objective the score equals `normalized_power`.

| Preset | Class | Cases | Expected values |
| --- | --- | --- | --- |
| `known-answer-feasible` | `CONSTRUCTIBLE` | 4 | every algorithm: `predicted_power_w = 125.0`, `predicted_reserved_peak_utilization = 0.125`, `normalized_power = 0.3125`, `objective_score = 0.3125`, `observed_energy_j = 3750.0`, `observed_utilization = 0.125`, `horizon_s = 30.0`, `release_s = 0.125`, `sla_rate = 0.0`; splitting the two VMs across hosts would cost 225.0 W, so 125.0 W is the optimum |
| `known-answer-tight` | `CONSTRUCTIBLE` | 4 | every algorithm: `predicted_power_w = 400.0`, `predicted_reserved_peak_utilization = 1.0`, `normalized_power = 1.0`, `objective_score = 1.0`, `observed_energy_j = 24000.0`, `observed_utilization = 1.0`, `horizon_s = 60.0`; each VM exactly saturates one host in all five dimensions, and every plan uses both hosts once |
| `known-answer-tradeoff` | `CONSTRUCTIBLE` | 2 (HO, GA) | power selects `[0,0]`; utilization selects `[1,1]`; see the hand-checkable table above for both weighted variants |
| `known-answer-infeasible` | `INFEASIBLE_PROVED` | 1 attempted, 3 unattempted | run `FAILED`, `error.code = SCENARIO_INFEASIBLE`, reason `replication 0 total ram_mib demand 8192 exceeds total host ram_mib capacity 4096`, no `analysis/` |
| `heterogeneous-hosts` | `CONSTRUCTIBLE` (both replications) | 8 | 18 hosts in 3 classes, 48 VMs, `horizon_s` 119-120, `sla_rate = 0.0`, distinct per-algorithm objective scores under the default power objective |
| `resource-pressure` | `CONSTRUCTIBLE` | 4 | 10 hosts, 36 VMs, `predicted_power_w = 1800.0`, `predicted_reserved_peak_utilization = 0.333333...`, `normalized_power = 0.72`, `horizon_s = 120.0`, 4 VMs per used host and 90% of total RAM reserved |

## Recorded request, resolution and hashes

A run directory `scenario-<uuid>` contains:

| Path | Content |
| --- | --- |
| `config/requested.json` | byte copy of the document the engine was given |
| `config/resolved.json` | canonical resolved document: defaults applied, ranges expanded, lists ascending, algorithms in canonical order, `optimizer` only when a search algorithm is configured |
| `effective.properties` | sorted `key=value` lines of the flat effective configuration, including `config.sha256` (SHA-256 of the requested bytes) |
| `raw/cases.csv`, `raw/placements.csv`, `raw/evaluations.csv` | the case matrix, final placements and (for search algorithms only) scalar optimizer traces |
| `scenarios/replication-<r>.json` | regenerated inputs, reference seconds, censor, canonical text and `scenario_sha256` for that replication |
| `analysis/summary.csv` | per-algorithm means over successful cases |
| `run.json`, `progress.json` | manifest with provenance, `resolved_config`, `effective_config`, `feasibility`, counters and hash-bound `files`; atomic progress snapshot |

`run.json` records `protocol=static_scenario_v2`, `scenario_schema_version=2`,
`config_name`, `config_sha256`, `expected_cases`, `expected_evaluations`, case counters,
`model_limits`, `evidence_limit` and `allocation_timing`. `files` lists every hash-bound
artifact; the run is reproducible from `config/requested.json` alone.

Validation (`lattora validate`, `scripts/scenario_validator.py`) re-derives the resolved
document, the effective map, each replication's specification and hash, the feasibility
classes, placements, objective version/weights, every predicted component and normalization,
the observed energy/SLA/horizon/counters, scalar trace order and the eligible-best chain,
and compares them with the recorded bytes and hashes. It prints
`VALID static_scenario scenario_schema_version=2` or `INVALID: ...`.
