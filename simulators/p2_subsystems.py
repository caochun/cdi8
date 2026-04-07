"""
BM  — 靶瞄准定位模拟器
LDB — LD 靶分系统模拟器
LK  — 冷空系统模拟器
MX  — 模型校准模拟器
"""
import asyncio
import logging
import random
from dataclasses import dataclass, field

from .base import BaseSimulator, SimState

logger = logging.getLogger(__name__)


# ── BM ───────────────────────────────────────────────────────────

class BMSimulator(BaseSimulator):
    """靶瞄准定位模拟器。靶预定位、光束引导。"""

    def __init__(self, sim_speed: float = 1.0):
        super().__init__("BM", sim_speed)
        self._target_id: str = ""
        self._position: tuple[float, float, float] = (0.0, 0.0, 0.0)
        self._alignment_error: float = 999.0   # μm
        self._positioned: bool = False
        self._guidance_active: bool = False

    @property
    def positioned(self) -> bool:
        return self._positioned

    @property
    def alignment_error(self) -> float:
        return self._alignment_error

    @property
    def guidance_active(self) -> bool:
        return self._guidance_active

    async def pre_position(self, target_id: str):
        """A07: 靶预定位/引导。"""
        if self._state == SimState.FAULT:
            raise RuntimeError("BM 处于故障状态")
        self._target_id = target_id
        self._positioned = False
        await self._transition(SimState.MOVING, f"靶定位中: {target_id}")
        await self._delay(3.0)
        self._position = (
            random.uniform(-0.5, 0.5),
            random.uniform(-0.5, 0.5),
            0.0,
        )
        self._alignment_error = random.uniform(1.0, 5.0)   # μm
        self._positioned = True
        await self._transition(SimState.READY, f"靶已定位，误差: {self._alignment_error:.2f} μm")
        await self._emit("target_positioned", self.name, target_id)

    async def guidance_beam_on(self):
        """开启引导光束。"""
        self._guidance_active = True
        logger.info("BM: 引导光束已开启")

    async def guidance_beam_off(self):
        """关闭引导光束。"""
        self._guidance_active = False
        logger.info("BM: 引导光束已关闭")

    async def home(self):
        """回原点（发射后收靶）。"""
        await self._transition(SimState.MOVING, "回原点中")
        await self._delay(2.0)
        self._positioned = False
        self._guidance_active = False
        await self._transition(SimState.STANDBY, "已回原点")

    async def reset(self):
        self._positioned = False
        self._guidance_active = False
        await super().reset()


# ── LDB ──────────────────────────────────────────────────────────

class LDBSimulator(BaseSimulator):
    """LD 靶分系统模拟器。液态氘靶专用流程。"""

    def __init__(self, sim_speed: float = 1.0):
        super().__init__("LDB", sim_speed)
        self._deployed: bool = False
        self._fuel_pressure: float = 0.0   # MPa
        self._temperature: float = 293.0   # K

    @property
    def deployed(self) -> bool:
        return self._deployed

    @property
    def fuel_pressure(self) -> float:
        return self._fuel_pressure

    @property
    def temperature(self) -> float:
        return self._temperature

    async def prepare_target(self):
        """靶准备：预冷、加压。"""
        await self._transition(SimState.RUNNING, "LD 靶预冷加压")
        await self._delay(5.0)
        self._temperature = random.uniform(18.0, 22.0)   # K，接近液氘温度
        self._fuel_pressure = random.uniform(0.1, 0.5)
        await self._transition(SimState.READY, f"LD 靶就绪，温度: {self._temperature:.1f} K")

    async def deploy_target(self):
        """B16: LD 靶就位到靶点。"""
        if self._state != SimState.READY:
            raise RuntimeError(f"LDB 未就绪，当前状态: {self._state}")
        await self._transition(SimState.MOVING, "LD 靶就位中")
        await self._delay(1.0)
        self._deployed = True
        await self._transition(SimState.READY, "LD 靶已就位")
        await self._emit("target_deployed", self.name)

    async def retract_target(self):
        """发射后收靶。"""
        await self._transition(SimState.MOVING, "收靶中")
        await self._delay(1.0)
        self._deployed = False
        self._fuel_pressure = 0.0
        await self._transition(SimState.STANDBY, "LD 靶已收回")

    async def reset(self):
        self._deployed = False
        self._fuel_pressure = 0.0
        await super().reset()


# ── LK ───────────────────────────────────────────────────────────

class LKSimulator(BaseSimulator):
    """冷空系统模拟器。放大片吹扫冷却。"""

    def __init__(self, sim_speed: float = 1.0):
        super().__init__("LK", sim_speed)
        self._purge_flow_rate: float = 0.0   # L/min
        self._temperature: float = 20.0      # °C
        self._purging: bool = False

    @property
    def purge_flow_rate(self) -> float:
        return self._purge_flow_rate

    @property
    def temperature(self) -> float:
        return self._temperature

    @property
    def purging(self) -> bool:
        return self._purging

    async def start_purge(self):
        """C03: 吹扫指令。"""
        if self._state == SimState.FAULT:
            raise RuntimeError("LK 处于故障状态")
        self._purging = True
        self._purge_flow_rate = 50.0   # L/min
        await self._transition(SimState.RUNNING, "片放吹扫中")
        await self._delay(3.0)
        self._temperature = max(18.0, self._temperature - 3.0)
        await self._emit("purge_done", self.name)

    async def stop_purge(self):
        """停止吹扫。"""
        self._purging = False
        self._purge_flow_rate = 0.0
        await self._transition(SimState.STANDBY, "吹扫停止")

    async def reset(self):
        self._purging = False
        self._purge_flow_rate = 0.0
        await super().reset()


# ── MX ───────────────────────────────────────────────────────────

class MXSimulator(BaseSimulator):
    """模型校准模拟器。实测数据反校激光模型，为下一发次服务。"""

    def __init__(self, sim_speed: float = 1.0):
        super().__init__("MX", sim_speed)
        self._model_version: int = 0
        self._correction_factors: dict[str, float] = {}
        self._calibration_status: str = "未校准"

    @property
    def model_version(self) -> int:
        return self._model_version

    @property
    def correction_factors(self) -> dict[str, float]:
        return dict(self._correction_factors)

    @property
    def calibration_status(self) -> str:
        return self._calibration_status

    async def start_calibration(self, shot_data: dict):
        """
        C05: 计算指令。输入本发次实测数据，输出修正系数。
        shot_data: 包含 CLY/WLZD 测量结果的字典。
        """
        if self._state == SimState.FAULT:
            raise RuntimeError("MX 处于故障状态")
        await self._transition(SimState.RUNNING, "模型反演计算中")
        await self._delay(2.0)

        # 模拟计算修正系数
        self._correction_factors = {
            "yf_gain_correction": random.uniform(0.98, 1.02),
            "dcf_gain_correction": random.uniform(0.98, 1.02),
            "plz_efficiency_correction": random.uniform(0.99, 1.01),
        }
        self._model_version += 1
        self._calibration_status = f"已校准 (v{self._model_version})"
        await self._transition(SimState.READY, self._calibration_status)
        await self._emit("model_updated", self.name, self._model_version, self._correction_factors)

    async def reset(self):
        self._calibration_status = "未校准"
        await super().reset()
