"""
server.py — 启动所有子系统 Tango Device Server（file-db 模式）。

用法：
    # 默认模式（写临时 file-db，无需运行中的 Tango Database）
    python -m simulators.server

    # 指定仿真速度和端口
    SIM_SPEED=10 python -m simulators.server --port 45450

    # 数据库模式（需要运行中的 TANGO_HOST，且已调用 --register 注册过）
    python -m simulators.server --db [--register]

Tango 设备名称约定：
    sim/<subsystem>/1   例：sim/yf/1, sim/bb/1, sim/aq/1
"""

import argparse
import os
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from tango.server import run

from .aq import AQDevice
from .bb import BBDevice
from .bm import BMDevice
from .cly import CLYDevice
from .dcf import DCFDevice
from .epj import EPJDevice
from .jzt import JZTDevice
from .kg import KGDevice
from .ldb import LDBDevice
from .lk import LKDevice
from .mx import MXDevice
from .plz import PLZDevice
from .wlzd import WLZDDevice
from .yf import YFDevice
from .zk import ZKDevice
from .zzy import ZZYDevice

# ── 设备类与 Tango 注册名的映射 ──────────────────────────────
DEVICE_CLASSES = [
    (ZZYDevice,  "sim/zzy/1"),
    (EPJDevice,  "sim/epj/1"),
    (YFDevice,   "sim/yf/1"),
    (DCFDevice,  "sim/dcf/1"),
    (PLZDevice,  "sim/plz/1"),
    (BBDevice,   "sim/bb/1"),
    (KGDevice,   "sim/kg/1"),
    (JZTDevice,  "sim/jzt/1"),
    (BMDevice,   "sim/bm/1"),
    (ZKDevice,   "sim/zk/1"),
    (CLYDevice,  "sim/cly/1"),
    (WLZDDevice, "sim/wlzd/1"),
    (AQDevice,   "sim/aq/1"),
    (LKDevice,   "sim/lk/1"),
    (MXDevice,   "sim/mx/1"),
    (LDBDevice,  "sim/ldb/1"),
]

_SERVER_NAME = "SimulatorServer"
_SERVER_INSTANCE = "gxlf"


def write_device_db(db_path: str):
    """写入 Tango file-database，格式：server/instance/DEVICE/ClassName: device/name/1"""
    with open(db_path, "w") as f:
        for cls, device_name in DEVICE_CLASSES:
            f.write(f"{_SERVER_NAME}/{_SERVER_INSTANCE}/DEVICE/{cls.__name__}: {device_name}\n")


def register_devices():
    """向 Tango Database 注册所有仿真设备（--db 模式用）。"""
    from tango import Database, DbDevInfo
    db = Database()
    for cls, device_name in DEVICE_CLASSES:
        info = DbDevInfo()
        info.server = f"{_SERVER_NAME}/{_SERVER_INSTANCE}"
        info._class = cls.__name__
        info.name = device_name
        db.add_device(info)
    print(f"已向数据库注册 {len(DEVICE_CLASSES)} 个设备")


def main():
    parser = argparse.ArgumentParser(description="GXLF 子系统仿真 Tango Server")
    parser.add_argument("--db", action="store_true",
                        help="数据库模式（需要运行中的 TANGO_HOST）")
    parser.add_argument("--register", action="store_true",
                        help="注册设备到 Tango Database 后启动（--db 模式下使用）")
    parser.add_argument("--port", type=int, default=45450,
                        help="监听端口（默认 45450）")
    args = parser.parse_args()

    sim_speed = float(os.environ.get("SIM_SPEED", "1.0"))
    classes = [cls for cls, _ in DEVICE_CLASSES]

    print(f"GXLF 子系统仿真器  速度: {sim_speed}x  设备数: {len(DEVICE_CLASSES)}")
    for cls, name in DEVICE_CLASSES:
        print(f"  {name:20s} -> {cls.__name__}")

    if args.db:
        # ── 数据库模式 ───────────────────────────────────────
        if args.register:
            register_devices()
        print(f"以数据库模式启动（TANGO_HOST={os.environ.get('TANGO_HOST', '未设置')}）")
        run(classes, args=[_SERVER_NAME, _SERVER_INSTANCE])
    else:
        # ── file-db 模式（默认）─────────────────────────────
        # 写临时 file-db，与 MultiDeviceTestContext 使用相同格式
        with tempfile.NamedTemporaryFile(
            suffix=".db", mode="w", delete=False, prefix="gxlf_sim_"
        ) as tmp:
            db_path = tmp.name
        write_device_db(db_path)
        print(f"以 file-db 模式启动（端口 {args.port}，db={db_path}）")
        run(
            classes,
            args=[
                _SERVER_NAME, _SERVER_INSTANCE,
                "-ORBendPoint", f"giop:tcp::{args.port}",
                f"-file={db_path}",
            ],
        )


if __name__ == "__main__":
    main()
