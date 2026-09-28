"""Run the two-system sequential joint-readiness simulation."""

from .composite_experiment import load_laser_joint_experiment
from .simulation import simulated_success_evidence
from .subsystem_fsm import SubsystemStateMachine


def main() -> int:
    seed = SubsystemStateMachine.from_yaml('gxlf_sim_system/models/excel-seed-source-state-machine.yaml')
    shg = SubsystemStateMachine.from_yaml('gxlf_sim_system/models/excel-shg-injector-state-machine.yaml')
    systems = {'seed_source': seed, 'shg_injector': shg}
    experiment = load_laser_joint_experiment(systems)
    print('激光链路双分系统联合准备与出光（顺序仿真）')
    while experiment.snapshot().status not in {'succeeded', 'failed'}:
        dispatch = experiment.dispatch_next()
        result = experiment.complete(
            dispatch, success=True,
            evidence=simulated_success_evidence(dispatch.action),
        )
        state = result.states[0][1]
        print(f'{dispatch.node_id}: {result.status} | {dispatch.target} | '
              f'{state.main_state} / {state.current_state} / {state.business_state}')
        if result.status != 'succeeded':
            print(result.reason)
            return 1
    print(f'实验结果：{experiment.snapshot().status}')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
