"""
BM — 靶瞄准定位 Tango Device。

职责：实验靶预定位、光束引导、打靶后收靶。
A 阶段预定位，C 阶段收靶分析。
"""

import json
import logging
import random

from tango import AttrWriteType, DevState
from tango.server import attribute, command

from ._base import SubsystemDevice

logger = logging.getLogger(__name__)


class BMDevice(SubsystemDevice):
    """靶瞄准定位系统。"""

    targetId = attribute(
        dtype=str,
        access=AttrWriteType.READ,
        doc="当前靶标编号",
    )
    positionX = attribute(
        dtype=float,
        access=AttrWriteType.READ,
        unit="μm",
        doc="靶心 X 坐标",
    )
    positionY = attribute(
        dtype=float,
        access=AttrWriteType.READ,
        unit="μm",
    )
    positionZ = attribute(
        dtype=float,
        access=AttrWriteType.READ,
        unit="μm",
    )
    pointingError = attribute(
        dtype=float,
        access=AttrWriteType.READ,
        unit="μm",
        doc="光束指向误差（弹着点偏移）",
    )

    async def init_device(self):
        await super().init_device()
        self._target_id: str = ""
        self._pos_x: float = 0.0
        self._pos_y: float = 0.0
        self._pos_z: float = 0.0
        self._pointing_error: float = 0.0

    async def read_targetId(self) -> str:
        return self._target_id

    async def read_positionX(self) -> float:
        return self._pos_x

    async def read_positionY(self) -> float:
        return self._pos_y

    async def read_positionZ(self) -> float:
        return self._pos_z

    async def read_pointingError(self) -> float:
        return self._pointing_error

    @command(dtype_in=str, doc_in="靶标编号")
    async def PrePosition(self, target_id: str):
        """A07: 实验靶预定位（模拟 XYZ 三轴运动）。"""
        if self.get_state() == DevState.FAULT:
            raise Exception("BM 处于故障状态，请先 Reset")
        self._target_id = target_id
        self.set_state(DevState.MOVING)
        self.set_status(f"靶 {target_id} 预定位中")

        # 模拟三轴运动（2 步，每步 0.5s）
        for step in range(2):
            await self._delay(0.5)
            self._pos_x = random.uniform(-5.0, 5.0)
            self._pos_y = random.uniform(-5.0, 5.0)
            self._pos_z = random.uniform(-2.0, 2.0)

        self._pointing_error = abs(random.gauss(0, 1.5))  # μm 级指向误差
        self.set_state(DevState.ON)
        self.set_status(
            f"靶 {target_id} 就位，"
            f"XYZ=({self._pos_x:.1f},{self._pos_y:.1f},{self._pos_z:.1f}) μm，"
            f"指向误差 {self._pointing_error:.2f} μm"
        )
        logger.info(
            "BM: 靶 %s 预定位完成，指向误差 %.2f μm", target_id, self._pointing_error
        )

    @command
    async def CollectTarget(self):
        """C06: 靶瞄收靶（发射后复位）。"""
        self.set_state(DevState.MOVING)
        self.set_status("收靶中")
        await self._delay(1.0)
        self._target_id = ""
        self.set_state(DevState.STANDBY)
        self.set_status("收靶完成，待机")
        logger.info("BM: 收靶完成")

    @command(dtype_in=str, doc_in="靶标编号")
    async def SimulateTargetPosition(self, target_id: str):
        """A阶段：模拟靶定位。
        在虚拟靶（仿真）模式下模拟靶丸定位到打靶焦点位置，
        用于无实物靶的系统联调测试，跳过实体靶运动机构动作。
        """
        if self.get_state() == DevState.FAULT:
            raise Exception("BM 处于故障状态，请先 Reset")
        self._target_id = target_id
        self._pos_x = 0.0
        self._pos_y = 0.0
        self._pos_z = 0.0
        self._pointing_error = 0.0
        self.set_state(DevState.ON)
        self.set_status(f"模拟靶 {target_id} 定位完成（仿真）")
        logger.info("BM: 模拟靶定位完成，target_id=%s", target_id)

    @command
    async def BeamGuide(self):
        """A阶段：光束引导。
        利用靶瞄系统对激光光束进行引导，通过对比光束焦斑位置与
        靶丸位置，输出指向修正量给 DCF 或偏转镜，实现精密瞄准。
        """
        if self.get_state() != DevState.ON:
            raise Exception(f"BM 未就绪（当前 {self.get_state()}）")
        self.set_state(DevState.MOVING)
        self.set_status("光束引导中")
        await self._delay(0.5)
        self._pointing_error = abs(random.gauss(0, 0.5))  # 引导后误差更小
        self.set_state(DevState.ON)
        self.set_status(f"光束引导完成，指向误差 {self._pointing_error:.2f} μm")
        logger.info("BM: 光束引导完成，指向误差 %.2f μm", self._pointing_error)

    @command
    async def ResetTarget(self):
        """A阶段：实验靶复位。
        将实验靶架复位到初始装载位置，用于更换靶丸或
        在发次中止后将靶室恢复至安全状态。
        """
        self.set_state(DevState.MOVING)
        self.set_status("实验靶复位中")
        await self._delay(1.0)
        self._target_id = ""
        self._pos_x = 0.0
        self._pos_y = 0.0
        self._pos_z = 0.0
        self.set_state(DevState.STANDBY)
        self.set_status("实验靶已复位")
        logger.info("BM: 实验靶复位完成")

    @command
    async def ConfirmShotState(self):
        """A阶段：打靶状态确认。
        在 A 阶段末尾对靶瞄系统的打靶就绪状态进行最终确认，
        包括靶丸位置、光束引导结果、指向误差是否满足打靶判据，
        全部通过后方可进入 B 阶段发射。
        """
        if self.get_state() != DevState.ON:
            raise Exception(f"BM 未就绪（当前 {self.get_state()}）")
        if not self._target_id:
            raise Exception("BM: 未装载靶标，无法确认打靶状态")
        logger.info("BM: 打靶状态确认通过，靶 %s，指向误差 %.2f μm",
                    self._target_id, self._pointing_error)

    @command(dtype_out=str, doc_out="靶瞄数据分析结果（JSON 编码）")
    async def AnalyzeData(self) -> str:
        """C阶段：靶瞄数据分析。
        发次结束后对靶瞄系统采集的打靶过程数据进行分析，
        包括弹着点偏移、焦斑尺寸、指向抖动等，
        输出分析结果供归档和精度评估使用。
        """
        data = {
            "target_id": self._target_id,
            "pointing_error_um": round(self._pointing_error, 2),
            "focal_spot_size_um": round(random.uniform(50.0, 200.0), 1),
            "hit_position_x_um": round(random.gauss(0, 2.0), 2),
            "hit_position_y_um": round(random.gauss(0, 2.0), 2),
            "pointing_jitter_um": round(abs(random.gauss(0, 1.0)), 2),
        }
        logger.info("BM: 靶瞄数据分析完成，指向误差 %.2f μm", data["pointing_error_um"])
        return json.dumps(data, ensure_ascii=False)
