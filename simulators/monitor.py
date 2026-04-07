"""
monitor.py — 子系统实时状态监控 TUI

用法：
    python -m simulators.monitor

与冒烟测试一起运行（可视化整个发次流程）：
    python -m simulators.monitor --with-shot
"""
import asyncio
import sys
from collections import deque
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from rich.columns import Columns
from rich.console import Console
from rich.layout import Layout
from rich.live import Live
from rich.panel import Panel
from rich.table import Table
from rich.text import Text

from simulators import SimulatorRegistry
from simulators.base import SimState

console = Console()

# ── 状态颜色映射 ──────────────────────────────────────────────

STATE_STYLE: dict[str, str] = {
    "STANDBY":   "dim white",
    "READY":     "bold green",
    "RUNNING":   "bold yellow",
    "MOVING":    "bold yellow",
    "FAULT":     "bold red on dark_red",
    # AQ 扩展状态
    "OPEN":      "dim white",
    "CLEARING":  "yellow",
    "LOCKED":    "cyan",
    "ARMED":     "bold green",
    # BB/KG 扩展
    "CHARGING":  "yellow",
    "CHARGED":   "cyan",
    "TRIGGERED": "bold cyan",
    # 默认
    "OFF":       "dim",
}

# ── 子系统中文名 ──────────────────────────────────────────────

SUBSYSTEM_LABEL: dict[str, str] = {
    "JZT":  "集中同步",
    "AQ":   "安全联锁",
    "BB":   "泵浦分系统",
    "YF":   "预放",
    "DCF":  "主放",
    "ZK":   "真空靶室",
    "PLZ":  "频率转换",
    "KG":   "开关驱动源",
    "CLY":  "测量取样",
    "WLZD": "物理诊断",
    "ZZY":  "种子源",
    "EPJ":  "二倍频注入",
    "BM":   "靶瞄准定位",
    "LDB":  "LD靶",
    "LK":   "冷空系统",
    "MX":   "模型校准",
}

# ── 显示顺序（按关系图分组）────────────────────────────────────

DISPLAY_ORDER = [
    # 激光链路
    "ZZY", "EPJ", "YF", "DCF", "PLZ",
    # 能源与时序
    "BB", "KG", "JZT",
    # 靶场与诊断
    "BM", "ZK", "LDB", "CLY", "WLZD",
    # 保障
    "AQ", "LK", "MX",
]

GROUP_LABELS = {
    "ZZY": "── 激光链路 ──",
    "BB":  "── 能源与时序 ──",
    "BM":  "── 靶场·诊断 ──",
    "AQ":  "── 保障 ──",
}


class MonitorUI:
    def __init__(self, registry: SimulatorRegistry, max_events: int = 20):
        self.registry = registry
        self._events: deque[tuple[str, str, str, str]] = deque(maxlen=max_events)
        self._safety: deque[tuple[str, str, str]] = deque(maxlen=10)
        self._shot_phase: str = "等待"

        # 订阅所有子系统事件
        registry.on_state_changed(self._on_state_changed)
        registry.on_safety(self._on_safety)

    def set_phase(self, phase: str):
        self._shot_phase = phase

    def _on_state_changed(self, name, old_state, new_state, message):
        ts = datetime.now().strftime("%H:%M:%S")
        self._events.appendleft((ts, name, f"{old_state.value} → {new_state.value}", message))

    def _on_safety(self, name, signal_id, message):
        ts = datetime.now().strftime("%H:%M:%S")
        self._safety.appendleft((ts, f"[{signal_id}] {name}", message))

    # ── 渲染 ─────────────────────────────────────────────────

    def render(self) -> Layout:
        layout = Layout()
        layout.split_column(
            Layout(name="header", size=3),
            Layout(name="body"),
            Layout(name="footer", size=3),
        )
        layout["body"].split_row(
            Layout(name="states", ratio=2),
            Layout(name="right", ratio=3),
        )
        layout["right"].split_column(
            Layout(name="events", ratio=3),
            Layout(name="safety", ratio=1),
        )

        layout["header"].update(self._render_header())
        layout["states"].update(self._render_states())
        layout["events"].update(self._render_events())
        layout["safety"].update(self._render_safety())
        layout["footer"].update(self._render_footer())
        return layout

    def _render_header(self) -> Panel:
        phase_style = {
            "等待": "dim",
            "A 发射准备": "bold blue",
            "B 发射": "bold yellow",
            "C 后处理": "bold magenta",
            "完成": "bold green",
        }.get(self._shot_phase, "bold white")
        text = Text.assemble(
            ("GXLF 集中管控 — 子系统状态监控", "bold"),
            "    ",
            ("当前阶段: ", "dim"),
            (self._shot_phase, phase_style),
        )
        return Panel(text, style="blue")

    def _render_states(self) -> Panel:
        table = Table(
            show_header=True,
            header_style="bold dim",
            show_lines=False,
            expand=True,
            padding=(0, 1),
        )
        table.add_column("标识", width=5, no_wrap=True)
        table.add_column("子系统", width=10, no_wrap=True)
        table.add_column("状态", width=10, no_wrap=True)
        table.add_column("说明", no_wrap=True)

        for name in DISPLAY_ORDER:
            # 分组标题行
            if name in GROUP_LABELS:
                table.add_row(
                    "", GROUP_LABELS[name], "", "",
                    style="dim italic"
                )

            sim = self.registry.all().get(name)
            if sim is None:
                continue

            state_val = sim.state.value
            style = STATE_STYLE.get(state_val, "white")
            state_text = Text(state_val, style=style)

            # 截断过长的状态说明
            status = sim.status
            if len(status) > 28:
                status = status[:26] + "…"

            table.add_row(
                Text(name, style="bold"),
                SUBSYSTEM_LABEL.get(name, name),
                state_text,
                status,
            )

        return Panel(table, title="子系统状态", border_style="blue")

    def _render_events(self) -> Panel:
        table = Table(
            show_header=False,
            box=None,
            expand=True,
            padding=(0, 1),
        )
        table.add_column("时间", width=9, style="dim", no_wrap=True)
        table.add_column("子系统", width=5, style="bold", no_wrap=True)
        table.add_column("迁移", width=22, no_wrap=True)
        table.add_column("说明")

        for ts, name, transition, message in self._events:
            # 迁移方向颜色
            if "FAULT" in transition:
                t_style = "red"
            elif "READY" in transition or "ARMED" in transition:
                t_style = "green"
            elif "STANDBY" in transition.split("→")[1]:
                t_style = "dim"
            else:
                t_style = "yellow"

            # 截断说明
            if len(message) > 30:
                message = message[:28] + "…"

            table.add_row(ts, name, Text(transition, style=t_style), message)

        return Panel(table, title=f"事件日志（最近 {self._events.maxlen} 条）", border_style="blue")

    def _render_safety(self) -> Panel:
        if not self._safety:
            content = Text("  无安全信号", style="dim green")
        else:
            lines = []
            for ts, signal, message in self._safety:
                lines.append(Text.assemble(
                    (ts, "dim"),
                    "  ",
                    (signal, "bold red"),
                    "  ",
                    (message[:40], "red"),
                ))
            content = Text("\n").join(lines)

        style = "bold red" if self._safety else "green"
        return Panel(content, title="安全信号", border_style=style)

    def _render_footer(self) -> Panel:
        fault_count = sum(
            1 for s in self.registry.all().values()
            if s.state.value == "FAULT"
        )
        ready_count = sum(
            1 for s in self.registry.all().values()
            if s.state.value in ("READY", "ARMED", "STANDBY")
        )
        text = Text.assemble(
            ("就绪/待机: ", "dim"), (str(ready_count), "green"),
            "   ",
            ("故障: ", "dim"), (str(fault_count), "red" if fault_count else "green"),
            "   ",
            ("安全信号: ", "dim"), (str(len(self._safety)), "red" if self._safety else "green"),
            "   ",
            ("q 退出", "dim"),
        )
        return Panel(text, style="dim")


async def run_monitor_only(registry: SimulatorRegistry, refresh: float = 0.2):
    """独立运行监控（不驱动任何发次）。"""
    ui = MonitorUI(registry)
    with Live(ui.render(), refresh_per_second=5, screen=True, console=console) as live:
        while True:
            live.update(ui.render())
            await asyncio.sleep(refresh)


async def run_with_shot(sim_speed: float = 5.0):
    """可视化完整发次（监控 + 冒烟测试并行）。"""
    import logging
    from rich.logging import RichHandler
    from simulators.smoke_test import run_shot

    # 把所有日志重定向到 rich，避免与 Live display 冲突
    logging.basicConfig(
        level=logging.WARNING,   # 监控模式下只显示警告以上，状态变化由 UI 展示
        handlers=[RichHandler(console=console, show_path=False)],
        force=True,
    )
    # 安全信号（CRITICAL）仍然显示
    logging.getLogger("simulators.base").setLevel(logging.CRITICAL)

    registry = SimulatorRegistry.build(sim_speed=sim_speed)
    ui = MonitorUI(registry)

    async def shot_driver():
        await asyncio.sleep(0.5)   # 等 UI 先渲染一帧
        ui.set_phase("A 发射准备")
        await zk_start(registry)
        result = await run_shot(registry)
        ui.set_phase("完成")
        await asyncio.sleep(2)     # 停留 2 秒让用户看到最终状态
        return result

    async def zk_start(reg):
        await reg.get("ZK").start_monitoring()

    with Live(ui.render(), refresh_per_second=10, screen=True, console=console) as live:
        shot_task = asyncio.create_task(shot_driver())

        # 监控 UI 持续刷新，直到发次结束
        while not shot_task.done():
            # 根据事件日志猜测当前阶段（简化）
            if ui._events:
                latest = ui._events[0][2]
                if "ARMED" in latest:
                    ui.set_phase("B 发射")
                elif "校准" in ui._events[0][3]:
                    ui.set_phase("C 后处理")
            live.update(ui.render())
            await asyncio.sleep(0.1)

        live.update(ui.render())
        result = await shot_task
        return result


async def main():
    import argparse
    parser = argparse.ArgumentParser(description="GXLF 子系统状态监控")
    parser.add_argument("--with-shot", action="store_true", help="同时运行一次发次冒烟测试")
    parser.add_argument("--speed", type=float, default=5.0, help="仿真速度倍率（默认 5）")
    args = parser.parse_args()

    if args.with_shot:
        await run_with_shot(sim_speed=args.speed)
    else:
        # 纯监控模式：构建注册表，等待外部驱动
        registry = SimulatorRegistry.build(sim_speed=args.speed)
        await run_monitor_only(registry)


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
