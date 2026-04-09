"""
KG — 开关驱动源 Tango Device。

职责：控制多程放大器中的电光开关，
      实现激光脉冲的精确开关与整形。
  B 阶段：充电至目标电压（Charge），触发脉冲（Trigger）
  C 阶段：复位（Reset）
"""

import json
import logging
import random

from tango import AttrWriteType, DevState
from tango.server import attribute, command

from ._base import SubsystemDevice

logger = logging.getLogger(__name__)


class KGDevice(SubsystemDevice):
    """开关驱动源。"""

    targetVoltage = attribute(
        dtype=float,
        access=AttrWriteType.READ,
        unit="kV",
        doc="开关充电目标电压",
    )
    actualVoltage = attribute(
        dtype=float,
        access=AttrWriteType.READ,
        unit="kV",
        doc="当前实际充电电压",
    )
    chargeProgress = attribute(
        dtype=float,
        access=AttrWriteType.READ,
        unit="%",
        doc="充电进度（0–100%）",
    )

    async def init_device(self):
        await super().init_device()
        self._target_voltage: float = 0.0
        self._actual_voltage: float = 0.0
        self._charge_progress: float = 0.0
        self.set_change_event("chargeProgress", True, False)
        self.set_change_event("actualVoltage", True, False)

    async def read_targetVoltage(self) -> float:
        return self._target_voltage

    async def read_actualVoltage(self) -> float:
        return self._actual_voltage

    async def read_chargeProgress(self) -> float:
        return self._charge_progress

    @command(dtype_in=float, doc_in="目标充电电压 (kV)")
    async def Charge(self, target_voltage: float):
        """B04: 开关充电（8 步线性充电）。"""
        if self.get_state() == DevState.FAULT:
            raise Exception("KG 处于故障状态，请先 Reset")
        self._target_voltage = target_voltage
        self.set_state(DevState.RUNNING)
        self.set_status(f"Charging, target {target_voltage:.1f} kV")

        for step in range(8):
            await self._delay(0.3)
            self._charge_progress = (step + 1) / 8 * 100.0
            self._actual_voltage = target_voltage * self._charge_progress / 100.0
            self.push_change_event("chargeProgress", self._charge_progress)
            self.push_change_event("actualVoltage", self._actual_voltage)

        self.set_state(DevState.ON)
        self.set_status(f"Charged, {self._actual_voltage:.2f} kV")
        logger.info("KG: 充电完成，%.2f kV", self._actual_voltage)

    @command
    async def Trigger(self):
        """B: 开关触发（输出高压脉冲）。"""
        if self.get_state() != DevState.ON:
            raise Exception(f"KG 未就绪（当前 {self.get_state()}）")
        self.set_state(DevState.RUNNING)
        self.set_status("Switch triggering")
        await self._delay(0.02)
        # 触发后电压放电到零
        self._actual_voltage *= random.uniform(0.0, 0.05)  # 残压
        self._charge_progress = 0.0
        self.set_state(DevState.ON)
        self.set_status("Switch trigger done")
        self.push_change_event("chargeProgress", 0.0)
        logger.info("KG: 开关触发完成")

    @command
    async def Reset(self):
        """C09: 开关复位，放电至安全状态。"""
        self._actual_voltage = 0.0
        self._charge_progress = 0.0
        self.set_state(DevState.STANDBY)
        self.set_status("Reset, standby")
        self.push_change_event("chargeProgress", 0.0)
        self.push_change_event("actualVoltage", 0.0)
        logger.info("KG: 已复位")

    @command
    async def PrepareTrigger(self):
        """B阶段：触发准备。
        在充电完成后、触发前进行触发时序预置，
        包括触发延迟参数设置与触发电路就绪确认，
        确保 Trigger() 指令下发时能以纳秒精度同步放电。
        """
        if self.get_state() != DevState.ON:
            raise Exception(f"KG 未就绪（当前 {self.get_state()}），请先完成充电（Charge）")
        self.set_status("Trigger ready, awaiting Trigger")
        logger.info("KG: 触发准备完成，电压 %.2f kV", self._actual_voltage)

    @command(dtype_out=str, doc_out="放电波形数据（JSON 编码）")
    async def ReadDischargeWaveform(self) -> str:
        """C阶段：放电波形采集。
        发次结束后采集开关放电回路的电流/电压波形，
        用于评估开关工作状态与本发次放电质量，供归档与故障诊断。
        """
        waveform = {
            "peak_voltage_kv": round(self._target_voltage * random.uniform(0.95, 1.05), 3),
            "peak_current_ka": round(random.uniform(50.0, 200.0), 2),
            "pulse_width_us": round(random.uniform(100.0, 500.0), 1),
            "rise_time_us": round(random.uniform(1.0, 5.0), 2),
            "fall_time_us": round(random.uniform(50.0, 200.0), 1),
        }
        logger.info("KG: 放电波形采集完成")
        return json.dumps(waveform, ensure_ascii=False)

    @command
    async def Standby(self):
        """C阶段：开关待机。
        发次完成后将开关驱动源切换至安全待机状态，
        释放储能电容残余电荷并关闭高压供电。
        """
        self._actual_voltage = 0.0
        self._charge_progress = 0.0
        self.set_state(DevState.STANDBY)
        self.set_status("Switch driver standby")
        self.push_change_event("chargeProgress", 0.0)
        self.push_change_event("actualVoltage", 0.0)
        logger.info("KG: 切换待机状态")
