"""
runner.py — JZGK 统一入口

用法：
    # 运行一发次，5 倍速
    python -m jzgk.runner

    # 多发次
    python -m jzgk.runner --shots 3 --speed 10

    # 测试安全联锁：在 B 阶段随机触发 S1
    python -m jzgk.runner --inject-fault
"""
import argparse
import asyncio
import logging
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from simulators import SimulatorRegistry
from jzgk import JZGK, JZGKState, ShotRecipe


async def run(shots: int = 1, sim_speed: float = 5.0, inject_fault: bool = False):
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)-7s %(name)s  %(message)s",
        force=True,
    )

    async with SimulatorRegistry.in_process(sim_speed=sim_speed) as registry:
        jzgk = JZGK(registry)
        records: list = []

        for i in range(shots):
            recipe = ShotRecipe(recipe_id=f"SHOT_{i + 1:03d}")

            if inject_fault and i == 0:
                def _schedule_intrusion(old, new):
                    if new == JZGKState.FIRING:
                        async def _do():
                            await asyncio.sleep(0.5 / sim_speed)
                            await registry.get("AQ").simulate_intrusion()
                        asyncio.ensure_future(_do())
                jzgk.on_phase_change(_schedule_intrusion)

            record = await jzgk.start_shot(recipe)
            records.append(record)
            if i < shots - 1:
                await asyncio.sleep(0.5 / sim_speed)

    for record in records:
        _print_record(record)
    _print_summary(records)


def _print_record(record):
    status = "成功" if record.success else "中止"
    print(
        f"\n发次 #{record.shot_id} {status}  "
        f"A={record.phase_a_elapsed:.1f}s "
        f"B={record.phase_b_elapsed:.1f}s "
        f"C={record.phase_c_elapsed:.1f}s  "
        f"UV={record.uv_energy_j:.0f} J  "
        f"中子={record.neutron_count}"
    )
    if not record.success:
        print(f"  中止原因: [{record.abort_reason.value}] {record.abort_detail}")
    if record.step_timings:
        _print_step_timings(record.step_timings)


def _print_step_timings(timings: dict[str, float]):
    by_phase: dict[str, list[tuple[str, float]]] = {}
    for key, elapsed in timings.items():
        prefix, name = key.split(".", 1)
        by_phase.setdefault(prefix, []).append((name, elapsed))

    for phase in ("a", "b", "c"):
        steps = by_phase.get(phase)
        if not steps:
            continue
        parts = [f"{name}:{elapsed:.2f}s" for name, elapsed in steps]
        print(f"  [{phase.upper()}] " + "  ".join(parts))


def _print_summary(records: list):
    if not records:
        return
    success = sum(1 for r in records if r.success)
    print(f"\n总发次: {len(records)}  成功: {success}  中止: {len(records) - success}")
    if success:
        avg_energy = sum(r.uv_energy_j for r in records if r.success) / success
        avg_neutron = sum(r.neutron_count for r in records if r.success) / success
        print(f"平均 UV 能量: {avg_energy:.0f} J  平均中子数: {avg_neutron:.0f}")


async def main():
    parser = argparse.ArgumentParser(description="GXLF 集中管控")
    parser.add_argument("--shots",        type=int,   default=1,   help="发次数量")
    parser.add_argument("--speed",        type=float, default=5.0, help="仿真速度倍率")
    parser.add_argument("--inject-fault", action="store_true",     help="注入 S1 故障测试安全联锁")
    args = parser.parse_args()

    await run(shots=args.shots, sim_speed=args.speed, inject_fault=args.inject_fault)


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
