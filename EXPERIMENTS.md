# Running and interpreting experiments

The commands in this guide use the installed `lattora` app on Linux and native
Apple Silicon macOS. Start with `lattora run --help`, or open `lattora` to review
the run plan in the terminal. Source contributors can use the same profile flags
with `./lattora.sh`; see the [development guide](DEVELOPMENT.md).

## Frozen profiles

| Profile | Scenarios (VMs/hosts) | Replications | HO N/T | Cases |
| --- | --- | --- | --- | --- |
| smoke | Micro 10/3 | 1 | 10/4 | 4 |
| explore | Micro 10/3, Small 50/10 | 5 | 20/20 | 40 |
| research | Micro 10/3, Small 50/10, Medium 100/20 | 30 | 30/40 | 360 main + 90 OAT |

All four algorithms run in every main replication. Research additionally runs
HO on Small with nine distinct one-at-a-time (OAT) N/T settings and ten paired
sensitivity replications. The shared (30,40) setting is counted once. These
settings and the **100-VM ceiling** are fixed; larger frozen scenarios, GPU, Pareto optimization, overcommit and migrations are future work.

```sh
lattora run --profile smoke
lattora run --profile explore
lattora run --profile research --workers 2 --heap-mib 1024
```

## Static Stress

Stress lets you choose size, population, iterations, replications and seed.
It measures a static campaign descriptively and does not change the frozen
Research protocol. A small bounded example is:

```sh
lattora run --profile stress --vms 100 --hosts 20 --population 10 --iterations 4 --replications 1 --time-limit 5m
```

Stress runs the selected full campaign only. Short calibration campaigns use
N10/T10/R1 at up to 100 VMs, 500 VMs and the selected size first. For Large,
these are 100/20, 500/100 and 10000/2000 VMs/hosts, followed by the full Large
campaign. It does not run the full intermediate presets. Calibrations run
serially so their resource measurements remain comparable.

## Configured static scenarios

The `scenario` profile runs a static campaign over a versioned JSON document instead of
frozen dimensions: host classes with their own power curves, VM demand distributions,
workload lengths, algorithms, replications, seeds and optimizer effort.

```sh
lattora run --profile scenario --preset known-answer-tight
lattora run --profile scenario --config scenario.json --output-dir "./my results"
lattora run --profile scenario --preset heterogeneous-hosts --dry-run
```

Five presets ship with the app: three minimal known-answer instances
(`known-answer-feasible`, `known-answer-tight`, `known-answer-infeasible`) and two
families (`heterogeneous-hosts`, `resource-pressure`). They state what a configuration
can express and what the engine answers with.

| Preset | Class | Expected values |
| --- | --- | --- |
| `known-answer-feasible` | `CONSTRUCTIBLE` | every algorithm `objective_w = 125.0`, `energy_j = 3750.0` |
| `known-answer-tight` | `CONSTRUCTIBLE` | every algorithm `objective_w = 400.0`, `energy_j = 24000.0` |
| `known-answer-infeasible` | `INFEASIBLE_PROVED` | `SCENARIO_INFEASIBLE`: `ram_mib` demand 8192 exceeds capacity 4096 |
| `heterogeneous-hosts` | `CONSTRUCTIBLE` | 18 hosts in 3 classes, 48 VMs, 2 replications |
| `resource-pressure` | `CONSTRUCTIBLE` | 10 hosts, 36 VMs, `objective_w = 1800.0` |

Each replication is classified before it runs: `CONSTRUCTIBLE` (a constructive witness
placed every VM), `UNPROVED` (the summed demand fits but the witness was rejected; the
replication fails explicitly instead of being reported as feasible) or
`INFEASIBLE_PROVED` (a necessary condition already fails and the run stops before
simulating it). Malformed documents, unknown and duplicate keys, unsupported utilization
or release values, impossible demand ranges and unrepresentable per-PE MIPS are rejected
before any simulation starts, with exit code 2 and no run directory.

[SCENARIOS.md](SCENARIOS.md) documents the schema, units, limits, draw order, seed
identity, presets, feasibility classes, supported simulator models and the recorded
request/resolution/hash layout. Configured campaigns are descriptive: they do not change
the frozen Research protocol or its claims.

## Configuration and paths

Use `--output-dir DIRECTORY` to choose a parent for unique retained runs, or
leave it unset to use the [central results library](README.md#results-and-interpretation).
Explicit output and configuration paths are resolved from the invocation
directory. Preview a profile without running it with `--dry-run`; add `--plain`
for plain progress. Inspect all available options with `lattora run --help`.

Frozen profiles accept a UTF-8 Java properties overlay with these keys only:

```properties
master.seed=123456
log.level=INFO
```

```sh
lattora run --profile explore --config experiment.properties --output-dir "./my results"
```

`master.seed` is a signed 64-bit decimal; `log.level` is `INFO` or `DEBUG`.
Unknown or duplicate keys (including escaped equivalents), malformed UTF-8 and
invalid values are rejected. Scenario and optimizer settings remain frozen;
`output.dir` is not a configuration key. Stress uses `--seed` and its own
explicit flags instead of `--config`.

## Workers and memory

`--workers auto` (default) runs independent cases concurrently within CPU and
shared heap limits: at most one worker per 512 MiB, capped at 32. Use
`--workers 1` for serial execution, or request a count with `--workers 4`.
Seeds, paired workloads and canonical output order are unchanged; individual
CloudSim event loops remain sequential. The heap cap is shared across workers.
The limit bounds concurrency; it does not guarantee that any size fits in RAM.
Research keeps at most one pending case per worker because it retains full
traces. Stress can queue up to four cases per worker (128 maximum), retaining
scalar evidence in temporary disk spools so faster cases can advance to the
next replication while a slower optimizer runs. Scientific rows are still
published in their canonical order.

The runners enforce their own deadlines and process-group cancellation, so
macOS does not need Linux's `timeout` command. macOS memory checks use
`sysctl hw.memsize` and `vm_stat`'s actual page size, counting free, inactive
and speculative pages. Wired memory, compressed pages and swap are excluded;
the existing minimum 3 GiB usable-memory check and heap-plus-1-GiB headroom
still apply. These are launch checks, not a memory reservation or a guarantee
that a large stress campaign fits. Native RSS samples report a sampled peak;
brief peaks between samples may be missed. Linux retains its `/proc` metrics
and every visible cgroup-v2 ancestor limit.

## Independent validation

Every experiment runs its independent validator. To repeat the check, use the
exact campaign or run directory printed by the app:

```sh
lattora validate "/path/to/retained/campaign" --plain
```

The validator checks manifest/file hashes, raw matrices, canonical inputs,
placements, objective budgets, metrics and independent analysis. The executable
contract is the [frozen configuration](src/main/resources/protocol.properties)
and [`RunConfig.effective()`](src/main/java/org/puneet/cloudsimplus/hiippo/runtime/RunConfig.java),
checked by the [output validator](scripts/statistics_validator.py).

A timeout or interruption leaves incomplete evidence; never combine fragments.
Stable releases require all 450 Research cases on each distribution target from
the exact versioned archive, valid independent analyses and two reviewer approvals.
Smoke or Explore checks alone do not establish full Research acceptance.
Installed runs retain release verification and record local tests as `NOT_RUN`;
they do not claim to have run the source test suite on the user's machine.

## What is measured

The optimizer minimizes estimated steady host power in **watts**. The simulation
separately integrates actual event-time power into **joules** and **kWh**;
used hosts remain on through the final workload horizon. SLA violations count
failed/censored cloudlets or slowdown strictly above 1.10 against independently
simulated isolated references, divided by all requested cloudlets.

Strict reservation, constant utilization and one cloudlet per VM can make SLA
identically zero and horizon placement-invariant. The conservative paired
wild-bootstrap/BCa/Holm analysis reports **NO_CLAIM** when required inference is
degenerate; descriptive energy differences do not override that gate. Results
are conditional on this synthetic simulator model, not production evidence.

HO implements all three paper phases with a declared Gaussian Mantegna Levy
adaptation. Its full objective-call budget is **N + 3NT**, including predators:
130/1220/3630 for the three main profiles. GA receives the same budget. The
[search implementation](src/main/java/org/puneet/cloudsimplus/hiippo/placement/Search.java)
and independent [optimizer oracle](scripts/optimizer_oracle.py) specify and check
the equations, draw order and deterministic repair. The frozen configuration
and output validator define the practical margins and inference gates.

## Retained outputs

Each unique directory contains `run.json`, sorted `effective.properties`,
`raw/main_results.csv`, `raw/placements.csv`, `raw/optimizer_trace.csv` and
`logs/run.log`. Explore adds descriptive analysis; research adds sensitivity
rows, paired primary comparisons, runtime summaries and an analysis report.
Only a validated `COMPLETE` manifest is eligible evidence. `RUNNING` and `FAILED`
directories remain for diagnosis. Logs rotate at 10 MiB with three retained
archives, including DEBUG. Existing local logs/results are never cleaned by the app.

The Results tab distinguishes experiments, setup checks and validation reports.
Completed experiments show case/evaluation totals, elapsed time, runtime limits
and algorithm means with explicit units. Recorded independent validation is
separate from the lightweight integrity check used to display a summary.
Stress remains descriptive; Research shows its recorded claim decisions.
Saved evidence includes paths, commands and raw metadata.

[Quickstart](README.md#your-first-experiment) · [Development](DEVELOPMENT.md) · [Distribution](DISTRIBUTION.md)
