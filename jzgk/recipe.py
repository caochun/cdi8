"""
ShotRecipe  — 一发次的所有输入参数
ShotRecord  — 一发次的执行结果（含时间戳、测量值、校准结果）
ShotAborted — 发次中途中止的异常
"""
import enum
from dataclasses import dataclass, field
from datetime import datetime


class AbortReason(enum.Enum):
    SAFETY_SIGNAL   = "safety_signal"    # S1/S2 触发
    SUBSYSTEM_FAULT = "subsystem_fault"  # 子系统进入 FAULT
    TIMEOUT         = "timeout"          # 步骤超时
    OPERATOR        = "operator"         # 操作员主动中止


class ShotAborted(Exception):
    def __init__(self, reason: AbortReason, detail: str = ""):
        self.reason = reason
        self.detail = detail
        super().__init__(f"[{reason.value}] {detail}")


@dataclass
class ShotRecipe:
    """一发次的输入配方。"""
    recipe_id: str

    # 能量参数
    pump_energy_j: float = 500.0          # 泵浦目标能量（J）
    kg_voltage_kv: float = 8.0            # 开关驱动源充电电压（kV）

    # 频率转换
    plz_pitch_mrad: float = 0.05          # 晶体俯仰角（mrad）
    plz_yaw_mrad: float = -0.02           # 晶体偏航角（mrad）

    # 时序参数（通道延迟，ns）
    timing_channels: dict[str, float] = field(default_factory=lambda: {
        "YF": 50.0, "DCF": 100.0, "PLZ": 150.0, "CLY": 200.0,
    })

    # 靶参数
    target_id: str = "TARGET_001"
    use_ld_target: bool = False           # True = 使用液氘靶（LDB 开罩/收靶）

    # 测量配置
    sampling_config: dict = field(default_factory=lambda: {
        "gate_width": 5, "channels": 4,
    })
    diagnostic_config: dict = field(default_factory=lambda: {
        "mode": "standard",
    })

    # 超时设置（秒，真实时间，会被 sim_speed 缩短）
    phase_a_timeout: float = 60.0
    phase_b_timeout: float = 30.0
    phase_c_timeout: float = 30.0

    @classmethod
    def default(cls) -> "ShotRecipe":
        return cls(recipe_id="DEFAULT")


@dataclass
class ShotRecord:
    """一发次的完整执行记录。"""
    shot_id: int
    recipe: ShotRecipe
    start_time: datetime = field(default_factory=datetime.now)
    end_time: datetime | None = None

    # 各阶段耗时（秒）
    phase_a_elapsed: float = 0.0
    phase_b_elapsed: float = 0.0
    phase_c_elapsed: float = 0.0

    # 测量结果
    laser_energy_kj: float = 0.0
    uv_energy_j: float = 0.0
    xray_signal: float = 0.0
    neutron_count: int = 0
    gamma_signal: float = 0.0

    # 校准结果
    model_version: int = 0
    correction_factors: dict = field(default_factory=dict)

    # 结果
    success: bool = False
    abort_reason: AbortReason | None = None
    abort_detail: str = ""

    # 步骤级耗时（key: "a.步骤名" / "b.步骤名" / "c.步骤名"）
    step_timings: dict[str, float] = field(default_factory=dict)

    # C 阶段各子系统采集数据（key: "zzy"/"epj"/"yf"/"bb"/"kg"/"bm"/"cly"/"wlzd"）
    subsystem_data: dict[str, dict] = field(default_factory=dict)

    def finalize(self, success: bool, exc: ShotAborted | None = None):
        self.end_time = datetime.now()
        self.success = success
        if exc:
            self.abort_reason = exc.reason
            self.abort_detail = exc.detail

    @property
    def total_elapsed(self) -> float:
        if self.end_time:
            return (self.end_time - self.start_time).total_seconds()
        return (datetime.now() - self.start_time).total_seconds()
