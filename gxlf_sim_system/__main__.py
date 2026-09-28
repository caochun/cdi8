"""Run the deterministic success simulation for the minimal experiment."""

from .experiment import load_seed_source_experiment
from .subsystem_fsm import load_seed_source_state_machine
from .simulation import simulated_success_evidence


def main() -> int:
    machine = load_seed_source_state_machine()
    experiment = load_seed_source_experiment(machine)
    print("光纤种子源最小实验（仿真，每个动作显式回报成功）")
    for node in experiment.nodes:
        dispatch = experiment.dispatch_next()
        result = experiment.complete(
            dispatch, success=True, evidence=simulated_success_evidence(dispatch.action),
        )
        state = result.subsystem
        print(f"[{node.phase}] {node.id}: {result.status} | "
              f"{state.main_state} / {state.current_state} / {state.business_state}")
        if result.status != "succeeded":
            print(result.reason)
            return 1
    print(f"实验结果：{experiment.snapshot().status}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
