"""
无数据库模式快速验证脚本。

不需要 Tango 数据库，直接用 MultiDeviceTestContext 启动全部仿真设备，
逐一调用每个 Device 的第一条业务命令，验证 normal / fail / no_perm 三种模式。

用法：
    python test_simulator.py
"""

import json
import sys

try:
    from tango.test_context import MultiDeviceTestContext
except ImportError:
    print("ERROR: pytango not installed. Run: pip install pytango")
    sys.exit(1)

# 每个设备的 (DeviceName, Class, [commands to test])
DEVICE_SPECS = [
    ("gxlf/seed_source/01",        "SeedSource",         ["SeedLaserOn", "SeedLaserStandby"]),
    ("gxlf/shg_injection/01",      "SHGInjection",       ["SHGLaserOn"]),
    ("gxlf/preamplifier/01",       "Preamplifier",       ["PreampWakeup", "EnergyFineLoop"]),
    ("gxlf/main_amplifier/01",     "MainAmplifier",      ["BeamlineAlign"]),
    ("gxlf/switch_driver/01",      "SwitchDriver",       ["SwitchCharge"]),
    ("gxlf/pump_laser/01",         "PumpLaser",          ["PumpShotReady", "ChargeProgressReport"]),
    ("gxlf/measurement_sample/01", "MeasurementSample",  ["MeasTargetAlignReady"]),
    ("gxlf/freq_conversion/01",    "FreqConversion",     ["CrystalPoseAdjust"]),
    ("gxlf/target_alignment/01",   "TargetAlignment",    ["ExpTargetPrePosition"]),
    ("gxlf/ld_target/01",          "LDTarget",           ["LDTargetReset"]),
    ("gxlf/vacuum_chamber/01",     "VacuumChamber",      ["VacuumPump"]),
    ("gxlf/diag_system/01",        "DiagSystem",         ["DiagCollectSetup", "DiagStatusConfirm"]),
    ("gxlf/timing_sync/01",        "TimingSync",         ["ShotReadyRecipe", "SyncTrigger"]),
    ("gxlf/cooling_air/01",        "CoolingAir",         ["SlabPurge"]),
    ("gxlf/safety_interlock/01",   "SafetyInterlock",    ["AreaClear"]),
    ("gxlf/model_calibration/01",  "ModelCalibration",   ["ModelParamCalc"]),
]

SAMPLE_INPUT = json.dumps({"task_id": 1, "shot_id": 100, "phase_id": 1,
                            "beamline_id": 1, "light_on": 1})

def run_tests():
    from devices import (
        SeedSource, SHGInjection, Preamplifier, MainAmplifier, SwitchDriver,
        PumpLaser, MeasurementSample, FreqConversion, TargetAlignment, LDTarget,
        VacuumChamber, DiagSystem, TimingSync, CoolingAir, SafetyInterlock,
        ModelCalibration,
    )
    cls_map = {
        "SeedSource": SeedSource, "SHGInjection": SHGInjection,
        "Preamplifier": Preamplifier, "MainAmplifier": MainAmplifier,
        "SwitchDriver": SwitchDriver, "PumpLaser": PumpLaser,
        "MeasurementSample": MeasurementSample, "FreqConversion": FreqConversion,
        "TargetAlignment": TargetAlignment, "LDTarget": LDTarget,
        "VacuumChamber": VacuumChamber, "DiagSystem": DiagSystem,
        "TimingSync": TimingSync, "CoolingAir": CoolingAir,
        "SafetyInterlock": SafetyInterlock, "ModelCalibration": ModelCalibration,
    }

    devices_config = [
        {"class": cls_map[cls_name], "devices": [{"name": dev_name}]}
        for dev_name, cls_name, _ in DEVICE_SPECS
    ]

    print("=" * 60)
    print("GXLF Simulator — 无数据库验证")
    print("=" * 60)

    passed = 0
    failed = 0

    with MultiDeviceTestContext(devices_config) as ctx:
        for dev_name, cls_name, cmds in DEVICE_SPECS:
            proxy = ctx.get_device(dev_name)
            for mode, expected_range in [("normal", {1}), ("fail", {0}), ("no_perm", {2})]:
                proxy.SetMode(mode)
                for cmd in cmds:
                    try:
                        result = getattr(proxy, cmd)(SAMPLE_INPUT)
                        # DiagStatusConfirm etc. return JSON string
                        if isinstance(result, str):
                            result = json.loads(result).get("accept_status", result)
                        ok = result in expected_range
                        status = "PASS" if ok else "FAIL"
                        if ok:
                            passed += 1
                        else:
                            failed += 1
                        print(f"[{status}] {cls_name}.{cmd}(mode={mode}) → {result}")
                    except Exception as e:
                        failed += 1
                        print(f"[ERR ] {cls_name}.{cmd}(mode={mode}) → {e}")

    print("=" * 60)
    print(f"Results: {passed} passed, {failed} failed")
    return failed == 0


if __name__ == "__main__":
    success = run_tests()
    sys.exit(0 if success else 1)
