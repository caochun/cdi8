# GXLF Contract-Driven Simulation System

This directory contains a standalone implementation of a GXLF flow engine plus
state-machine driven Tango-style service simulators.

It uses the three model files in `gxlf_sim_system/models/` as source data:

- `gxlf-firing-flow.yaml`: flow DAG and node orchestration.
- `interface-contracts.yaml`: command request/response/callback contracts.
- `service-state-machines.yaml`: service instances, health states, task states,
  business states, and command transitions.

The first implementation intentionally does not require a real Tango database or
PyTango runtime. It exposes an internal Tango-like boundary:

```text
command_inout(command_name, DevString(JSON)) -> DevString(JSON)
task status callbacks as JSON events
service health snapshots as JSON payloads
```

That keeps the flow engine and simulator contract-compatible with the YAML
models while making the system runnable in a plain Python environment.

## Run

From the repository root:

```bash
python3 -m gxlf_sim_system run --max-nodes 12
```

Run the whole flow:

```bash
python3 -m gxlf_sim_system run
```

Validate the three model files against the simulator indexes:

```bash
python3 -m gxlf_sim_system validate
```

## Current Scope

Implemented:

- Loads all three YAML model files.
- Builds service instances from `system_instance_catalog`.
- Resolves `broadcast`, `by_beam_group`, and `by_beam_line` fan-out targets.
- Validates node commands against service state-machine templates.
- Simulates Tango-style command request and accept response payloads.
- Simulates synchronous and asynchronous command completion callbacks.
- Executes the GXLF DAG with `all_success` aggregation.
- Tracks per-instance `health_state`, `business_state`, and task history.

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
