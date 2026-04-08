"""
BB — 泵浦分系统 Tango Device。

职责：为片状放大器闪光灯提供脉冲大电流（kV 级电容器组储能）。
这是装置最危险的部分（爆炸/火灾风险）。
  A 阶段：发射准备（Prepare）
  B 阶段：充电（Charge）、触发放电（Trigger）
  紧急：EmergencyStop 发出 S2 安全信号

充电故障模型：每步 0.2% 概率触发 S2，约 2% 每发次。
"""

import logging
import random

from tango import AttrWriteType, DevState
from tango.server import attribute, command

from ._base import SubsystemDevice

logger = logging.getLogger(__name__)


class BBDevice(SubsystemDevice):
    """泵浦分系统。"""

    targetEnergy = attribute(
        dtype=float,
        access=AttrWriteType.READ,
        unit="J",
        doc="发次目标泵浦能量",
    )
    actualEnergy = attribute(
        dtype=float,
        access=AttrWriteType.READ,
        unit="J",
        doc="实际放电泵浦能量",
    )
    chargeProgress = attribute(
        dtype=float,
        access=AttrWriteType.READ,
        unit="%",
        doc="充电进度（0–100%）",
    )
    chargeVoltage = attribute(
        dtype=float,
        access=AttrWriteType.READ,
        unit="V",
        doc="当前充电电压",
    )
    safetySignal = attribute(
        dtype=str,
        access=AttrWriteType.READ,
        doc="安全信号：'' = 无，'S2' = 紧急停充",
    )

    async def init_device(self):
        await super().init_device()
        self._target_energy: float = 0.0
        self._actual_energy: float = 0.0
        self._charge_progress: float = 0.0
        self._charge_voltage: float = 0.0
        self._safety_signal: str = ""
        self.set_change_event("chargeProgress", True, False)
        self.set_change_event("safetySignal", True, False)

    async def read_targetEnergy(self) -> float:
        return self._target_energy

    async def read_actualEnergy(self) -> float:
        return self._actual_energy

    async def read_chargeProgress(self) -> float:
        return self._charge_progress

    async def read_chargeVoltage(self) -> float:
        return self._charge_voltage

    async def read_safetySignal(self) -> str:
        return self._safety_signal

    @command(dtype_in=float, doc_in="目标泵浦能量 (J)")
    async def Prepare(self, target_energy: float):
        """A06: 泵浦发射准备（设置目标能量、自检）。"""
        if self.get_state() == DevState.FAULT:
            raise Exception("BB 处于故障状态，请先 Reset")
        self._target_energy = target_energy
        self.set_state(DevState.RUNNING)
        self.set_status(f"泵浦准备中，目标 {target_energy:.0f} J")
        await self._delay(0.5)
        self.set_state(DevState.ON)
        self.set_status(f"泵浦就绪，目标 {target_energy:.0f} J")
        logger.info("BB: 泵浦准备完成，目标能量 %.0f J", target_energy)

    @command
    async def Charge(self):
        """B03: 发射充电（10 步，每步 0.2% 故障概率）。"""
        if self.get_state() == DevState.FAULT:
            raise Exception("BB 处于故障状态，请先 Reset")
        self.set_state(DevState.RUNNING)
        self.set_status("充电中")
        self._charge_progress = 0.0

        for step in range(10):
            await self._delay(0.5)
            self._charge_progress = (step + 1) / 10 * 100.0
            self._charge_voltage = self._charge_progress / 100.0 * 25000.0  # 最高 25 kV
            self.push_change_event("chargeProgress", self._charge_progress)

            if random.random() < 0.002:  # 0.2%/步 ≈ 2%/发次
                await self._trigger_s2("充电过程中检测到异常，紧急停充")
                return

        self.set_state(DevState.ON)
        self.set_status(f"充电完成，{self._charge_progress:.0f}%，电压 {self._charge_voltage:.0f} V")
        logger.info("BB: 充电完成，电压 %.0f V", self._charge_voltage)

    @command
    async def Trigger(self):
        """B: 泵浦放电触发（向闪光灯放电）。"""
        if self.get_state() != DevState.ON:
            raise Exception(f"BB 未就绪（当前 {self.get_state()}）")
        self.set_state(DevState.RUNNING)
        self.set_status("放电触发中")
        await self._delay(0.05)
        self._actual_energy = self._target_energy * random.uniform(0.97, 1.03)
        self._charge_progress = 0.0
        self._charge_voltage = 0.0
        self.set_state(DevState.ON)
        self.set_status(f"放电完成，实际能量 {self._actual_energy:.1f} J")
        self.push_change_event("chargeProgress", 0.0)
        logger.info("BB: 放电完成，实际能量 %.1f J（目标 %.1f J）",
                    self._actual_energy, self._target_energy)

    @command(dtype_in=str, doc_in="停充原因")
    async def EmergencyStop(self, reason: str):
        """S2: 紧急停充（来自外部中止指令）。"""
        await self._trigger_s2(reason)

    async def _trigger_s2(self, message: str):
        """内部：触发 S2 安全信号并进入 FAULT。"""
        self._safety_signal = "S2"
        self._charge_progress = 0.0
        self._charge_voltage = 0.0
        self.set_state(DevState.FAULT)
        self.set_status(f"紧急停充: {message}")
        await self._emit_safety("S2", message)
        logger.critical("BB: S2 紧急停充 — %s", message)

    @command
    async def Reset(self):
        """复位：清除充电状态。"""
        self._safety_signal = ""
        self._charge_progress = 0.0
        self._charge_voltage = 0.0
        self._actual_energy = 0.0
        await super().Reset()

    @command
    async def ChargeReady(self):
        """B阶段：发射充电准备。
        在开始充电前进行预检查，确认电容器组状态、
        冷却水温度、绝缘状态均满足充电要求，
        为后续 Charge() 指令做好准备。
        """
        if self.get_state() == DevState.FAULT:
            raise Exception("BB 处于故障状态，请先 Reset")
        if self.get_state() != DevState.ON:
            raise Exception(f"BB 未就绪（当前 {self.get_state()}），请先执行 Prepare")
        self.set_status("发射充电准备完成，可执行充电")
        logger.info("BB: 发射充电准备完成，目标能量 %.0f J", self._target_energy)

    @command
    async def PrepareTrigger(self):
        """B阶段：泵浦触发准备。
        充电完成后进行触发时序预置，包括触发延迟配置与
        闪光灯触发电路就绪确认，确保与 JZT 触发同步。
        """
        if self.get_state() != DevState.ON:
            raise Exception(f"BB 未就绪（当前 {self.get_state()}），请先完成充电")
        self.set_status("泵浦触发准备完成，等待 Trigger 指令")
        logger.info("BB: 触发准备完成，电压 %.0f V", self._charge_voltage)

    @command
    async def PreionizeReady(self):
        """C阶段：预电离发射准备。
        为预电离放电回路进行准备，设置预电离脉冲参数，
        确保闪光灯在主放电前完成均匀预电离，提高放电稳定性。
        """
        if self.get_state() == DevState.FAULT:
            raise Exception("BB 处于故障状态，请先 Reset")
        self.set_status("预电离发射准备完成")
        logger.info("BB: 预电离发射准备完成")

    @command
    async def PreionizeCharge(self):
        """C阶段：预电离发射充电。
        对预电离回路电容充电至预定电压，
        为下一发次的闪光灯预电离提供能量。
        """
        if self.get_state() == DevState.FAULT:
            raise Exception("BB 处于故障状态，请先 Reset")
        self.set_state(DevState.RUNNING)
        self.set_status("预电离回路充电中")
        await self._delay(0.3)
        self.set_state(DevState.ON)
        self.set_status("预电离充电完成")
        logger.info("BB: 预电离充电完成")

    @command(dtype_out=str, doc_out="泵浦数据（JSON 编码）")
    async def ReadPumpData(self) -> str:
        """C阶段：泵浦数据采集。
        发次结束后读取泵浦分系统的放电能量、电压波形等测量数据，
        供发次后归档与效率分析使用。
        """
        data = {
            "actual_energy_j": round(self._actual_energy, 2),
            "target_energy_j": round(self._target_energy, 2),
            "peak_voltage_v": round(self._charge_voltage * random.uniform(0.9, 1.0), 1),
            "pulse_count": 1,
            "efficiency": round(
                self._actual_energy / self._target_energy if self._target_energy > 0 else 0.0,
                4,
            ),
        }
        logger.info("BB: 泵浦数据采集完成，实际能量 %.1f J", self._actual_energy)
        return json.dumps(data, ensure_ascii=False)

    @command
    async def PowerOffReset(self):
        """B/C阶段：关机复位。
        将泵浦分系统安全关机：释放储能电容残余电荷、
        关闭高压电源、冷却系统保持运行。
        用于计划性停机或紧急情况下的安全关断。
        """
        self._charge_progress = 0.0
        self._charge_voltage = 0.0
        self._actual_energy = 0.0
        self._safety_signal = ""
        self.set_state(DevState.STANDBY)
        self.set_status("泵浦分系统已关机复位")
        self.push_change_event("chargeProgress", 0.0)
        logger.info("BB: 关机复位完成")

    @command
    async def Standby(self):
        """C阶段：泵浦待机。
        发次完成后将泵浦分系统切换至低功耗待机状态，
        维持冷却循环但停止高压充电，等待下一发次指令。
        """
        self._charge_progress = 0.0
        self.set_state(DevState.STANDBY)
        self.set_status("泵浦分系统待机中")
        self.push_change_event("chargeProgress", 0.0)
        logger.info("BB: 切换待机状态")
