"""
SimulatorRegistry — 基于 Tango DeviceProxy 的子系统注册表。

用法：
  1. 连接已运行的 Tango Server（TANGO_HOST 已配置）
       registry = SimulatorRegistry.connect()
       await registry.get("YF").wake_up()

  2. 进程内（MultiDeviceTestContext，无需 Tango Server）
       async with SimulatorRegistry.in_process(sim_speed=10) as registry:
           await registry.get("YF").wake_up()

线程桥接：tango.DeviceProxy 是同步 API，使用 asyncio.to_thread 包装。
事件订阅：on_safety / on_state_changed 通过 Tango 变更事件实现。

SubsystemProxy 状态查询：
  proxy.is_fault    → True if DevState.FAULT
  proxy.is_ready    → True if DevState.ON（Tango ON = 就绪）
  proxy.dev_state   → tango.DevState（直接读取）
"""

import asyncio
import json
import logging
from contextlib import asynccontextmanager
from typing import Any, Callable

import tango

logger = logging.getLogger(__name__)

# ── 设备名映射 ────────────────────────────────────────────────────
_DEVICE_NAMES: dict[str, str] = {
    "ZZY":  "sim/zzy/1",
    "EPJ":  "sim/epj/1",
    "YF":   "sim/yf/1",
    "DCF":  "sim/dcf/1",
    "PLZ":  "sim/plz/1",
    "BB":   "sim/bb/1",
    "KG":   "sim/kg/1",
    "JZT":  "sim/jzt/1",
    "BM":   "sim/bm/1",
    "ZK":   "sim/zk/1",
    "CLY":  "sim/cly/1",
    "WLZD": "sim/wlzd/1",
    "AQ":   "sim/aq/1",
    "LK":   "sim/lk/1",
    "MX":   "sim/mx/1",
    "LDB":  "sim/ldb/1",
}


# ────────────────────────────────────────────────────────────────
# 通用 DeviceProxy 异步适配器
# ────────────────────────────────────────────────────────────────

class SubsystemProxy:
    """
    DeviceProxy 异步适配器基类。

    所有 Tango command 调用封装为 async 方法，
    所有属性读取封装为 async 方法或缓存属性。
    """

    def __init__(self, name: str, proxy: tango.DeviceProxy):
        self.name = name
        self._proxy = proxy
        self._loop = asyncio.get_event_loop()
        # 状态缓存（通过事件订阅更新）
        self._cached_state: tango.DevState = tango.DevState.STANDBY
        self._cached_status: str = ""
        # 事件订阅 ID
        self._event_ids: list[int] = []

    def _subscribe_state_event(self, callback: Callable):
        """订阅设备状态变更事件。"""
        try:
            eid = self._proxy.subscribe_event(
                "State",
                tango.EventType.CHANGE_EVENT,
                self._make_event_handler(callback),
            )
            self._event_ids.append(eid)
        except Exception as e:
            logger.warning("%s: 无法订阅 State 事件: %s", self.name, e)

    def _subscribe_safety_event(self, callback: Callable):
        """订阅安全信号变更事件（safetySignal 属性）。"""
        try:
            eid = self._proxy.subscribe_event(
                "safetySignal",
                tango.EventType.CHANGE_EVENT,
                self._make_safety_handler(callback),
            )
            self._event_ids.append(eid)
        except Exception as e:
            logger.debug("%s: 无 safetySignal 属性或订阅失败: %s", self.name, e)

    def _make_event_handler(self, callback: Callable):
        def handler(event: tango.EventData):
            if not event.err and event.attr_value is not None:
                self._cached_state = event.attr_value.value
                try:
                    asyncio.run_coroutine_threadsafe(
                        self._async_callback(callback, self.name, self._cached_state),
                        self._loop,
                    )
                except Exception:
                    pass
        return handler

    def _make_safety_handler(self, callback: Callable):
        def handler(event: tango.EventData):
            if not event.err and event.attr_value is not None:
                signal = event.attr_value.value
                if signal:  # 非空字符串 = 有安全信号
                    try:
                        asyncio.run_coroutine_threadsafe(
                            self._async_safety_callback(callback, self.name, signal),
                            self._loop,
                        )
                    except Exception:
                        pass
        return handler

    @staticmethod
    async def _async_callback(cb, *args):
        try:
            result = cb(*args)
            if asyncio.iscoroutine(result):
                await result
        except Exception:
            pass

    @staticmethod
    async def _async_safety_callback(cb, name, signal):
        try:
            result = cb(name, signal, "")
            if asyncio.iscoroutine(result):
                await result
        except Exception:
            pass

    def cleanup(self):
        for eid in self._event_ids:
            try:
                self._proxy.unsubscribe_event(eid)
            except Exception:
                pass
        self._event_ids.clear()

    # ── 通用属性 ────────────────────────────────────────────
    @property
    def dev_state(self) -> tango.DevState:
        """当前设备状态（缓存值，通过事件订阅更新）。"""
        return self._cached_state

    @property
    def is_fault(self) -> bool:
        return self._cached_state == tango.DevState.FAULT

    @property
    def is_ready(self) -> bool:
        return self._cached_state == tango.DevState.ON

    async def _cmd(self, name: str, *args):
        """异步调用 Tango command（in executor thread）。"""
        def _call():
            cmd = getattr(self._proxy, name)
            return cmd(*args) if args else cmd()
        return await asyncio.to_thread(_call)

    async def _read(self, attr: str) -> Any:
        """异步读取 Tango attribute。"""
        return await asyncio.to_thread(getattr, self._proxy, attr)

    async def _write(self, attr: str, value: Any):
        """异步写入 Tango attribute。"""
        await asyncio.to_thread(setattr, self._proxy, attr, value)

    # ── 基础命令 ────────────────────────────────────────────
    async def reset(self):
        await self._cmd("Reset")
        self._cached_state = tango.DevState.STANDBY

    async def inject_fault(self, reason: str = "模拟故障"):
        await self._cmd("InjectFault", reason)


# ── 各子系统专用 Proxy ────────────────────────────────────────────

class ZZYProxy(SubsystemProxy):
    async def enable_output(self):
        await self._cmd("EnableOutput")

    async def disable_output(self):
        await self._cmd("DisableOutput")

    async def read_laser_parameters(self) -> dict:
        """C阶段：采集种子源激光参数，返回反序列化后的 dict。"""
        result_json = await self._cmd("ReadLaserParameters")
        return json.loads(result_json) if result_json else {}

    async def standby(self):
        """C阶段：切换种子源至待机状态。"""
        await self._cmd("Standby")


class EPJProxy(SubsystemProxy):
    async def enable_output(self):
        await self._cmd("EnableOutput")

    async def disable_output(self):
        await self._cmd("DisableOutput")

    async def read_laser_parameters(self) -> dict:
        """C阶段：采集二倍频激光参数，返回反序列化后的 dict。"""
        result_json = await self._cmd("ReadLaserParameters")
        return json.loads(result_json) if result_json else {}

    async def standby(self):
        """C阶段：切换二倍频注入源至待机状态。"""
        await self._cmd("Standby")


class YFProxy(SubsystemProxy):
    @property
    def measured_energy(self) -> float:
        try:
            return self._proxy.measuredEnergy
        except Exception:
            return 0.0

    async def wake_up(self):
        await self._cmd("WakeUp")

    async def receive_pump_energy(self, pump_energy: float):
        await self._cmd("ReceivePumpEnergy", pump_energy)

    async def start_closed_loop(self):
        await self._cmd("StartClosedLoop")

    async def accept_purge(self):
        await self._cmd("AcceptPurge")

    async def shot_output_light(self):
        """A阶段：预放出打靶光。"""
        await self._cmd("ShotOutputLight")

    async def coarse_energy_loop(self):
        """A阶段：激活能量粗闭环。"""
        await self._cmd("CoarseEnergyLoop")

    async def fine_energy_loop(self):
        """A/B阶段：激活能量精闭环。"""
        await self._cmd("FineEnergyLoop")

    async def clear_energy_counter(self):
        """B阶段：能量计清零。"""
        await self._cmd("ClearEnergyCounter")

    async def read_energy_data(self) -> dict:
        """C阶段：采集能量数据，返回反序列化后的 dict。"""
        result_json = await self._cmd("ReadEnergyData")
        return json.loads(result_json) if result_json else {}

    async def standby(self):
        """C阶段：切换预放组件至待机状态。"""
        await self._cmd("Standby")


class DCFProxy(SubsystemProxy):
    @property
    def output_energy(self) -> float:
        try:
            return self._proxy.outputEnergy
        except Exception:
            return 0.0

    async def align(self):
        await self._cmd("Align")

    async def confirm_ready(self):
        await self._cmd("ConfirmReady")

    async def receive_pump_and_fire(self, pump_energy: float):
        await self._cmd("ReceivePumpAndFire", pump_energy)

    async def confirm_rep_frequency_state(self):
        """A阶段：确认重频光状态。"""
        await self._cmd("ConfirmRepFrequencyState")

    async def confirm_shot_state(self):
        """A阶段：确认打靶就绪状态。"""
        await self._cmd("ConfirmShotState")


class PLZProxy(SubsystemProxy):
    @property
    def uv_energy(self) -> float:
        try:
            return self._proxy.uvEnergy
        except Exception:
            return 0.0

    async def set_crystal_pose(self, pitch: float, yaw: float):
        await self._cmd("SetCrystalPose", [pitch, yaw])

    async def receive_fundamental(self, energy: float):
        await self._cmd("ReceiveFundamental", energy)


class BBProxy(SubsystemProxy):
    @property
    def actual_energy(self) -> float:
        try:
            return self._proxy.actualEnergy
        except Exception:
            return 0.0

    async def prepare(self, target_energy: float):
        await self._cmd("Prepare", target_energy)

    async def charge(self):
        await self._cmd("Charge")

    async def trigger(self):
        await self._cmd("Trigger")

    async def emergency_stop(self, reason: str = "紧急停充"):
        await self._cmd("EmergencyStop", reason)

    async def charge_ready(self):
        """B阶段：发射充电准备（充电前预检查）。"""
        await self._cmd("ChargeReady")

    async def prepare_trigger(self):
        """B阶段：泵浦触发准备（触发时序预置）。"""
        await self._cmd("PrepareTrigger")

    async def preionize_ready(self):
        """C阶段：预电离发射准备。"""
        await self._cmd("PreionizeReady")

    async def preionize_charge(self):
        """C阶段：预电离回路充电。"""
        await self._cmd("PreionizeCharge")

    async def read_pump_data(self) -> dict:
        """C阶段：采集泵浦数据，返回反序列化后的 dict。"""
        result_json = await self._cmd("ReadPumpData")
        return json.loads(result_json) if result_json else {}

    async def power_off_reset(self):
        """B/C阶段：泵浦分系统关机复位。"""
        await self._cmd("PowerOffReset")

    async def standby(self):
        """C阶段：切换泵浦分系统至待机状态。"""
        await self._cmd("Standby")


class KGProxy(SubsystemProxy):
    async def charge(self, voltage: float):
        await self._cmd("Charge", voltage)

    async def trigger(self):
        await self._cmd("Trigger")

    async def reset(self):
        await self._cmd("Reset")
        self._cached_state = tango.DevState.STANDBY

    async def prepare_trigger(self):
        """B阶段：开关触发准备（触发时序预置）。"""
        await self._cmd("PrepareTrigger")

    async def read_discharge_waveform(self) -> dict:
        """C阶段：采集放电波形数据，返回反序列化后的 dict。"""
        result_json = await self._cmd("ReadDischargeWaveform")
        return json.loads(result_json) if result_json else {}

    async def standby(self):
        """C阶段：切换开关驱动源至待机状态。"""
        await self._cmd("Standby")


class JZTProxy(SubsystemProxy):
    async def load_recipe(self, recipe_id: str):
        await self._cmd("LoadRecipe", recipe_id)

    async def load_single_shot(self, channels: dict):
        await self._cmd("LoadSingleShot", json.dumps(channels))

    async def arm(self):
        await self._cmd("Arm")

    async def fire(self):
        await self._cmd("Fire")

    async def reset(self):
        await self._cmd("Reset")
        self._cached_state = tango.DevState.STANDBY

    async def load_shot_ready_recipe(self):
        """A阶段：加载发射准备时序配方。"""
        await self._cmd("LoadShotReadyRecipe")

    async def load_measure_rep_recipe(self):
        """A阶段：加载测量重频时序配方。"""
        await self._cmd("LoadMeasureRepRecipe")

    async def load_preamplifier_rep_recipe(self):
        """A阶段：加载预放重频时序配方。"""
        await self._cmd("LoadPreamplifierRepRecipe")

    async def load_measure_single_recipe(self):
        """B阶段：加载测量单次时序配方。"""
        await self._cmd("LoadMeasureSingleRecipe")

    async def load_preamplifier_single_recipe(self):
        """B阶段：加载预放单次时序配方。"""
        await self._cmd("LoadPreamplifierSingleRecipe")


class BMProxy(SubsystemProxy):
    async def pre_position(self, target_id: str):
        await self._cmd("PrePosition", target_id)

    async def simulate_target_position(self, target_id: str):
        """A阶段：模拟靶定位（仿真模式，跳过实体靶机构动作）。"""
        await self._cmd("SimulateTargetPosition", target_id)

    async def beam_guide(self):
        """A阶段：光束引导（对比焦斑与靶位，输出指向修正量）。"""
        await self._cmd("BeamGuide")

    async def reset_target(self):
        """A阶段：实验靶复位至装载位置。"""
        await self._cmd("ResetTarget")

    async def confirm_shot_state(self):
        """A阶段：靶瞄打靶就绪状态最终确认。"""
        await self._cmd("ConfirmShotState")

    async def analyze_data(self) -> dict:
        """C阶段：靶瞄数据分析，返回反序列化后的 dict。"""
        result_json = await self._cmd("AnalyzeData")
        return json.loads(result_json) if result_json else {}


class ZKProxy(SubsystemProxy):
    @property
    def pressure(self) -> float:
        try:
            return self._proxy.pressure
        except Exception:
            return 1.013e5

    async def start_monitoring(self):
        await self._cmd("StartMonitoring")

    async def stop_monitoring(self):
        await self._cmd("StopMonitoring")

    async def start_evacuation(self):
        await self._cmd("StartEvacuation")


class CLYProxy(SubsystemProxy):
    async def setup(self, config: dict):
        await self._cmd("Setup", json.dumps(config))

    async def start_sampling(self):
        await self._cmd("StartSampling")

    async def read_results(self) -> dict:
        result_json = await self._cmd("ReadResults")
        return json.loads(result_json) if result_json else {}

    async def measure_target_ready(self):
        """A阶段：测量系统靶瞄准备。"""
        await self._cmd("MeasureTargetReady")

    async def measure_motion_ready(self):
        """A阶段：测量系统打靶运动准备。"""
        await self._cmd("MeasureMotionReady")

    async def measurement_ready(self):
        """B阶段：打靶测量准备（切换至采集就绪状态）。"""
        await self._cmd("MeasurementReady")

    async def standby(self):
        """C阶段：切换测量组件至待机状态。"""
        await self._cmd("Standby")


class WLZDProxy(SubsystemProxy):
    async def setup_detectors(self, config: dict):
        await self._cmd("SetupDetectors", json.dumps(config))

    async def arm_detectors(self):
        await self._cmd("ArmDetectors")

    async def acquire(self):
        await self._cmd("Acquire")

    async def read_results(self) -> dict:
        result_json = await self._cmd("ReadResults")
        return json.loads(result_json) if result_json else {}

    async def execute_device_action(self, action: str):
        """B阶段：执行诊断设备动作（收回/就位/试触发/上电）。"""
        await self._cmd("ExecuteDeviceAction", action)

    async def post_shot_processing(self):
        """C阶段：发射后处理（退高压/收回/下电）。"""
        await self._cmd("PostShotProcessing")


class AQProxy(SubsystemProxy):
    async def clear_area(self):
        await self._cmd("ClearArea")

    async def lock_down(self):
        await self._cmd("LockDown")

    async def release(self):
        await self._cmd("Release")

    async def simulate_intrusion(self):
        await self._cmd("SimulateIntrusion")

    async def control_warning_light(self, mode: int):
        """B/C阶段：警灯控制（0=关闭，1=常亮，2=闪烁）。"""
        await self._cmd("ControlWarningLight", mode)

    async def control_alarm_sound(self, mode: int):
        """B/C阶段：警示音控制（0=关闭，1=开启）。"""
        await self._cmd("ControlAlarmSound", mode)

    async def control_shield_door(self, action: int):
        """A阶段：屏蔽门控制（0=开门，1=关门，2=锁定）。"""
        await self._cmd("ControlShieldDoor", action)

    async def set_safety_state(self, state: int):
        """A/C阶段：安全管控状态控制（0=释放，1=预备，2=联锁激活，3=紧急停机）。"""
        await self._cmd("SetSafetyState", state)


class LKProxy(SubsystemProxy):
    async def start_purge(self):
        await self._cmd("StartPurge")

    async def stop_purge(self):
        await self._cmd("StopPurge")

    async def purge_control(self, action: str):
        """片放吹扫统一控制接口（action: '开始'/'结束'/'关机'）。"""
        await self._cmd("PurgeControl", action)


class MXProxy(SubsystemProxy):
    @property
    def model_version(self) -> int:
        try:
            return self._proxy.modelVersion
        except Exception:
            return 0

    @property
    def correction_factors(self) -> dict:
        try:
            return json.loads(self._proxy.correctionFactors)
        except Exception:
            return {}

    async def start_calibration(self, shot_data: dict):
        await self._cmd("StartCalibration", json.dumps(shot_data))


class LDBProxy(SubsystemProxy):
    async def open_cover(self):
        await self._cmd("OpenCover")

    async def simulate_open_cover(self):
        """A/B阶段：模拟 LD 靶开罩（仿真测试模式）。"""
        await self._cmd("SimulateOpenCover")

    async def reset(self):
        """A阶段：LD 靶分系统复位。"""
        await self._cmd("Reset")
        self._cached_state = tango.DevState.STANDBY


# ── 名称 → Proxy 类映射 ──────────────────────────────────────────
_PROXY_CLASSES: dict[str, type[SubsystemProxy]] = {
    "ZZY":  ZZYProxy,
    "EPJ":  EPJProxy,
    "YF":   YFProxy,
    "DCF":  DCFProxy,
    "PLZ":  PLZProxy,
    "BB":   BBProxy,
    "KG":   KGProxy,
    "JZT":  JZTProxy,
    "BM":   BMProxy,
    "ZK":   ZKProxy,
    "CLY":  CLYProxy,
    "WLZD": WLZDProxy,
    "AQ":   AQProxy,
    "LK":   LKProxy,
    "MX":   MXProxy,
    "LDB":  LDBProxy,
}


# ────────────────────────────────────────────────────────────────
# SimulatorRegistry
# ────────────────────────────────────────────────────────────────

class SimulatorRegistry:
    """
    Tango DeviceProxy 注册表。

    用法（连接已运行的 Tango Server）：
        registry = SimulatorRegistry.connect()
        await registry.get("YF").wake_up()

    用法（进程内 test context）：
        async with SimulatorRegistry.in_process(sim_speed=10) as reg:
            await reg.get("YF").wake_up()
    """

    def __init__(self):
        self._proxies: dict[str, SubsystemProxy] = {}
        self._safety_handlers: list[Callable] = []
        self._state_handlers: list[Callable] = []

    # ── 工厂方法 ─────────────────────────────────────────────

    @classmethod
    def connect(cls, tango_host: str | None = None) -> "SimulatorRegistry":
        """连接已运行的 Tango Server（同步初始化）。"""
        if tango_host:
            import os
            os.environ["TANGO_HOST"] = tango_host

        registry = cls()
        for name, device_name in _DEVICE_NAMES.items():
            try:
                proxy = tango.DeviceProxy(device_name)
                proxy_cls = _PROXY_CLASSES[name]
                registry._proxies[name] = proxy_cls(name, proxy)
                logger.info("已连接: %s → %s", name, device_name)
            except Exception as e:
                logger.error("连接失败: %s (%s): %s", name, device_name, e)

        logger.info("SimulatorRegistry: 已连接 %d 个子系统", len(registry._proxies))
        return registry

    @classmethod
    @asynccontextmanager
    async def in_process(cls, sim_speed: float = 1.0):
        """
        进程内 Tango 设备（MultiDeviceTestContext），无需运行 Tango Server。
        用于开发和测试。
        """
        from tango.test_context import MultiDeviceTestContext
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

        device_info = [
            (ZZYDevice,  {"sim/zzy/1": {}}),
            (EPJDevice,  {"sim/epj/1": {}}),
            (YFDevice,   {"sim/yf/1": {}}),
            (DCFDevice,  {"sim/dcf/1": {}}),
            (PLZDevice,  {"sim/plz/1": {}}),
            (BBDevice,   {"sim/bb/1": {}}),
            (KGDevice,   {"sim/kg/1": {}}),
            (JZTDevice,  {"sim/jzt/1": {}}),
            (BMDevice,   {"sim/bm/1": {}}),
            (ZKDevice,   {"sim/zk/1": {}}),
            (CLYDevice,  {"sim/cly/1": {}}),
            (WLZDDevice, {"sim/wlzd/1": {}}),
            (AQDevice,   {"sim/aq/1": {}}),
            (LKDevice,   {"sim/lk/1": {}}),
            (MXDevice,   {"sim/mx/1": {}}),
            (LDBDevice,  {"sim/ldb/1": {}}),
        ]

        with MultiDeviceTestContext(device_info, process=False) as ctx:
            registry = cls()
            for name, device_name in _DEVICE_NAMES.items():
                try:
                    proxy = ctx.get_device(device_name)
                    # 设置仿真速度
                    proxy.simSpeed = sim_speed
                    proxy_cls = _PROXY_CLASSES[name]
                    registry._proxies[name] = proxy_cls(name, proxy)
                except Exception as e:
                    logger.error("进程内设备初始化失败: %s: %s", name, e)

            logger.info("SimulatorRegistry (in-process): %d 个子系统就绪", len(registry._proxies))
            yield registry
            registry._cleanup()

    # ── 访问接口 ─────────────────────────────────────────────

    def get(self, name: str) -> SubsystemProxy:
        if name not in self._proxies:
            raise KeyError(f"未知子系统: {name!r}。已注册: {list(self._proxies)}")
        return self._proxies[name]

    def all(self) -> dict[str, SubsystemProxy]:
        return dict(self._proxies)

    # ── 事件订阅 ─────────────────────────────────────────────

    def on_safety(self, handler: Callable):
        """
        订阅所有子系统的安全信号。
        callback(name: str, signal_id: str, message: str)
        AQ → S1, BB → S2, ZK → S3
        """
        self._safety_handlers.append(handler)
        for name in ("AQ", "BB", "ZK"):
            proxy = self._proxies.get(name)
            if proxy:
                proxy._subscribe_safety_event(self._dispatch_safety)

    def on_state_changed(self, handler: Callable):
        """订阅所有子系统的状态变化事件。"""
        self._state_handlers.append(handler)
        for proxy in self._proxies.values():
            proxy._subscribe_state_event(self._dispatch_state)

    async def _dispatch_safety(self, name: str, signal_id: str, message: str):
        for h in self._safety_handlers:
            try:
                r = h(name, signal_id, message)
                if asyncio.iscoroutine(r):
                    await r
            except Exception:
                logger.exception("安全事件回调异常")

    async def _dispatch_state(self, name: str, state):
        for h in self._state_handlers:
            try:
                r = h(name, state)
                if asyncio.iscoroutine(r):
                    await r
            except Exception:
                logger.exception("状态变化回调异常")

    # ── 批量操作 ─────────────────────────────────────────────

    async def set_sim_speed(self, speed: float):
        """全局调整仿真速度（写入所有设备的 simSpeed 属性）。"""
        for name, proxy in self._proxies.items():
            try:
                await asyncio.to_thread(setattr, proxy._proxy, "simSpeed", speed)
            except Exception as e:
                logger.warning("设置 %s simSpeed 失败: %s", name, e)

    def _cleanup(self):
        for proxy in self._proxies.values():
            proxy.cleanup()
