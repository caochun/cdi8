# GXLF Contract-Driven Simulation System

This directory contains a standalone implementation of a GXLF flow engine plus
state-machine driven Tango-style service simulators.

It uses the three YAML model files in `gxlf_sim_system/models/` as source data:

- `gxlf-firing-flow.yaml`: flow DAG and node orchestration.
- `interface-contracts.yaml`: command request/response/callback contracts.
- `service-state-machines.yaml`: service instances, health states, task states,
  business states, and command transitions.

The first implementation intentionally does not require a real Tango database or
PyTango runtime. It exposes an internal Tango-like boundary:

```text
command_inout(command_name, DevString(JSON)) -> DevString(JSON)
task status callbacks through the simulator callback bus
service health snapshots as JSON payloads
```

`command_inout` now models the real control boundary more closely: it returns
only the immediate accept/reject response. Device task states such as
`accepted`, `executing`, and `succeeded` are published later through an internal
callback bus, and the flow engine waits on that bus before advancing the DAG.

That keeps the flow engine and simulator contract-compatible with the YAML
models while making the system runnable in a plain Python environment.

## Run

From the repository root:

```bash
make sim-validate
make sim-run
```

Install the core system as a CLI:

```bash
python3 -m pip install -e .
gxlf-sim run --max-nodes 12
```

Run the whole flow:

```bash
gxlf-sim run
```

Write device lifecycle events to JSONL for timeline rendering:

```bash
gxlf-sim run --lifecycle-log gxlf_sim_system/output/lifecycle.jsonl
```

Render the lifecycle log as an interactive HTML timeline:

```bash
gxlf-sim timeline \
  --input gxlf_sim_system/output/lifecycle.jsonl \
  --output gxlf_sim_system/output/timeline.html
```

Validate the three model files against the simulator indexes:

```bash
gxlf-sim validate
```

## Current Scope

Implemented:

- Loads all three YAML model files.
- Builds service instances from `system_instance_catalog`.
- Resolves `broadcast`, `by_beam_group`, and `by_beam_line` fan-out targets.
- Validates node commands against service state-machine templates.
- Simulates Tango-style command request and accept response payloads.
- Simulates immediate command acceptance plus later task-state callbacks.
- Executes the GXLF DAG with `all_success` aggregation.
- Tracks per-instance `health_state`, `business_state`, and task history.
- Writes per-device lifecycle events as JSONL for later timeline rendering.

## Runtime Compatibility Notes

The simulator keeps the source YAML files unchanged, but it applies a few narrow
compatibility rules so the current draft models can drive a full golden-path run:

- `多程放大系统` in the flow is treated as an alias of `多程放大组件` in the
  state-machine model.
- `ShotMotionReady` is treated as an alias of `MeasShotMotionReady`.
- Sync recipe commands are treated as cumulative recipe-loading facts because
  the current sync template models several independent recipe flags as one
  scalar `business_state`.
- The second `SyncTrigger` is allowed after the first trigger has reached
  `triggered`, covering the main-shot and pre-ionization trigger chains.
- Warning music/light commands are idempotent while the warning is already
  active.
- `TargetDataAnalysis` can read archived target data after `TargetRetract`; the
  validator still warns that N53 and N54 lack an explicit ordering dependency.

Not implemented yet:

- Real PyTango device servers.
- Full permission service integration.
- Real Tango event subscription.
- Full operator pending UI and manual decision workflow.
- Full safety interlock watchdog and compensation dispatch.
