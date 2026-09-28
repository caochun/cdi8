"""Run the seed fan-out plus single SHG joint-flow demonstration."""

from .joint_fanout_experiment import load_laser_joint_fanout_experiment
from .simulation import simulated_success_evidence
from .subsystem_fsm import SubsystemStateMachine


def main() -> int:
    machines = {
        'seed_source': {
            f'seed_{index:02d}': SubsystemStateMachine.from_yaml(
                'gxlf_sim_system/models/excel-seed-source-state-machine.yaml'
            ) for index in range(1, 4)
        },
        'shg_injector': {
            'shg_01': SubsystemStateMachine.from_yaml(
                'gxlf_sim_system/models/excel-shg-injector-state-machine.yaml'
            )
        },
    }
    flow = load_laser_joint_fanout_experiment(machines)
    print('种子源 fan-out + 二倍频组件联合流程（all_success + AND）')
    while flow.snapshot().status not in {'succeeded', 'failed'}:
        dispatch = flow.dispatch_next()
        if dispatch is None:
            break
        print(f'{dispatch.node_id}: dispatched {len(dispatch.task_ids)} targets')
        for target, _task_id in dispatch.task_ids:
            result = flow.complete(dispatch, target, success=True,
                                   evidence=simulated_success_evidence(dispatch.action))
            if result is not None:
                print(f'  result={result.status}, completed={result.completed_targets}')
                if result.status != 'succeeded':
                    print(result.reason)
                    return 1
    print(f'实验结果：{flow.snapshot().status}')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
