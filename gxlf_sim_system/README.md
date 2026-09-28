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

Run its tests from the repository root:

```bash
make test
```
