"""
JZGK — 集中管控控制器

状态机：IDLE → PREPARING → FIRING → POST → IDLE
         任意状态 → EMERGENCY_STOP

三阶段 DAG 流程完整定义（对应 docs/集中管控系统-子系统关系图.md）：
  A (PREPARING) : A01–A11  发射准备
  B (FIRING)    : B01–B22  发射
  C (POST)      : C01–C12  发射后处理
"""
import asyncio
import enum
import logging
import time
from collections.abc import Awaitable, Callable

import tango

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

_A_HARD = [
    ("ZZY",  "Seed source output failed"),
    ("EPJ",  "2w injection failed"),
    ("YF",   "Preamplifier wakeup failed"),
    ("DCF",  "Main amplifier alignment failed"),
    ("JZT",  "Sync recipe load failed"),
    ("BB",   "Pump preparation failed"),
    ("BM",   "Target alignment failed"),
    ("ZK",   "Vacuum evacuation failed"),
]
_A_SOFT = [
    ("WLZD", "Diagnostics setup failed, data will be missing"),
    ("CLY",  "Sampling setup failed, data will be missing"),
]

_B_HARD = [
    ("BB", "Pump charging fault"),
    ("KG", "Switch driver charging fault"),
]


# ── 状态机定义 ────────────────────────────────────────────────

_VALID_TRANSITIONS: frozenset[tuple[JZGKState, JZGKState]] = frozenset({
    (JZGKState.IDLE,           JZGKState.PREPARING),
    (JZGKState.PREPARING,      JZGKState.FIRING),
    (JZGKState.FIRING,         JZGKState.POST),
    (JZGKState.POST,           JZGKState.IDLE),
    (JZGKState.EMERGENCY_STOP, JZGKState.IDLE),
})


class JZGK:
    def __init__(self, registry: SimulatorRegistry):
        self.registry = registry
        self.safety = SafetyWatcher(registry)
        self._state = JZGKState.IDLE
        self._shot_counter = 0
        self._history: list[ShotRecord] = []
        self._on_phase_change: list = []
        self._wf_a: Workflow | None = None
        self._wf_b: Workflow | None = None
        self._wf_c: Workflow | None = None
        self._on_step_start: Callable | None = None
        self._on_step_done: Callable | None = None

    def set_step_callbacks(
        self,
        on_start: Callable[[str, str], None] | None,
        on_done: Callable[[str, StepResult], None] | None,
    ):
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
            raise RuntimeError(f"JZGK not IDLE, cannot start shot (current: {self._state})")

        self._shot_counter += 1
        self._wf_a = self._wf_b = self._wf_c = None
        record = ShotRecord(shot_id=self._shot_counter, recipe=recipe)
        self.safety.clear()

        logger.info("=" * 56)
        logger.info("JZGK Shot #%d started  recipe: %s", self._shot_counter, recipe.recipe_id)

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
                "JZGK Shot #%d complete  A=%.1fs B=%.1fs C=%.1fs  total=%.1fs",
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
            exc = ShotAborted(AbortReason.TIMEOUT, f"Phase timeout (state={self._state})")
            record.finalize(success=False, exc=exc)
            await self._handle_abort(exc)

        finally:
            self._history.append(record)
            await self._set_state(JZGKState.IDLE)

        return record

    # ── A 阶段：发射准备（A01–A11）────────────────────────────

    async def _phase_a(self, recipe: ShotRecipe, record: ShotRecord):
        logger.info("── Phase A: Shot Preparation ──")
        reg = self.registry

        # ── 补偿闭包 ──
        async def bb_safe():
            bb = reg.get("BB")
            if bb.dev_state in (tango.DevState.RUNNING, tango.DevState.ON):
                await bb.emergency_stop("Shot aborted, safe discharge")

        async def aq_safe_release():
            aq = reg.get("AQ")
            if aq.is_fault:
                logger.warning("AQ in FAULT (S1), manual reset required")
            else:
                await aq.release()

        # ── 条件闭包 ──
        async def ldb_reset():
            if recipe.use_ld_target:
                await reg.get("LDB").reset()

        # ── DAG 叶节点名称（并行汇聚点）──
        _PARALLEL = [
            "zzz_out", "epj_out", "yf_fine", "dcf_align",
            "jzt_preamp_rep", "bb_prepare", "bm_beam_guide",
            "zk_evacuate", "wlzd_setup", "cly_motion_ready", "ldb_reset",
        ]

        wf = Workflow("phase_a", [
            # ZK 监控（所有步骤的前置）
            Step("zk_monitor",  [],             reg.get("ZK").start_monitoring,
                 compensate=reg.get("ZK").stop_monitoring),

            # A01: ZZY 种子源出光
            Step("zzz_out",     ["zk_monitor"], reg.get("ZZY").enable_output,
                 compensate=reg.get("ZZY").disable_output),

            # A02: EPJ 二倍频出光
            Step("epj_out",     ["zk_monitor"], reg.get("EPJ").enable_output,
                 compensate=reg.get("EPJ").disable_output),

            # A03: YF 预放唤醒 → 出打靶光 → 粗闭环 → 精闭环
            Step("yf_wakeup",      ["zk_monitor"],  reg.get("YF").wake_up),
            Step("yf_shot_output", ["yf_wakeup"],   reg.get("YF").shot_output_light),
            Step("yf_coarse",      ["yf_shot_output"], reg.get("YF").coarse_energy_loop),
            Step("yf_fine",        ["yf_coarse"],   reg.get("YF").fine_energy_loop),

            # A04: DCF 光路准直
            Step("dcf_align",   ["zk_monitor"], reg.get("DCF").align),

            # A05: JZT 配方加载序列
            Step("jzt_shot_ready",  ["zk_monitor"],      reg.get("JZT").load_shot_ready_recipe,
                 compensate=reg.get("JZT").reset),
            Step("jzt_measure_rep", ["jzt_shot_ready"],   reg.get("JZT").load_measure_rep_recipe),
            Step("jzt_preamp_rep",  ["jzt_measure_rep"],  reg.get("JZT").load_preamplifier_rep_recipe),

            # A06: BB 泵浦准备
            Step("bb_prepare",  ["zk_monitor"], lambda: reg.get("BB").prepare(recipe.pump_energy_j),
                 compensate=bb_safe),

            # A07: BM 靶瞄预定位 → 光束引导
            Step("bm_prepos",      ["zk_monitor"], lambda: reg.get("BM").pre_position(recipe.target_id)),
            Step("bm_beam_guide",  ["bm_prepos"],  reg.get("BM").beam_guide),

            # ZK 抽真空
            Step("zk_evacuate", ["zk_monitor"], reg.get("ZK").start_evacuation),

            # A08: WLZD 诊断设备配置（soft）
            Step("wlzd_setup",  ["zk_monitor"],
                 lambda: reg.get("WLZD").setup_detectors(recipe.diagnostic_config), soft=True),

            # A09: CLY 测量配置 → 靶瞄准备 → 打靶运动准备
            Step("cly_setup",        ["zk_monitor"],    lambda: reg.get("CLY").setup(recipe.sampling_config), soft=True),
            Step("cly_target_ready", ["cly_setup"],     reg.get("CLY").measure_target_ready, soft=True),
            Step("cly_motion_ready", ["cly_target_ready"], reg.get("CLY").measure_motion_ready, soft=True),

            # LDB 复位（条件：仅 LD 靶）
            Step("ldb_reset",   ["zk_monitor"], ldb_reset),

            # ── 故障检查 ──
            Step("fault_check", _PARALLEL,      lambda: self._check_faults(_A_HARD, _A_SOFT, "Phase A")),
            Step("s3_check",    ["fault_check"], self._check_s3_severity),

            # A04 续：DCF 重频光确认 → 打靶状态确认
            Step("dcf_rep_confirm",  ["s3_check"],          reg.get("DCF").confirm_rep_frequency_state),
            Step("dcf_shot_confirm", ["dcf_rep_confirm"],   reg.get("DCF").confirm_shot_state),

            # A07 续：BM 打靶状态确认
            Step("bm_shot_confirm",  ["dcf_shot_confirm"],  reg.get("BM").confirm_shot_state),

            # A10: AQ 清场联锁序列
            Step("aq_alarm_on",     ["bm_shot_confirm"],  lambda: reg.get("AQ").control_alarm_sound(1)),
            Step("aq_clear",        ["aq_alarm_on"],      reg.get("AQ").clear_area),
            Step("aq_shield_close", ["aq_clear"],         lambda: reg.get("AQ").control_shield_door(1)),
            Step("aq_shield_lock",  ["aq_shield_close"],  lambda: reg.get("AQ").control_shield_door(2)),
            Step("aq_lockdown",     ["aq_shield_lock"],   reg.get("AQ").lock_down,
                 compensate=aq_safe_release),
            Step("aq_safety_active", ["aq_lockdown"],     lambda: reg.get("AQ").set_safety_state(2)),
        ], on_step_start=self._on_step_start, on_step_done=self._on_step_done)

        self._wf_a = wf
        try:
            results = await wf.run()
        except WorkflowAborted as e:
            self._log_steps("a", wf.results, record)
            raise self._abort_from_workflow(e)
        self._log_steps("a", results, record)
        logger.info("Phase A complete")

    # ── B 阶段：发射（B01–B22）───────────────────────────────

    async def _phase_b(self, recipe: ShotRecipe, record: ShotRecord):
        logger.info("── Phase B: Firing ──")
        reg = self.registry

        # ── 补偿闭包 ──
        async def bb_safe():
            bb = reg.get("BB")
            if bb.dev_state in (tango.DevState.RUNNING, tango.DevState.ON):
                await bb.emergency_stop("Shot aborted, safe discharge")

        async def kg_safe():
            kg = reg.get("KG")
            if kg.dev_state in (tango.DevState.RUNNING, tango.DevState.ON):
                await kg.reset()

        # ── 条件闭包 ──
        async def ldb_open():
            if recipe.use_ld_target:
                await reg.get("LDB").simulate_open_cover()

        async def capture_uv():
            record.uv_energy_j = reg.get("PLZ").uv_energy
            logger.info("Phase B complete  UV energy: %.1f J", record.uv_energy_j)

        _JZT = "jzt_preamp_single"
        _CHARGE = [
            "bb_charge", "kg_charge", "plz_crystal", "wlzd_arm",
            "yf_clear_energy", "cly_measure_ready", "ldb_open",
        ]

        wf = Workflow("phase_b", [
            # B01: JZT 切换单次配方（测量 → 预放）
            Step("jzt_measure_single", [],                    reg.get("JZT").load_measure_single_recipe,
                 compensate=reg.get("JZT").reset),
            Step("jzt_preamp_single",  ["jzt_measure_single"], reg.get("JZT").load_preamplifier_single_recipe),

            # B03: BB 充电准备 → 充电
            Step("bb_charge_ready", [_JZT],              reg.get("BB").charge_ready),
            Step("bb_charge",       ["bb_charge_ready"],  reg.get("BB").charge,
                 compensate=bb_safe),

            # B04: KG 开关充电
            Step("kg_charge",   [_JZT], lambda: reg.get("KG").charge(recipe.kg_voltage_kv),
                 compensate=kg_safe),

            # B05: PLZ 晶体调姿
            Step("plz_crystal", [_JZT], lambda: reg.get("PLZ").set_crystal_pose(recipe.plz_pitch_mrad, recipe.plz_yaw_mrad)),

            # B06: WLZD 探测器就绪
            Step("wlzd_arm",    [_JZT], reg.get("WLZD").arm_detectors),

            # B07: YF 能量精闭环 → 能量计清零
            Step("yf_fine_loop",    [_JZT],            reg.get("YF").fine_energy_loop),
            Step("yf_clear_energy", ["yf_fine_loop"],  reg.get("YF").clear_energy_counter),

            # CLY 打靶测量准备
            Step("cly_measure_ready", [_JZT], reg.get("CLY").measurement_ready),

            # B08: LDB 开罩（条件）
            Step("ldb_open", [_JZT], ldb_open),

            # ── 故障检查 ──
            Step("fault_check", _CHARGE,        lambda: self._check_faults(_B_HARD, [], "Phase B charging")),
            Step("safety_chk",  ["fault_check"], self.safety.check),
            Step("s3_check",    ["safety_chk"],  self._check_s3_severity),

            # B20/B21: 触发准备
            Step("bb_prep_trigger", ["s3_check"], reg.get("BB").prepare_trigger),
            Step("kg_prep_trigger", ["s3_check"], reg.get("KG").prepare_trigger),

            # 放电触发 → 激光链 → 同步触发
            Step("bb_trigger",  ["bb_prep_trigger", "kg_prep_trigger"], reg.get("BB").trigger),
            Step("kg_trigger",  ["bb_trigger"],  reg.get("KG").trigger),
            Step("yf_pump",     ["kg_trigger"],  lambda: reg.get("YF").receive_pump_energy(reg.get("BB").actual_energy * 0.3)),
            Step("dcf_fire",    ["kg_trigger"],  lambda: reg.get("DCF").receive_pump_and_fire(reg.get("BB").actual_energy * 0.7)),
            Step("plz_uv",     ["yf_pump", "dcf_fire"], lambda: reg.get("PLZ").receive_fundamental(reg.get("DCF").output_energy)),
            Step("cly_sample",  ["plz_uv"],      reg.get("CLY").start_sampling),
            Step("jzt_arm",     ["cly_sample"],   reg.get("JZT").arm),
            Step("jzt_fire",    ["jzt_arm"],      reg.get("JZT").fire),
            Step("capture_uv",  ["jzt_fire"],     capture_uv),
        ], on_step_start=self._on_step_start, on_step_done=self._on_step_done)

        self._wf_b = wf
        try:
            results = await wf.run()
        except WorkflowAborted as e:
            self._log_steps("b", wf.results, record)
            raise self._abort_from_workflow(e)
        self._log_steps("b", results, record)

    # ── C 阶段：发射后处理（C01–C12）────────────────────────

    async def _phase_c(self, recipe: ShotRecipe, record: ShotRecord):
        logger.info("── Phase C: Post-shot ──")
        reg = self.registry
        _buf: dict[str, dict] = {}

        # ── 数据采集闭包（C01–C09）──
        async def zzy_read():
            _buf["zzy"] = await reg.get("ZZY").read_laser_parameters()

        async def epj_read():
            _buf["epj"] = await reg.get("EPJ").read_laser_parameters()

        async def yf_read():
            _buf["yf"] = await reg.get("YF").read_energy_data()

        async def bb_read():
            _buf["bb"] = await reg.get("BB").read_pump_data()

        async def kg_read():
            _buf["kg"] = await reg.get("KG").read_discharge_waveform()

        async def bm_analyze():
            _buf["bm"] = await reg.get("BM").analyze_data()

        async def cly_read():
            _buf["cly"] = await reg.get("CLY").read_results()

        async def wlzd_read():
            _buf["wlzd"] = await reg.get("WLZD").read_results()

        async def record_data():
            record.subsystem_data = dict(_buf)
            lp = _buf.get("cly", {})
            ps = _buf.get("wlzd", {})
            record.laser_energy_kj = lp.get("energy_kj", 0.0)
            record.xray_signal     = ps.get("xray_signal", 0.0)
            record.neutron_count   = ps.get("neutron_count", 0)
            record.gamma_signal    = ps.get("gamma_signal", 0.0)
            logger.info(
                "C: Laser %.3f kJ | X-ray %.2f | Neutron %d | gamma %.2f",
                record.laser_energy_kj, record.xray_signal,
                record.neutron_count, record.gamma_signal,
            )

        # ── 条件闭包 ──
        async def ldb_close():
            if recipe.use_ld_target:
                await reg.get("LDB").close_cover()

        async def mx_calibrate():
            shot_data = {}
            for d in _buf.values():
                shot_data.update(d)
            await reg.get("MX").start_calibration(shot_data)
            record.model_version      = reg.get("MX").model_version
            record.correction_factors = reg.get("MX").correction_factors
            logger.info("Phase C complete  model v%d", record.model_version)

        _READS = [
            "zzy_read", "epj_read", "yf_read", "bb_read",
            "kg_read", "bm_analyze", "cly_read", "wlzd_read",
        ]
        _POST = [
            "lk_purge_off", "wlzd_post_shot", "bm_collect", "ldb_close", "bb_power_off",
        ]
        _STANDBY = [
            "zzy_standby", "epj_standby", "yf_standby", "kg_standby",
            "cly_standby", "bb_standby", "jzt_reset",
        ]

        wf = Workflow("phase_c", [
            # 触发采集
            Step("wlzd_acquire", [], reg.get("WLZD").acquire),

            # C01–C09: 并行数据采集
            Step("zzy_read",    ["wlzd_acquire"], zzy_read),
            Step("epj_read",    ["wlzd_acquire"], epj_read),
            Step("yf_read",     ["wlzd_acquire"], yf_read),
            Step("bb_read",     ["wlzd_acquire"], bb_read),
            Step("kg_read",     ["wlzd_acquire"], kg_read),
            Step("bm_analyze",  ["wlzd_acquire"], bm_analyze),
            Step("cly_read",    ["wlzd_acquire"], cly_read),
            Step("wlzd_read",   ["wlzd_acquire"], wlzd_read),

            # 汇总记录
            Step("record_data", _READS, record_data),

            # C10: 片放吹扫
            Step("lk_purge_on",  ["record_data"], reg.get("LK").start_purge,
                 compensate=reg.get("LK").stop_purge),
            Step("yf_purge",     ["lk_purge_on"],  reg.get("YF").accept_purge),
            Step("lk_purge_off", ["yf_purge"],     reg.get("LK").stop_purge),

            # C07: WLZD 后处理（退高压/收回/下电）
            Step("wlzd_post_shot", ["record_data"], reg.get("WLZD").post_shot_processing),

            # C06: BM 收靶
            Step("bm_collect",  ["record_data"], reg.get("BM").collect_target),

            # LDB 收靶（条件）
            Step("ldb_close",   ["record_data"], ldb_close),

            # C05: BB 预电离 → 关机
            Step("bb_preionize_ready",  ["record_data"],           reg.get("BB").preionize_ready),
            Step("bb_preionize_charge", ["bb_preionize_ready"],    reg.get("BB").preionize_charge),
            Step("bb_power_off",        ["bb_preionize_charge"],   reg.get("BB").power_off_reset),

            # 各子系统待机（并行）
            Step("zzy_standby", _POST, reg.get("ZZY").standby),
            Step("epj_standby", _POST, reg.get("EPJ").standby),
            Step("yf_standby",  _POST, reg.get("YF").standby),
            Step("kg_standby",  _POST, reg.get("KG").standby),
            Step("cly_standby", _POST, reg.get("CLY").standby),
            Step("bb_standby",  _POST, reg.get("BB").standby),
            Step("jzt_reset",   _POST, reg.get("JZT").reset),

            # C11: AQ 联锁释放序列
            Step("aq_alarm_off", _STANDBY,          lambda: reg.get("AQ").control_alarm_sound(0)),
            Step("aq_light_off", ["aq_alarm_off"],   lambda: reg.get("AQ").control_warning_light(0)),
            Step("aq_release",   ["aq_light_off"],   reg.get("AQ").release),

            # MX 模型校准
            Step("mx_calibrate",    ["aq_release"],    mx_calibrate),

            # ZK 停止监控
            Step("zk_stop_monitor", ["mx_calibrate"],  reg.get("ZK").stop_monitoring),
        ], on_step_start=self._on_step_start, on_step_done=self._on_step_done)

        self._wf_c = wf
        try:
            results = await wf.run()
        except WorkflowAborted as e:
            self._log_steps("c", wf.results, record)
            raise self._abort_from_workflow(e)
        self._log_steps("c", results, record)

    # ── 故障检查 ─────────────────────────────────────────────

    def _check_faults(
        self,
        hard: list[tuple[str, str]],
        soft: list[tuple[str, str]],
        phase_label: str,
    ):
        hard_failures = []
        for name, msg in hard:
            if self.registry.get(name).is_fault:
                hard_failures.append(f"{name}: {msg}")

        for name, msg in soft:
            if self.registry.get(name).is_fault:
                logger.warning("%s soft fault (continuing): %s", phase_label, f"{name}: {msg}")

        if hard_failures:
            detail = f"{phase_label} hard fault: " + " | ".join(hard_failures)
            logger.error(detail)
            raise ShotAborted(AbortReason.SUBSYSTEM_FAULT, detail)

    def _check_s3_severity(self):
        from simulators.zk import VACUUM_THRESHOLD

        s3_events = [w for w in self.safety.warnings if w.signal_id == "S3"]
        if not s3_events:
            return

        zk = self.registry.get("ZK")
        pressure = zk.pressure

        if pressure > VACUUM_THRESHOLD * 100:
            raise ShotAborted(
                AbortReason.SUBSYSTEM_FAULT,
                f"S3 severe vacuum leak: {pressure:.2e} Pa (threshold {VACUUM_THRESHOLD:.2e} Pa x100), aborting",
            )
        else:
            logger.warning(
                "S3 minor vacuum warning: %.2e Pa > %.2e Pa, continuing (operator attention needed)",
                pressure, VACUUM_THRESHOLD,
            )

    # ── 补偿 ─────────────────────────────────────────────────

    async def _handle_abort(self, exc: ShotAborted):
        logger.error("JZGK shot aborted: %s", exc)
        await self._set_state(JZGKState.EMERGENCY_STOP)

    async def _safe_state_all(self):
        wfs = [w for w in (self._wf_a, self._wf_b, self._wf_c) if w is not None]
        if not wfs:
            logger.info("No active workflows, nothing to compensate")
            return
        results = await asyncio.gather(
            *[wf.run_compensations() for wf in wfs],
            return_exceptions=True,
        )
        failed = [r for r in results if isinstance(r, Exception)]
        if failed:
            logger.warning("Safe-state partial failure (%d items): %s", len(failed), failed)
        else:
            logger.info("Safe-state complete, all subsystems reached safe state")

    # ── 内部工具 ─────────────────────────────────────────────

    def _abort_from_workflow(self, exc: WorkflowAborted) -> ShotAborted:
        if isinstance(exc.cause, ShotAborted):
            return exc.cause
        return ShotAborted(AbortReason.SUBSYSTEM_FAULT, str(exc))

    def _log_steps(self, prefix: str, results: dict[str, StepResult], record: ShotRecord):
        parts = []
        for r in results.values():
            mark = "✓" if r.success else "✗"
            entry = f"{r.name}:{mark}{r.elapsed:.2f}s"
            if r.attempts > 1:
                entry += f"x{r.attempts}"
            parts.append(entry)
            record.step_timings[f"{prefix}.{r.name}"] = r.elapsed
        logger.info("%s steps: %s", prefix, " | ".join(parts))

    async def _set_state(self, new_state: JZGKState):
        old = self._state
        if new_state != JZGKState.EMERGENCY_STOP and (old, new_state) not in _VALID_TRANSITIONS:
            raise RuntimeError(
                f"Illegal state transition: {old.value} -> {new_state.value}"
            )
        self._state = new_state
        logger.info("JZGK: %s -> %s", old.value, new_state.value)
        entry = _ENTRY_ACTIONS.get(new_state)
        if entry is not None:
            await entry(self)
        for cb in self._on_phase_change:
            try:
                result = cb(old, new_state)
                if asyncio.iscoroutine(result):
                    await result
            except Exception:
                pass


_ENTRY_ACTIONS: dict[JZGKState, Callable[[JZGK], Awaitable]] = {
    JZGKState.EMERGENCY_STOP: JZGK._safe_state_all,
}
