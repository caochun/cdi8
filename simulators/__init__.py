"""
simulators — GXLF 子系统 Tango Device 包。

每个子系统实现为 tango.server.Device 子类（GreenMode.Asyncio）。
遵循 GXLF 集中管控接口规范：adminMode / controlMode / selfCheckState /
healthState / simSpeed 等公共属性由基类 SubsystemDevice 提供。

设备名约定：sim/<subsystem>/1（nodb 模式）
启动方式：python -m simulators.server
客户端注册表：SimulatorRegistry（simulators/registry.py）
"""

from ._base import SubsystemDevice
from .registry import SimulatorRegistry

# 各子系统 Device 类
from .zzy import ZZYDevice
from .epj import EPJDevice
from .yf import YFDevice
from .dcf import DCFDevice
from .plz import PLZDevice
from .bb import BBDevice
from .kg import KGDevice
from .jzt import JZTDevice
from .bm import BMDevice
from .zk import ZKDevice, VACUUM_THRESHOLD
from .cly import CLYDevice
from .wlzd import WLZDDevice
from .aq import AQDevice
from .lk import LKDevice
from .mx import MXDevice
from .ldb import LDBDevice

__all__ = [
    "SubsystemDevice",
    "SimulatorRegistry",
    "ZZYDevice", "EPJDevice", "YFDevice", "DCFDevice", "PLZDevice",
    "BBDevice", "KGDevice", "JZTDevice", "BMDevice", "ZKDevice",
    "CLYDevice", "WLZDDevice", "AQDevice", "LKDevice", "MXDevice",
    "LDBDevice",
    "VACUUM_THRESHOLD",
]
