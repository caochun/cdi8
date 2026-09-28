"""Run the three-instance parallel fan-out demonstration."""

from .parallel_experiment import load_seed_source_fanout_experiment
from .simulation import simulated_success_evidence
from .subsystem_fsm import SubsystemStateMachine


def main() -> int:
    instances = {
        f'seed_{index:02d}': SubsystemStateMachine.from_yaml(
            'gxlf_sim_system/models/excel-seed-source-state-machine.yaml'
        )
        for index in range(1, 4)
    }
    flow = load_seed_source_fanout_experiment(instances)
    print('光纤种子源三实例并行扇出（all_success）')
    while flow.snapshot().status not in {'succeeded', 'failed'}:
        dispatch = flow.dispatch_next()
        for target, _task_id in dispatch.task_ids:
            result = flow.complete(
                dispatch, target, success=True,
                evidence=simulated_success_evidence(dispatch.action),
            )
            print(f'{dispatch.node_id}: {target} -> '
                  f'{"pending" if result is None else result.status}')
        status = dict(flow.snapshot().node_states).get(dispatch.node_id)
        print(f'节点结果：{dispatch.node_id} -> {status}')
    print(f'实验结果：{flow.snapshot().status}')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
