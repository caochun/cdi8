"""
JZT — 集中同步模拟器

职责：全装置纳秒级时序同步。
在软件层面，JZT 的职责是：
  - 加载配方（A05 / B01）
  - Arm：进入待触发状态
  - Fire：广播时序触发信号给 ZZY/YF/DCF/PLZ/CLY

注意：实际纳秒级时序由硬件承担，软件层只模拟"命令下发"和"状态反馈"。

状态序列：
  STANDBY → ARMED → TRIGGERED → STANDBY
"""
import asyncio
import logging
from dataclasses import dataclass, field

from .base import BaseSimulator, SimState

logger = logging.getLogger(__name__)


@dataclass
class TimingRecipe:
    recipe_id: str
    # 各通道延迟（ns），键为通道名，值为相对触发延迟
    channels: dict[str, float] = field(default_factory=dict)

    @classmethod
    def default(cls) -> "TimingRecipe":
        """默认单发配方——各子系统的典型延迟。"""
        return cls(
            recipe_id="DEFAULT",
            channels={
                "ZZY": 0.0,
                "YF": 50.0,
                "DCF": 100.0,
                "PLZ": 150.0,
                "CLY": 200.0,
            },
        )


class JZTSimulator(BaseSimulator):
    """集中同步子系统模拟器。"""

    def __init__(self, sim_speed: float = 1.0):
        super().__init__("JZT", sim_speed)
        self._recipe: TimingRecipe = TimingRecipe.default()
        self._armed: bool = False

    # ── 属性 ────────────────────────────────────────────────

    @property
    def recipe_id(self) -> str:
        return self._recipe.recipe_id

    @property
    def armed(self) -> bool:
        return self._armed

    @property
    def channels(self) -> dict[str, float]:
        return dict(self._recipe.channels)

    # ── 命令 ────────────────────────────────────────────────

    async def load_recipe(self, recipe_id: str):
        """
        A05: 加载发射准备配方。
        recipe_id 指定预存的时序方案。
        """
        if self._state == SimState.FAULT:
            raise RuntimeError("JZT 处于故障状态，请先 reset")
        await self._transition(SimState.RUNNING, f"加载配方 {recipe_id}")
        await self._delay(0.3)
        # 模拟：根据 recipe_id 选择配方，这里统一用 default + 修改 id
        self._recipe = TimingRecipe.default()
        self._recipe.recipe_id = recipe_id
        self._armed = False
        await self._transition(SimState.STANDBY, f"配方已加载: {recipe_id}")

    async def load_single_shot(self, params: dict):
        """
        B01: 加载单发配方。params 是通道延迟字典，可覆盖默认值。
        """
        if self._state == SimState.FAULT:
            raise RuntimeError("JZT 处于故障状态，请先 reset")
        await self._transition(SimState.RUNNING, "加载单发配方")
        await self._delay(0.2)
        recipe = TimingRecipe.default()
        recipe.recipe_id = "SINGLE_SHOT"
        recipe.channels.update(params)
        self._recipe = recipe
        self._armed = False
        await self._transition(SimState.STANDBY, "单发配方已加载")

    async def arm(self):
        """进入待触发状态。"""
        if self._state != SimState.STANDBY:
            raise RuntimeError(f"JZT 无法 Arm，当前状态: {self._state}")
        await self._transition(SimState.READY, "已就绪，等待触发")
        self._armed = True

    async def fire(self):
        """
        触发时序广播。
        实际纳秒级时序由硬件完成；软件层模拟"已触发"状态变化。
        """
        if not self._armed:
            raise RuntimeError("JZT 尚未 Arm，无法触发")
        await self._transition(SimState.RUNNING, "时序触发中")
        await self._delay(0.05)  # 极短，模拟触发信号发出
        self._armed = False
        await self._transition(SimState.STANDBY, "触发完成，返回待机")
        # 通知订阅者（集中管控可在此监听"fired"事件）
        await self._emit("fired", self.name, self._recipe.recipe_id)

    async def reset(self):
        self._armed = False
        await super().reset()
