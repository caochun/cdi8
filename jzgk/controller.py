"""
JZGK — 集中管控控制器

状态机：IDLE → PREPARING → FIRING → POST → IDLE
         任意状态 → EMERGENCY_STOP

修订记录：
- P0: 补全 A/B 阶段并行后的全量故障检查（硬/软故障分级）
- P1: 补全 _handle_abort 的补偿动作（光链关断、能源安全释放）
- P2: S3 真空告警决策（严重泄漏升级为中止）
- P3: 状态机一等公民（合法转换表 + entry actions 表驱动）
"""
import asyncio
import enum
import logging
import time
from collections.abc import Awaitable, Callable

from simulators.registry import SimulatorRegistry

from .recipe import AbortReason, ShotAborted, ShotRecord, ShotRecipe
from .safety import SafetyWatcher
from .workflow import Step, StepResult, Workflow, WorkflowAborted

logger = logging.getLogger(__name__)


class JZGKState(enum.Enum):
    IDLE            = "IDLE"
    PREPARING       = "PREPARING"
    FIRING          = "FIRING"
    POST            = "POST"
    EMERGENCY_STOP  = "EMERGENCY_STOP"


# ── 子系统故障分级 ────────────────────────────────────────────
#
# HARD: 故障 → 立即中止发次
# SOFT: 故障 → 记录警告，继续（诊断/测量类，不影响打靶安全）

_A_HARD = [
    ("ZZY",  "种子源出光失败，光链无种子"),
    ("EPJ",  "二倍频注入失败，无注入光"),
    ("YF",   "预放唤醒失败，无放大能力"),
    ("DCF",  "主放准直失败，光束偏离"),
    ("JZT",  "集中同步配方加载失败，时序不可用"),
    ("BB",   "泵浦准备失败，无能量来源"),
    ("BM",   "靶瞄准定位失败，靶位不明"),
    ("ZK",   "真空靶室抽真空失败，无法打靶"),
]
_A_SOFT = [
    ("WLZD", "物理诊断配置失败，本发次诊断数据缺失"),
    ("CLY",  "测量取样配置失败，本发次测量数据缺失"),
]

_B_HARD = [
    ("BB", "泵浦充电故障，无放电能量"),
    ("KG", "开关驱动源充电故障"),
]


# ── 状态机定义 ────────────────────────────────────────────────
#
# 合法转换表：仅允许表中列出的 (from, to) 对。
# EMERGENCY_STOP 是通配目标，可从任意状态到达（单独处理）。

_VALID_TRANSITIONS: frozenset[tuple[JZGKState, JZGKState]] = frozenset({
    (JZGKState.IDLE,           JZGKState.PREPARING),
    (JZGKState.PREPARING,      JZGKState.FIRING),
    (JZGKState.FIRING,         JZGKState.POST),
    (JZGKState.POST,           JZGKState.IDLE),
    (JZGKState.EMERGENCY_STOP, JZGKState.IDLE),
    # 任意状态 → EMERGENCY_STOP 在 _set_state 中特殊允许
})


class JZGK:
    def __init__(self, registry: SimulatorRegistry):
        self.registry = registry
        self.safety = SafetyWatcher(registry)
        self._state = JZGKState.IDLE
        self._shot_counter = 0
        self._history: list[ShotRecord] = []
        self._on_phase_change: list = []
        # 追踪本发次已激活的子系统，用于补偿
        self._activated: set[str] = set()
        # 步骤级事件回调（由 Web API 层注入）
        self._on_step_start: Callable | None = None
        self._on_step_done: Callable | None = None

    def set_step_callbacks(
        self,
        on_start: "Callable[[str, str], None] | None",
        on_done: "Callable[[str, StepResult], None] | None",
    ):
        """注入步骤级事件回调，供 Web UI 实时推送步骤状态。"""
        self._on_step_start = on_start
        self._on_step_done = on_done

    @property
    def state(self) -> JZGKState:
        return self._state

    @property
    def history(self) -> list[ShotRecord]:
        return list(self._history)

    def on_phase_change(self, cb):
        self._on_phase_change.append(cb)

    # ── 主入口 ───────────────────────────────────────────────

    async def start_shot(self, recipe: ShotRecipe) -> ShotRecord:
        if self._state != JZGKState.IDLE:
            raise RuntimeError(f"JZGK 非 IDLE 状态，无法启动发次（当前: {self._state}）")

        self._shot_counter += 1
        self._activated.clear()
        record = ShotRecord(shot_id=self._shot_counter, recipe=recipe)
        self.safety.clear()

        logger.info("=" * 56)
        logger.info("JZGK 发次 #%d 开始  配方: %s", self._shot_counter, recipe.recipe_id)

        try:
            await self._set_state(JZGKState.PREPARING)
            t0 = time.monotonic()
            await self.safety.guard(
                asyncio.wait_for(self._phase_a(recipe, record), timeout=recipe.phase_a_timeout)
            )
            record.phase_a_elapsed = time.monotonic() - t0
            await self.safety.check()

            await self._set_state(JZGKState.FIRING)
            t0 = time.monotonic()
            await self.safety.guard(
                asyncio.wait_for(self._phase_b(recipe, record), timeout=recipe.phase_b_timeout)
            )
            record.phase_b_elapsed = time.monotonic() - t0
            await self.safety.check()

            await self._set_state(JZGKState.POST)
            t0 = time.monotonic()
            await self.safety.guard(
                asyncio.wait_for(self._phase_c(recipe, record), timeout=recipe.phase_c_timeout)
            )
            record.phase_c_elapsed = time.monotonic() - t0

            record.finalize(success=True)
            logger.info(
                "JZGK 发次 #%d 完成  A=%.1fs B=%.1fs C=%.1fs  总计=%.1fs",
                record.shot_id,
                record.phase_a_elapsed,
                record.phase_b_elapsed,
                record.phase_c_elapsed,
                record.total_elapsed,
            )

        except ShotAborted as exc:
            record.finalize(success=False, exc=exc)
            await self._handle_abort(exc)

        except asyncio.TimeoutError:
            exc = ShotAborted(AbortReason.TIMEOUT, f"阶段超时（state={self._state}）")
            record.finalize(success=False, exc=exc)
            await self._handle_abort(exc)

        finally:
            self._history.append(record)
            await self._set_state(JZGKState.IDLE)

        return record

    # ── A 阶段：发射准备 ──────────────────────────────────────

    async def _phase_a(self, recipe: ShotRecipe, record: ShotRecord):
        logger.info("── A 阶段：发射准备 ──")
        reg = self.registry

        async def zzz_out():
            await reg.get("ZZY").enable_output()
            self._activated.add("ZZY")

        async def epj_out():
            await reg.get("EPJ").enable_output()
            self._activated.add("EPJ")

        async def yf_wakeup():
            await reg.get("YF").wake_up()
            self._activated.add("YF")

        async def bb_prepare():
            await reg.get("BB").prepare(recipe.pump_energy_j)
            self._activated.add("BB")

        async def zk_evacuate():
            await reg.get("ZK").start_evacuation()
            self._activated.add("ZK")

        async def dcf_confirm():
            await reg.get("DCF").confirm_ready()
            self._activated.add("DCF")

        async def aq_lockdown():
            await reg.get("AQ").lock_down()
            self._activated.add("AQ_LOCKED")

        _PARALLEL = [
            "zzz_out", "epj_out", "yf_wakeup", "dcf_align", "jzt_load",
            "bb_prepare", "bm_pos", "zk_evacuate", "wlzd_setup", "cly_setup",
        ]
        wf = Workflow("phase_a", [
            Step("zk_monitor",  [],         reg.get("ZK").start_monitoring),
            Step("zzz_out",     ["zk_monitor"], zzz_out),
            Step("epj_out",     ["zk_monitor"], epj_out),
            Step("yf_wakeup",   ["zk_monitor"], yf_wakeup),
            Step("dcf_align",   ["zk_monitor"], reg.get("DCF").align),
            Step("jzt_load",    ["zk_monitor"], lambda: reg.get("JZT").load_recipe(recipe.recipe_id)),
            Step("bb_prepare",  ["zk_monitor"], bb_prepare),
            Step("bm_pos",      ["zk_monitor"], lambda: reg.get("BM").pre_position(recipe.target_id)),
            Step("zk_evacuate", ["zk_monitor"], zk_evacuate),
            Step("wlzd_setup",  ["zk_monitor"], lambda: reg.get("WLZD").setup_detectors(recipe.diagnostic_config), soft=True),
            Step("cly_setup",   ["zk_monitor"], lambda: reg.get("CLY").setup(recipe.sampling_config), soft=True),
            Step("fault_check", _PARALLEL,  lambda: self._check_faults(_A_HARD, _A_SOFT, "A 阶段")),
            Step("s3_check",    ["fault_check"], self._check_s3_severity),
            Step("dcf_confirm", ["s3_check"],    dcf_confirm),
            Step("aq_clear",    ["dcf_confirm"], reg.get("AQ").clear_area),
            Step("aq_lockdown", ["aq_clear"],    aq_lockdown),
        ], on_step_start=self._on_step_start, on_step_done=self._on_step_done)
        try:
            results = await wf.run()
        except WorkflowAborted as e:
            self._log_steps("a", wf.results, record)   # 保留已完成步骤的耗时
            raise self._abort_from_workflow(e)
        self._log_steps("a", results, record)
        logger.info("A 阶段完成")

    # ── B 阶段：发射 ─────────────────────────────────────────

    async def _phase_b(self, recipe: ShotRecipe, record: ShotRecord):
        logger.info("── B 阶段：发射 ──")
        reg = self.registry

        async def bb_charge():
            await reg.get("BB").charge()
            self._activated.add("BB_CHARGED")

        async def kg_charge():
            await reg.get("KG").charge(recipe.kg_voltage_kv)
            self._activated.add("KG_CHARGED")

        async def bb_trigger():
            await reg.get("BB").trigger()
            self._activated.discard("BB_CHARGED")

        async def kg_trigger():
            await reg.get("KG").trigger()
            self._activated.discard("KG_CHARGED")

        async def capture_uv():
            record.uv_energy_j = reg.get("PLZ").uv_energy
            logger.info("B 阶段完成  UV能量: %.1f J", record.uv_energy_j)

        _CHARGE = ["bb_charge", "kg_charge", "plz_crystal", "wlzd_arm"]
        wf = Workflow("phase_b", [
            Step("jzt_single",  [],        lambda: reg.get("JZT").load_single_shot(recipe.timing_channels)),
            Step("bb_charge",   ["jzt_single"], bb_charge),
            Step("kg_charge",   ["jzt_single"], kg_charge),
            Step("plz_crystal", ["jzt_single"], lambda: reg.get("PLZ").set_crystal_pose(recipe.plz_pitch_mrad, recipe.plz_yaw_mrad)),
            Step("wlzd_arm",    ["jzt_single"], reg.get("WLZD").arm_detectors),
            Step("fault_check", _CHARGE,    lambda: self._check_faults(_B_HARD, [], "B 阶段充电")),
            Step("safety_chk",  ["fault_check"], self.safety.check),
            Step("s3_check",    ["safety_chk"],  self._check_s3_severity),
            Step("bb_trigger",  ["s3_check"],    bb_trigger),
            Step("kg_trigger",  ["bb_trigger"],  kg_trigger),   # BB 触发后再触发 KG
            Step("yf_pump",     ["kg_trigger"],  lambda: reg.get("YF").receive_pump_energy(reg.get("BB").actual_energy * 0.3)),
            Step("dcf_fire",    ["kg_trigger"],  lambda: reg.get("DCF").receive_pump_and_fire(reg.get("BB").actual_energy * 0.7)),
            Step("plz_uv",      ["yf_pump", "dcf_fire"], lambda: reg.get("PLZ").receive_fundamental(reg.get("DCF").output_energy)),
            Step("cly_sample",  ["plz_uv"],       reg.get("CLY").start_sampling),
            Step("jzt_arm",     ["cly_sample"],   reg.get("JZT").arm),
            Step("jzt_fire",    ["jzt_arm"],      reg.get("JZT").fire),
            Step("capture_uv",  ["jzt_fire"],     capture_uv),
        ], on_step_start=self._on_step_start, on_step_done=self._on_step_done)
        try:
            results = await wf.run()
        except WorkflowAborted as e:
            self._log_steps("b", wf.results, record)
            raise self._abort_from_workflow(e)
        self._log_steps("b", results, record)

    # ── C 阶段：发射后处理 ───────────────────────────────────

    async def _phase_c(self, recipe: ShotRecipe, record: ShotRecord):
        logger.info("── C 阶段：后处理 ──")
        reg = self.registry
        _buf: dict = {}

        async def cly_read():
            _buf["laser_params"] = await reg.get("CLY").read_results()

        async def wlzd_read():
            _buf["phys_signals"] = await reg.get("WLZD").read_results()

        async def record_data():
            lp = _buf["laser_params"]
            ps = _buf["phys_signals"]
            record.laser_energy_kj = lp.get("energy_kj", 0.0)
            record.xray_signal     = ps.get("xray_signal", 0.0)
            record.neutron_count   = ps.get("neutron_count", 0)
            record.gamma_signal    = ps.get("gamma_signal", 0.0)
            logger.info(
                "C: 激光 %.3f kJ | X-ray %.2f | Neutron %d | γ %.2f",
                record.laser_energy_kj, record.xray_signal,
                record.neutron_count, record.gamma_signal,
            )

        async def yf_purge():
            await reg.get("YF").accept_purge()
            self._activated.discard("YF")

        async def mx_calibrate():
            shot_data = {**_buf.get("laser_params", {}), **_buf.get("phys_signals", {})}
            await reg.get("MX").start_calibration(shot_data)
            record.model_version      = reg.get("MX").model_version
            record.correction_factors = reg.get("MX").correction_factors
            logger.info("C 阶段完成  模型 v%d", record.model_version)

        wf = Workflow("phase_c", [
            Step("wlzd_acquire", [],                        reg.get("WLZD").acquire),
            Step("cly_read",     ["wlzd_acquire"],          cly_read),
            Step("wlzd_read",    ["wlzd_acquire"],          wlzd_read),
            Step("record_data",  ["cly_read", "wlzd_read"], record_data),
            Step("lk_purge_on",  ["record_data"],           reg.get("LK").start_purge),
            Step("yf_purge",     ["lk_purge_on"],           yf_purge),
            Step("lk_purge_off", ["yf_purge"],              reg.get("LK").stop_purge),
            Step("mx_calibrate", ["lk_purge_off"],          mx_calibrate),
        ], on_step_start=self._on_step_start, on_step_done=self._on_step_done)
        try:
            results = await wf.run()
        except WorkflowAborted as e:
            self._log_steps("c", wf.results, record)
            raise self._abort_from_workflow(e)
        self._log_steps("c", results, record)

    # ── P0: 全量故障检查 ─────────────────────────────────────

    def _check_faults(
        self,
        hard: list[tuple[str, str]],
        soft: list[tuple[str, str]],
        phase_label: str,
    ):
        """
        检查硬故障（立即中止）和软故障（警告继续）。
        hard/soft: [(subsystem_name, error_message), ...]
        """
        hard_failures = []
        for name, msg in hard:
            if self.registry.get(name).is_fault:
                hard_failures.append(f"{name}: {msg}")

        for name, msg in soft:
            if self.registry.get(name).is_fault:
                logger.warning("%s 软故障（继续执行）: %s", phase_label, f"{name}: {msg}")

        if hard_failures:
            detail = f"{phase_label}硬故障 — " + " | ".join(hard_failures)
            logger.error(detail)
            raise ShotAborted(AbortReason.SUBSYSTEM_FAULT, detail)

    # ── P2: S3 真空告警决策 ──────────────────────────────────

    def _check_s3_severity(self):
        """
        评估 S3 真空度告警的严重程度：
        - 无 S3 告警：继续
        - 气压 > VACUUM_THRESHOLD * 100（严重泄漏）：中止
        - 气压 > VACUUM_THRESHOLD（轻度泄漏）：记录警告，继续
        """
        from simulators.zk import VACUUM_THRESHOLD

        s3_events = [w for w in self.safety.warnings if w.signal_id == "S3"]
        if not s3_events:
            return

        zk = self.registry.get("ZK")
        pressure = zk.pressure

        if pressure > VACUUM_THRESHOLD * 100:
            raise ShotAborted(
                AbortReason.SUBSYSTEM_FAULT,
                f"S3 严重真空泄漏: {pressure:.2e} Pa（阈值 {VACUUM_THRESHOLD:.2e} Pa × 100），中止发次",
            )
        else:
            logger.warning(
                "S3 轻度真空告警: %.2e Pa > %.2e Pa，继续执行（操作员应关注）",
                pressure, VACUUM_THRESHOLD,
            )

    # ── P1: 补偿动作 ─────────────────────────────────────────

    async def _handle_abort(self, exc: ShotAborted):
        logger.error("JZGK 发次中止: %s", exc)
        # 转换到 EMERGENCY_STOP 将自动触发 entry action: _safe_state_all
        await self._set_state(JZGKState.EMERGENCY_STOP)

    async def _safe_state_all(self):
        """
        将所有已激活子系统带回安全状态。
        并发执行，单个失败不阻塞其他，全部完成后记录结果。
        """
        reg = self.registry
        results = await asyncio.gather(
            self._safe("ZZY 禁光",    self._disable_beam_source("ZZY")),
            self._safe("EPJ 禁光",    self._disable_beam_source("EPJ")),
            self._safe("BB 停充",     self._safe_bb()),
            self._safe("KG 复位",     self._safe_charged(reg.get("KG"))),
            self._safe("JZT 复位",    reg.get("JZT").reset()),
            self._safe("AQ 释放联锁", reg.get("AQ").release()),
            self._safe("ZK 停止监控", reg.get("ZK").stop_monitoring()),
            return_exceptions=True,
        )
        failed = [r for r in results if isinstance(r, Exception)]
        if failed:
            logger.warning("安全复位部分失败（%d 项）: %s", len(failed), failed)
        else:
            logger.info("安全复位完成，所有子系统已到达安全状态")

    async def _safe(self, label: str, coro):
        """单个子系统的安全动作，捕获所有异常并记录。"""
        try:
            await coro
        except Exception as e:
            logger.warning("安全复位 [%s] 失败: %s", label, e)
            raise

    async def _disable_beam_source(self, name: str):
        """关闭光源输出。
        检查 _activated 记录 OR 模拟器实际状态，防止部分激活场景漏补偿。
        """
        sim = self.registry.get(name)
        tracked = name in self._activated
        active_state = sim.state.value in ("RUNNING", "READY")
        if not tracked and not active_state:
            return
        if hasattr(sim, "disable_output"):
            await sim.disable_output()

    async def _safe_bb(self):
        """BB 安全处置：充电中 → 紧急停充；已触发/待机 → 不操作。"""
        bb = self.registry.get("BB")
        state_val = bb.state.value
        if state_val in ("RUNNING", "READY"):
            # RUNNING 可能是充电中或触发中
            if "BB_CHARGED" in self._activated or "BB" in self._activated:
                await bb.emergency_stop("发次中止，安全停充")
        elif bb.is_fault:
            pass  # 已故障，不重复操作

    async def _safe_charged(self, sim):
        """通用充电设备安全处置：有高压则复位。"""
        state_val = sim.state.value
        if state_val in ("RUNNING", "READY", "CHARGING", "CHARGED"):
            await sim.reset()

    # ── 内部工具 ─────────────────────────────────────────────

    def _abort_from_workflow(self, exc: WorkflowAborted) -> ShotAborted:
        """将工作流中止转换为发次中止，保留原始异常类型。"""
        if isinstance(exc.cause, ShotAborted):
            return exc.cause
        return ShotAborted(AbortReason.SUBSYSTEM_FAULT, str(exc))

    def _log_steps(self, prefix: str, results: dict[str, StepResult], record: ShotRecord):
        """记录每步耗时（日志 + 写入 record.step_timings）。"""
        parts = []
        for r in results.values():
            mark = "✓" if r.success else "✗"
            entry = f"{r.name}:{mark}{r.elapsed:.2f}s"
            if r.attempts > 1:
                entry += f"×{r.attempts}"
            parts.append(entry)
            record.step_timings[f"{prefix}.{r.name}"] = r.elapsed
        logger.info("%s 步骤耗时: %s", prefix, " | ".join(parts))

    async def _set_state(self, new_state: JZGKState):
        old = self._state
        # ── Guard: 校验合法转换 ────────────────────────────
        if new_state != JZGKState.EMERGENCY_STOP and (old, new_state) not in _VALID_TRANSITIONS:
            raise RuntimeError(
                f"非法状态转换: {old.value} → {new_state.value}，"
                f"合法转换: {[f'{a.value}→{b.value}' for a, b in _VALID_TRANSITIONS]}"
            )
        self._state = new_state
        logger.info("JZGK: %s → %s", old.value, new_state.value)
        # ── Entry actions: 表驱动 ──────────────────────────
        entry = _ENTRY_ACTIONS.get(new_state)
        if entry is not None:
            await entry(self)
        # ── 外部观察者回调 ─────────────────────────────────
        for cb in self._on_phase_change:
            try:
                result = cb(old, new_state)
                if asyncio.iscoroutine(result):
                    await result
            except Exception:
                pass


# ── 状态 entry actions 表（在类定义之后，引用类方法）────────────
#
# 每个 entry action 接受 JZGK 实例，返回 coroutine。
# 只注册需要 side effect 的状态；无 action 的状态不出现在表中。

_ENTRY_ACTIONS: dict[JZGKState, "Callable[[JZGK], Awaitable]"] = {
    JZGKState.EMERGENCY_STOP: JZGK._safe_state_all,
}
