"""
runner.py — JZGK + 实时监控 TUI 的统一入口

用法：
    # 运行一发次，5 倍速，可视化
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

from rich.console import Console
from rich.live import Live
from rich.logging import RichHandler
from rich.text import Text

from simulators import SimulatorRegistry
from simulators.monitor import MonitorUI
from jzgk import JZGK, JZGKState, ShotRecipe

console = Console()

# JZGK 状态 → 监控 UI 阶段标签
PHASE_LABEL = {
    JZGKState.IDLE:            "待机",
    JZGKState.PREPARING:       "A 发射准备",
    JZGKState.FIRING:          "B 发射",
    JZGKState.POST:            "C 后处理",
    JZGKState.EMERGENCY_STOP:  "⚠ 紧急停机",
}


async def run(shots: int = 1, sim_speed: float = 5.0, inject_fault: bool = False):
    # 静默日志（状态由 TUI 展示）
    logging.basicConfig(
        level=logging.WARNING,
        handlers=[RichHandler(console=console, show_path=False)],
        force=True,
    )
    logging.getLogger("jzgk").setLevel(logging.INFO)

    registry = SimulatorRegistry.build(sim_speed=sim_speed)
    ui = MonitorUI(registry)
    jzgk = JZGK(registry)

    # JZGK 状态变化 → 更新 UI 阶段标签
    def on_phase(old, new):
        ui.set_phase(PHASE_LABEL.get(new, new.value))

    jzgk.on_phase_change(on_phase)

    records: list = []

    async def shot_loop():
        await asyncio.sleep(0.3)   # 等 UI 先渲染
        for i in range(shots):
            recipe = ShotRecipe(recipe_id=f"SHOT_{i + 1:03d}")

            if inject_fault and i == 0:
                # 监听 FIRING 阶段开始后 0.5s 注入 S1（此时 AQ 已 ARMED）
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

    with Live(ui.render(), refresh_per_second=10, screen=True, console=console) as live:
        task = asyncio.create_task(shot_loop())
        while not task.done():
            live.update(ui.render())
            await asyncio.sleep(0.1)
        live.update(ui.render())
        await asyncio.sleep(1.5)   # 停留让用户看到最终状态

    # Live 结束后打印结果（screen=True 会清屏，必须在 with 块外打印）
    await task   # 传播异常
    for record in records:
        _print_record(record)
    _print_summary(jzgk)


def _print_record(record):
    status = "[bold green]成功[/]" if record.success else "[bold red]中止[/]"
    console.print(
        f"\n发次 #{record.shot_id} {status}  "
        f"A={record.phase_a_elapsed:.1f}s "
        f"B={record.phase_b_elapsed:.1f}s "
        f"C={record.phase_c_elapsed:.1f}s  "
        f"UV={record.uv_energy_j:.0f} J  "
        f"中子={record.neutron_count}"
    )
    if not record.success:
        console.print(f"  中止原因: [{record.abort_reason.value}] {record.abort_detail}", style="red")
    if record.step_timings:
        _print_step_timings(record.step_timings)


def _print_step_timings(timings: dict[str, float]):
    """按阶段分组打印每步耗时。"""
    from itertools import groupby
    by_phase: dict[str, list[tuple[str, float]]] = {}
    for key, elapsed in timings.items():
        prefix, name = key.split(".", 1)
        by_phase.setdefault(prefix, []).append((name, elapsed))

    for phase in ("a", "b", "c"):
        steps = by_phase.get(phase)
        if not steps:
            continue
        parts = [f"{name}:{elapsed:.2f}s" for name, elapsed in steps]
        console.print(f"  [{phase.upper()}] " + "  ".join(parts), style="dim")


def _print_summary(jzgk: JZGK):
    history = jzgk.history
    if not history:
        return
    success = sum(1 for r in history if r.success)
    console.rule("发次汇总")
    console.print(f"总发次: {len(history)}  成功: {success}  中止: {len(history) - success}")
    if success:
        avg_energy = sum(r.uv_energy_j for r in history if r.success) / success
        avg_neutron = sum(r.neutron_count for r in history if r.success) / success
        console.print(f"平均 UV 能量: {avg_energy:.0f} J  平均中子数: {avg_neutron:.0f}")


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
