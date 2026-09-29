# Excel-derived subsystem state-machine simulation

This branch contains an executable state machine for the **光纤种子源组件**,
derived from rows 3–13 of the workbook
`20260928集中管控系统与分系统关键状态梳理清单.xlsx`.

The model is stored in `models/excel-seed-source-state-machine.yaml` and is
executed by `subsystem_fsm.py`.

`experiment.py` executes the seven-node sequential experiment defined in
`models/seed-source-experiment.yaml`. Dispatch and simulated completion are
separate operations; the next node is available only after verified success.
Run the success simulation with `python3 -m gxlf_sim_system`.

Model 2.0 preserves column O as `state_definition` and requires task-specific
`CompletionEvidence` before successful completion. Generic exceptions interrupt
active actions, and recovery requires verified cause clearance and safe reset.
See the repository README for API examples and the explicit modeling assumptions.

Step 3 adds `excel-shg-injector-state-machine.yaml` and
`laser-joint-experiment.yaml`. The composite executor keeps both subsystem
machines independent and evaluates the joint readiness gate with AND semantics.

Step 4 adds `parallel_experiment.py` and a three-instance fan-out demo. The
node dispatches concurrently and uses `all_success`: one failed or exceptional
instance fails the whole node, and late results are rejected.

Before Step 5, the joint flow is also available as a combined model:
`joint_fanout_experiment.py` runs three seed-source instances as one
`all_success` group and one SHG instance, then applies the cross-subsystem AND
gate. This remains sequential across nodes; timeout and compensation are added in Step 5 below.

Step 5 adds deterministic timeout checks, declared fault injection, a global
interlock event, and priority-ordered compensation. Compensation returns
subsystems to a safe state but never changes a failed flow back to success.

Run its tests from the repository root:

```bash
make test
```

Step 6 provides a loopback operator console and append-only JSONL journal for
the joint fan-out experiment. Run `make operator` and open
`http://127.0.0.1:8765`. Replay is read-only and does not dispatch commands.
See [the operator guide](../reviews/operator-console.md) for scope and limitations.

The operator's joint fan-out model (v1.1) now includes both subsystems'
collection, normal standby/reset and shutdown: 14 action nodes plus one gate.
Successful emission no longer ends this experiment. The earlier two-single-
instance demo remains a preparation/emission example.
