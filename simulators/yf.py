"""
YF — 再生与双程放大（预放）Tango Device。

职责：激光预放大，能量粗/精闭环控制。
  A 阶段：唤醒预热（WakeUp）
  B 阶段：接收泵浦能量（ReceivePumpEnergy），输出放大激光
  C 阶段：接受冷空吹扫（AcceptPurge）

状态序列：STANDBY → RUNNING（预热）→ ON（就绪）→ RUNNING（放大）→ ON → STANDBY（吹扫后）
"""

import logging
import random

from tango import AttrWriteType, DevState
from tango.server import attribute, command

from ._base import SubsystemDevice

logger = logging.getLogger(__name__)


class YFDevice(SubsystemDevice):
    """再生与双程放大（预放）。"""

    energySetpoint = attribute(
        dtype=float,
        access=AttrWriteType.READ_WRITE,
        unit="J",
        doc="能量闭环目标值",
    )
    measuredEnergy = attribute(
        dtype=float,
        access=AttrWriteType.READ,
        unit="J",
        doc="最近一次测量的输出能量",
    )
    temperature = attribute(
        dtype=float,
        access=AttrWriteType.READ,
        unit="°C",
        doc="放大片工作温度",
    )
    loopClosed = attribute(
        dtype=bool,
        access=AttrWriteType.READ,
        doc="True = 能量闭环已激活",
    )

    async def init_device(self):
        await super().init_device()
        self._energy_setpoint: float = 0.0
        self._measured_energy: float = 0.0
        self._temperature: float = 20.0
        self._loop_closed: bool = False
        self.set_change_event("measuredEnergy", True, False)
        self.set_change_event("temperature", True, False)

    async def read_energySetpoint(self) -> float:
        return self._energy_setpoint

    async def write_energySetpoint(self, value: float):
        self._energy_setpoint = value

    async def read_measuredEnergy(self) -> float:
        return self._measured_energy

    async def read_temperature(self) -> float:
        return self._temperature

    async def read_loopClosed(self) -> bool:
        return self._loop_closed

    @command
    async def WakeUp(self):
        """A03: 预放唤醒/预热，从待机激活预放。"""
        if self.get_state() == DevState.FAULT:
            raise Exception("YF 处于故障状态，请先 Reset")
        self.set_state(DevState.RUNNING)
        self.set_status("Preamplifier warming up")
        # 模拟预热：温度从室温升至工作温度（约 41°C）
        for step in range(6):
            await self._delay(0.5)
            self._temperature = 20.0 + step * 3.5
            self.push_change_event("temperature", self._temperature)
        self.set_state(DevState.ON)
        self.set_status(f"Preamplifier ready, temp {self._temperature:.1f}C")
        logger.info("YF: 预热完成，温度 %.1f°C", self._temperature)

    @command(dtype_in=float, dtype_out=float,
             doc_in="泵浦能量 (J)", doc_out="放大后激光能量 (J)")
    async def ReceivePumpEnergy(self, pump_energy: float) -> float:
        """B07/B11: 接收泵浦能量，执行放大，返回输出能量。"""
        self.set_state(DevState.RUNNING)
        self.set_status("Receiving pump energy, amplifying")
        await self._delay(0.1)

        gain = 0.15
        self._measured_energy = pump_energy * gain * random.uniform(0.95, 1.05)
        if self._loop_closed and self._energy_setpoint > 0:
            # 能量精闭环：向目标值靠拢
            self._measured_energy = self._energy_setpoint * random.uniform(0.98, 1.02)

        self.set_state(DevState.ON)
        self.set_status(f"Amplification done, output {self._measured_energy:.3f} J")
        self.push_change_event("measuredEnergy", self._measured_energy)
        logger.info("YF: 放大完成，泵浦 %.1f J → 输出 %.3f J", pump_energy, self._measured_energy)
        return self._measured_energy

    @command
    async def StartClosedLoop(self):
        """启动能量闭环控制。"""
        if self.get_state() != DevState.ON:
            raise Exception(f"YF 未就绪（当前 {self.get_state()}），无法启动闭环")
        self._loop_closed = True
        logger.info("YF: 能量闭环已启动，目标 %.2f J", self._energy_setpoint)

    @command
    async def AcceptPurge(self):
        """C03/C10: 接受冷空吹扫，片放冷却。"""
        self.set_state(DevState.RUNNING)
        self.set_status("Slab purge cooling")
        await self._delay(2.0)
        self._temperature = max(20.0, self._temperature - 5.0)
        self._loop_closed = False
        self.set_state(DevState.STANDBY)
        self.set_status(f"Purge done, temp {self._temperature:.1f}C")
        self.push_change_event("temperature", self._temperature)
        logger.info("YF: 吹扫完成，温度降至 %.1f°C", self._temperature)

    @command
    async def Reset(self):
        """复位：清除闭环状态后调用基类复位。"""
        self._loop_closed = False
        self._measured_energy = 0.0
        await super().Reset()

    @command
    async def ShotOutputLight(self):
        """A阶段：预放出打靶光。
        驱动预放大链输出单脉冲打靶光，用于 A 阶段光束准直验证与靶瞄确认。
        需已完成预热（WakeUp）后方可执行。
        """
        if self.get_state() != DevState.ON:
            raise Exception(f"YF 未就绪（当前 {self.get_state()}），请先 WakeUp")
        self.set_state(DevState.RUNNING)
        self.set_status("Preamplifier shot output")
        await self._delay(0.1)
        self._measured_energy = self._energy_setpoint * 0.5 if self._energy_setpoint > 0 else 0.1
        self.set_state(DevState.ON)
        self.set_status("Shot output done")
        self.push_change_event("measuredEnergy", self._measured_energy)
        logger.info("YF: 预放出打靶光，能量 %.3f J", self._measured_energy)

    @command
    async def CoarseEnergyLoop(self):
        """A阶段：能量粗闭环。
        在打靶准备阶段激活能量粗闭环控制，将输出能量调整至目标值的 ±5% 范围内，
        为后续精闭环提供初始工作点。
        """
        if self.get_state() != DevState.ON:
            raise Exception(f"YF 未就绪（当前 {self.get_state()}）")
        self.set_state(DevState.RUNNING)
        self.set_status("Coarse energy loop adjusting")
        await self._delay(0.5)
        if self._energy_setpoint > 0:
            self._measured_energy = self._energy_setpoint * random.uniform(0.95, 1.05)
        self._loop_closed = True
        self.set_state(DevState.ON)
        self.set_status(f"Coarse loop done, energy {self._measured_energy:.3f} J")
        self.push_change_event("measuredEnergy", self._measured_energy)
        logger.info("YF: 能量粗闭环完成，%.3f J", self._measured_energy)

    @command
    async def FineEnergyLoop(self):
        """A/B阶段：能量精闭环。
        激活能量精闭环控制，将输出能量精确控制在目标值的 ±1% 范围内，
        满足高精度打靶要求。通常在粗闭环（CoarseEnergyLoop）完成后调用。
        """
        if self.get_state() != DevState.ON:
            raise Exception(f"YF 未就绪（当前 {self.get_state()}）")
        self.set_state(DevState.RUNNING)
        self.set_status("Fine energy loop adjusting")
        await self._delay(0.8)
        if self._energy_setpoint > 0:
            self._measured_energy = self._energy_setpoint * random.uniform(0.99, 1.01)
        self._loop_closed = True
        self.set_state(DevState.ON)
        self.set_status(f"Fine loop done, energy {self._measured_energy:.3f} J")
        self.push_change_event("measuredEnergy", self._measured_energy)
        logger.info("YF: 能量精闭环完成，%.3f J（目标 %.3f J）",
                    self._measured_energy, self._energy_setpoint)

    @command
    async def ClearEnergyCounter(self):
        """B阶段：能量计清零。
        在 B 阶段充电完成后、触发前将能量计计数器清零，
        确保本发次测量值不受上一发次残余读数影响。
        """
        self._measured_energy = 0.0
        self.push_change_event("measuredEnergy", self._measured_energy)
        logger.info("YF: 能量计已清零")

    @command(dtype_out=str, doc_out="能量数据（JSON 编码）")
    async def ReadEnergyData(self) -> str:
        """C阶段：能量数据采集。
        发次结束后读取预放大链各级输出能量的测量数据，
        包含各放大器级次的增益和总输出能量，供发次后归档。
        """
        import json
        import random
        data = {
            "measured_energy_j": round(self._measured_energy, 4),
            "energy_setpoint_j": round(self._energy_setpoint, 4),
            "loop_closed": self._loop_closed,
            "gain_regen": round(random.uniform(1e4, 1e5), 0),
            "gain_2pass": round(random.uniform(10.0, 30.0), 2),
            "temperature_c": round(self._temperature, 2),
        }
        logger.info("YF: 能量数据采集完成")
        return json.dumps(data, ensure_ascii=False)

    @command
    async def Standby(self):
        """C阶段：预放组件待机。
        发次后将预放大链切换至低功耗待机状态，
        关闭闭环控制，维持基本泵浦但停止脉冲出光。
        """
        self._loop_closed = False
        self.set_state(DevState.STANDBY)
        self.set_status("Preamplifier standby")
        logger.info("YF: 切换待机状态")
