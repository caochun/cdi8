"""
GXLF 集中管控系统仿真器入口。

将全部 16 个分系统的 Tango Device 注册到同一个 Device Server 中，
通过一条命令启动所有仿真设备。

用法：
    python run_simulator.py GxlfSimulator

    其中 "GxlfSimulator" 是 Tango Device Server 的实例名称，
    需要与 Tango 数据库中的注册信息一致（若使用数据库）。

无数据库模式（开发用）：
    使用 tango.test_context.MultiDeviceTestContext 可以不依赖
    Tango 数据库运行所有 Device，适合单元测试和本地开发。
    参见 test_simulator.py。

设备实例映射（Device Path → Class）：
    gxlf/seed_source/01         → SeedSource
    gxlf/shg_injection/01       → SHGInjection
    gxlf/preamplifier/01        → Preamplifier
    gxlf/main_amplifier/01      → MainAmplifier
    gxlf/switch_driver/01       → SwitchDriver
    gxlf/pump_laser/01          → PumpLaser
    gxlf/measurement_sample/01  → MeasurementSample
    gxlf/freq_conversion/01     → FreqConversion
    gxlf/target_alignment/01    → TargetAlignment
    gxlf/ld_target/01           → LDTarget
    gxlf/vacuum_chamber/01      → VacuumChamber
    gxlf/diag_system/01         → DiagSystem
    gxlf/timing_sync/01         → TimingSync
    gxlf/cooling_air/01         → CoolingAir
    gxlf/safety_interlock/01    → SafetyInterlock
    gxlf/model_calibration/01   → ModelCalibration
"""

import sys
import logging

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)

from tango.server import run
from devices import ALL_CLASSES


def main():
    run(ALL_CLASSES)


if __name__ == "__main__":
    main()
