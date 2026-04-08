"""
CLY — 测量取样 Tango Device。

职责：62 路在线测量，采集束线的能量、波形、近场、远场、波前。
  A 阶段：配置采样参数（Setup）
  B 阶段：启动采样（StartSampling）
  C 阶段：读取结果（ReadResults）
"""

import json
import logging
import random

from tango import AttrWriteType, DevState
from tango.server import attribute, command

from ._base import SubsystemDevice

logger = logging.getLogger(__name__)


class CLYDevice(SubsystemDevice):
    """测量取样分系统。"""

    channelCount = attribute(
        dtype=int,
        access=AttrWriteType.READ,
        doc="已配置的采样通道数",
    )
    lastResults = attribute(
        dtype=str,
        access=AttrWriteType.READ,
        doc="最近一次采集结果（JSON 编码）",
    )

    async def init_device(self):
        await super().init_device()
        self._channel_count: int = 0
        self._sampling_config: dict = {}
        self._last_results: dict = {}

    async def read_channelCount(self) -> int:
        return self._channel_count

    async def read_lastResults(self) -> str:
        return json.dumps(self._last_results, ensure_ascii=False)

    @command(dtype_in=str, doc_in="采样配置（JSON 编码）")
    async def Setup(self, config_json: str):
        """A09: 测量打靶运动准备/测量靶瞄准备。"""
        if self.get_state() == DevState.FAULT:
            raise Exception("CLY 处于故障状态，请先 Reset")
        try:
            self._sampling_config = json.loads(config_json)
        except json.JSONDecodeError:
            self._sampling_config = {}
        self.set_state(DevState.RUNNING)
        await self._delay(0.3)
        # 模拟通道数（实际有 62 路，这里用配置数或默认值）
        self._channel_count = self._sampling_config.get("channels", 62)
        self.set_state(DevState.ON)
        self.set_status(f"采样配置完成，{self._channel_count} 路通道")
        logger.info("CLY: 采样配置完成，%d 路", self._channel_count)

    @command
    async def StartSampling(self):
        """B13: 启动采样（同步 JZT 触发）。"""
        if self.get_state() == DevState.FAULT:
            raise Exception("CLY 处于故障状态，请先 Reset")
        self.set_state(DevState.RUNNING)
        self.set_status("采样中")
        await self._delay(0.1)
        self.set_state(DevState.ON)
        self.set_status("采样就绪，等待触发")
        logger.info("CLY: 采样就绪")

    @command(dtype_out=str, doc_out="激光参数测量结果（JSON 编码）")
    async def ReadResults(self) -> str:
        """C08: 采集激光参数（能量/波形/近场/远场）。"""
        self.set_state(DevState.RUNNING)
        self.set_status("读取测量数据中")
        await self._delay(0.5)

        self._last_results = {
            "energy_kj": round(random.uniform(1.5, 3.5), 4),
            "pulse_width_ns": round(random.uniform(2.0, 4.0), 2),
            "near_field_uniformity": round(random.uniform(0.80, 0.95), 3),
            "far_field_divergence_urad": round(random.uniform(50, 120), 1),
            "wavefront_rms_lambda": round(random.uniform(0.05, 0.15), 3),
        }
        self.set_state(DevState.ON)
        self.set_status("测量数据采集完成")
        result_json = json.dumps(self._last_results, ensure_ascii=False)
        logger.info("CLY: 测量完成，能量 %.4f kJ", self._last_results["energy_kj"])
        return result_json

    @command
    async def MeasureTargetReady(self):
        """A阶段：测量靶瞄准备。
        配置测量系统用于靶瞄过程的监测参数，
        包括靶瞄相机触发时序、焦斑测量仪初始化等，
        确保 A 阶段靶瞄过程中的光束质量实时监测。
        """
        if self.get_state() == DevState.FAULT:
            raise Exception("CLY 处于故障状态，请先 Reset")
        self.set_state(DevState.RUNNING)
        self.set_status("测量靶瞄准备中")
        await self._delay(0.2)
        self.set_state(DevState.ON)
        self.set_status("测量靶瞄准备完成")
        logger.info("CLY: 测量靶瞄准备完成")

    @command
    async def MeasureMotionReady(self):
        """A阶段：测量打靶运动准备。
        配置测量系统用于靶室运动机构动作过程中的监测，
        确认各测量仪器已收到待机指令或已退出光路，
        避免靶室运动期间测量设备受损。
        """
        if self.get_state() == DevState.FAULT:
            raise Exception("CLY 处于故障状态，请先 Reset")
        self.set_state(DevState.RUNNING)
        self.set_status("测量打靶运动准备中")
        await self._delay(0.2)
        self.set_state(DevState.ON)
        self.set_status("测量打靶运动准备完成")
        logger.info("CLY: 测量打靶运动准备完成")

    @command
    async def MeasurementReady(self):
        """B阶段：打靶测量准备。
        在 B 阶段充电完成后，将所有测量仪器切换至采集就绪状态，
        包括示波器触发等待、相机门控开启等，
        确保打靶时刻能完整采集所有束线参数。
        """
        if self.get_state() == DevState.FAULT:
            raise Exception("CLY 处于故障状态，请先 Reset")
        self.set_state(DevState.RUNNING)
        self.set_status("打靶测量准备中")
        await self._delay(0.1)
        self.set_state(DevState.ON)
        self.set_status("打靶测量准备完成，等待触发")
        logger.info("CLY: 打靶测量准备完成")

    @command
    async def Standby(self):
        """C阶段：测量组件待机。
        发次完成后将测量系统切换至低功耗待机状态，
        保存本次测量数据，关闭高压门控，等待下一发次指令。
        """
        self.set_state(DevState.STANDBY)
        self.set_status("测量组件待机中")
        logger.info("CLY: 切换待机状态")
