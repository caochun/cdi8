"""
冒烟测试：用 10 倍速跑通一个完整发次（A → B → C），验证所有模拟器接口可用。

运行：
    python -m simulators.smoke_test
"""
import asyncio
import logging
import sys
from pathlib import Path

# 确保包路径可用
sys.path.insert(0, str(Path(__file__).parent.parent))

from simulators import SimulatorRegistry

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    datefmt="%H:%M:%S",
)
logger = logging.getLogger("smoke_test")

SAFETY_SIGNALS: list[tuple] = []
STATE_CHANGES: list[tuple] = []


def on_safety(name, signal_id, message):
    SAFETY_SIGNALS.append((name, signal_id, message))
    logger.critical("🚨 安全信号 %s from %s: %s", signal_id, name, message)


def on_state_changed(name, old_state, new_state, message):
    STATE_CHANGES.append((name, old_state.value, new_state.value))


async def run_shot(reg: SimulatorRegistry):
    jzt  = reg.get("JZT")
    aq   = reg.get("AQ")
    bb   = reg.get("BB")
    yf   = reg.get("YF")
    dcf  = reg.get("DCF")
    zk   = reg.get("ZK")
    plz  = reg.get("PLZ")
    kg   = reg.get("KG")
    cly  = reg.get("CLY")
    wlzd = reg.get("WLZD")
    zzy  = reg.get("ZZY")
    epj  = reg.get("EPJ")
    bm   = reg.get("BM")
    ldb  = reg.get("LDB")
    lk   = reg.get("LK")
    mx   = reg.get("MX")

    logger.info("=" * 60)
    logger.info("阶段 A：发射准备")
    logger.info("=" * 60)

    # 启动真空监控
    await zk.start_monitoring()

    # A 阶段：并行发出各子系统准备指令
    await asyncio.gather(
        zzy.enable_output(),                           # A01
        epj.enable_output(),                           # A02
        yf.wake_up(),                                  # A03
        dcf.align(),                                   # A04
        jzt.load_recipe("RECIPE_001"),                 # A05
        bb.prepare(energy_setpoint=500.0),             # A06
        bm.pre_position("TARGET_001"),                 # A07
        zk.start_evacuation(),                         # A09
        wlzd.setup_detectors({"mode": "standard"}),   # A11
        cly.setup({"gate_width": 5, "channels": 4}),  # A12
    )

    # A13/A14: 清场（串行，有人员撤离等待）
    logger.info("--- A13: 清场 ---")
    await aq.clear_area()
    await aq.lock_down()

    # 确认主放就绪；若准直失败则中止本发次
    if dcf.is_fault:
        logger.error("DCF 准直失败，中止发次。请 reset 后重试。")
        await zk.stop_monitoring()
        return None, None
    await dcf.confirm_ready()

    logger.info("=" * 60)
    logger.info("阶段 B：发射")
    logger.info("=" * 60)

    await jzt.load_single_shot({"YF": 50.0, "DCF": 100.0})   # B01

    # B 阶段并行：充电、晶体调整、探测器就位
    await asyncio.gather(
        bb.charge(),                                   # B07
        kg.charge(target_voltage=8.0),                 # B10
        plz.set_crystal_pose(pitch=0.05, yaw=-0.02),  # B12
        wlzd.arm_detectors(),                          # B14
    )

    # 检查充电结果
    if bb.is_fault:
        logger.error("BB 充电故障（S2 已上报），中止发次")
        await zk.stop_monitoring()
        return None, None

    # 触发（串行）
    await bb.trigger()                                 # B07 触发
    await kg.trigger()                                 # B11

    # 模拟放大链
    pump_energy = bb.actual_energy
    await yf.receive_pump_energy(pump_energy * 0.3)   # B08
    await dcf.receive_pump_and_fire(pump_energy * 0.7) # B09+B11
    await plz.receive_fundamental(dcf.output_energy)   # B05

    # 测量采样
    await cly.start_sampling()                         # B06/B13

    # JZT 触发（象征性）
    await jzt.arm()
    await jzt.fire()

    logger.info("=" * 60)
    logger.info("阶段 C：发射后处理")
    logger.info("=" * 60)

    # 采集诊断信号
    await wlzd.acquire()

    # 读取结果
    laser_params = await cly.read_results()            # C01
    phys_signals = await wlzd.read_results()           # C02

    logger.info("C01 激光参数: %s", laser_params)
    logger.info("C02 物理信号: %s", phys_signals)

    # 吹扫
    await lk.start_purge()                             # C03/C04
    await yf.accept_purge()
    await lk.stop_purge()

    # 模型校准
    shot_data = {**laser_params, **phys_signals}
    await mx.start_calibration(shot_data)              # C05/C06

    logger.info("C06 校准结果: v%d, 修正: %s", mx.model_version, mx.correction_factors)

    # 清理
    await zk.stop_monitoring()

    return laser_params, phys_signals


async def main():
    logger.info("构建模拟器注册表（10 倍速）...")
    reg = SimulatorRegistry.build(sim_speed=10.0)
    reg.on_safety(on_safety)
    reg.on_state_changed(on_state_changed)

    try:
        result = await run_shot(reg)
        if result == (None, None):
            logger.error("发次因故中止")
            return 1
        laser_params, phys_signals = result
    except Exception:
        logger.exception("发次执行异常")
        return 1

    logger.info("=" * 60)
    logger.info("冒烟测试完成")
    logger.info("  状态变化次数: %d", len(STATE_CHANGES))
    logger.info("  安全信号数量: %d", len(SAFETY_SIGNALS))
    if SAFETY_SIGNALS:
        for sig in SAFETY_SIGNALS:
            logger.info("    %s", sig)
    logger.info("=" * 60)
    return 0


if __name__ == "__main__":
    exit_code = asyncio.run(main())
    sys.exit(exit_code)
