"""
SimulatorRegistry — 统一管理所有子系统模拟器实例。

用法：
    registry = SimulatorRegistry.build()
    jzt = registry.get("JZT")
    await jzt.load_recipe("RECIPE_001")

    # 订阅所有子系统的安全信号
    registry.on_safety(my_handler)
"""
import logging
from typing import Callable, Any

from .base import BaseSimulator
from .jzt import JZTSimulator
from .aq import AQSimulator
from .bb import BBSimulator
from .yf import YFSimulator
from .dcf import DCFSimulator
from .zk import ZKSimulator
from .plz import PLZSimulator
from .kg import KGSimulator
from .cly import CLYSimulator
from .wlzd import WLZDSimulator
from .zzy_epj import ZZYSimulator, EPJSimulator
from .p2_subsystems import BMSimulator, LDBSimulator, LKSimulator, MXSimulator

logger = logging.getLogger(__name__)


class SimulatorRegistry:
    """所有子系统模拟器的注册表。"""

    def __init__(self):
        self._subsystems: dict[str, BaseSimulator] = {}

    def register(self, sim: BaseSimulator) -> "SimulatorRegistry":
        self._subsystems[sim.name] = sim
        return self

    def get(self, name: str) -> BaseSimulator:
        if name not in self._subsystems:
            raise KeyError(f"未知子系统: {name!r}。已注册: {list(self._subsystems)}")
        return self._subsystems[name]

    def all(self) -> dict[str, BaseSimulator]:
        return dict(self._subsystems)

    def on_safety(self, handler: Callable[..., Any]):
        """订阅所有子系统的安全信号（S1/S2/S3）。"""
        for sim in self._subsystems.values():
            sim.on("safety", handler)

    def on_state_changed(self, handler: Callable[..., Any]):
        """订阅所有子系统的状态变化事件。"""
        for sim in self._subsystems.values():
            sim.on("state_changed", handler)

    @classmethod
    def build(cls, sim_speed: float = 1.0) -> "SimulatorRegistry":
        """
        构建标准的全套模拟器实例。

        Args:
            sim_speed: 仿真速度倍率（全局）。10.0 = 10倍速，适合快速测试。
        """
        registry = cls()
        for sim in [
            # P0
            JZTSimulator(sim_speed),
            AQSimulator(sim_speed),
            # P1
            BBSimulator(sim_speed),
            YFSimulator(sim_speed),
            DCFSimulator(sim_speed),
            ZKSimulator(sim_speed),
            PLZSimulator(sim_speed),
            KGSimulator(sim_speed),
            CLYSimulator(sim_speed),
            WLZDSimulator(sim_speed),
            # P2
            ZZYSimulator(sim_speed),
            EPJSimulator(sim_speed),
            BMSimulator(sim_speed),
            LDBSimulator(sim_speed),
            LKSimulator(sim_speed),
            MXSimulator(sim_speed),
        ]:
            registry.register(sim)
        logger.info("SimulatorRegistry: 已注册 %d 个子系统", len(registry._subsystems))
        return registry
