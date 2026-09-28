# Excel-derived subsystem state-machine simulation

This branch contains the first implementation step for the experimental flow
control simulator: an executable state machine for the **光纤种子源组件**,
derived from rows 3–13 of the workbook
`20260928集中管控系统与分系统关键状态梳理清单.xlsx`.

The model is stored in `models/excel-seed-source-state-machine.yaml` and is
executed by `subsystem_fsm.py`.

Run its tests from the repository root:

```bash
python3 -m unittest gxlf_sim_system.tests.test_seed_source_state_machine -v
```
