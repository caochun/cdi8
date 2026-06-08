export type Vec3 = [number, number, number];

export enum Phase {
  IDLE = "IDLE",
  PARAM_DISPATCH = "PARAM_DISPATCH",
  PARALLEL_STARTUP = "PARALLEL_STARTUP",
  CLEARANCE_LOCKDOWN = "CLEARANCE_LOCKDOWN",
  ENERGY_ALIGNMENT = "ENERGY_ALIGNMENT",
  READINESS_CHECK = "READINESS_CHECK",
  FINAL_PREP = "FINAL_PREP",
  CHARGING = "CHARGING",
  COUNTDOWN = "COUNTDOWN",
  FIRING = "FIRING",
  TARGET_IMPLOSION = "TARGET_IMPLOSION",
  DATA_COLLECTION = "DATA_COLLECTION",
  POST_PROCESS = "POST_PROCESS",
  COMPLETE = "COMPLETE",
}

export enum SubsystemId {
  SEED_SOURCE = "SEED_SOURCE",
  BROADBAND_INJECT = "BROADBAND_INJECT",
  PRE_AMPLIFIER = "PRE_AMPLIFIER",
  MULTI_PASS_AMP = "MULTI_PASS_AMP",
  PUMP = "PUMP",
  FREQUENCY_CONV = "FREQUENCY_CONV",
  TARGET_ALIGN = "TARGET_ALIGN",
  TARGET_CHAMBER = "TARGET_CHAMBER",
  DIAGNOSTICS = "DIAGNOSTICS",
  SYNC = "SYNC",
  SWITCH_DRIVER = "SWITCH_DRIVER",
  COOLING = "COOLING",
  MEASUREMENT = "MEASUREMENT",
  SAFETY = "SAFETY",
  CONTROL_ENV = "CONTROL_ENV",
  SHIELDING_DOOR = "SHIELDING_DOOR",
  PERSONNEL_COUNTER = "PERSONNEL_COUNTER",
}

export enum SubsystemStatus {
  OFF = "OFF",
  STANDBY = "STANDBY",
  WARMING = "WARMING",
  READY = "READY",
  ACTIVE = "ACTIVE",
  FIRING = "FIRING",
  COLLECTING = "COLLECTING",
  ERROR = "ERROR",
}

export enum InterlockId {
  SHIELDING_DOOR = "SHIELDING_DOOR",
  PERSONNEL_CLEAR = "PERSONNEL_CLEAR",
  VACUUM_NOMINAL = "VACUUM_NOMINAL",
  LASER_ALIGNED = "LASER_ALIGNED",
  CAPACITORS_CHARGED = "CAPACITORS_CHARGED",
  TIMING_LOCKED = "TIMING_LOCKED",
  TARGET_POSITIONED = "TARGET_POSITIONED",
  DIAGNOSTICS_ARMED = "DIAGNOSTICS_ARMED",
  MASTER_INTERLOCK = "MASTER_INTERLOCK",
}

export interface VisualParams {
  laserBeamsVisible: boolean;
  laserBeamsIntensity: number;
  xenonFlashActive: boolean;
  warningLightsActive: boolean;
  warningLightsColor: "amber" | "red";
  targetGlowIntensity: number;
  capacitorChargeLevel: number;
  shieldingDoorsOpen: boolean;
  bloomIntensity: number;
  coolingFlowing: boolean;
}

export interface PhaseConfig {
  id: Phase;
  label: string;
  labelZh: string;
  description: string;
  camera: string;
}

export interface CameraPreset {
  position: Vec3;
  target: Vec3;
  fov: number;
  label: string;
}

export type GXLFSystemName =
  | "光纤种子源组件"
  | "二倍频宽带激光注入组件"
  | "再生与双程放大组件"
  | "多程放大组件"
  | "泵浦分系统"
  | "测量取样组件"
  | "频率转换分系统"
  | "靶瞄准定位系统"
  | "真空靶室分系统"
  | "物理实验诊断分系统"
  | "集中同步分系统"
  | "开关驱动源组件"
  | "冷却分系统"
  | "控制环境组件"
  | "建安工程安防系统屏蔽门控制接口"
  | "人员进出计数接口";

export type GXLFLifecycleEventType =
  | "command_received"
  | "command_rejected"
  | "state_transition"
  | "task_state_changed";

export interface GXLFLifecycleEvent {
  seq: number;
  timestamp: string;
  event_type: GXLFLifecycleEventType | string;
  flow_instance_id?: string;
  shot_id?: string;
  stage_id?: string;
  node_id?: string;
  node_name?: string;
  command?: string;
  task_id?: string;
  system_name: GXLFSystemName | string;
  service_type?: string;
  service_id?: string;
  tango_fqdn?: string;
  instance_code?: string;
  beam_line_no?: number;
  beam_group_no?: number;
  health_state_before?: string;
  health_state_after?: string;
  business_state_before?: string;
  business_state_after?: string;
  task_state_before?: string;
  task_state_after?: string;
  result_status?: string;
  sim_expected_duration_ms?: number;
  sim_delay_seconds?: number;
  sim_node_delay_seconds?: number;
  sim_business_countdown_seconds?: number;
  message?: string;
  replayed?: boolean;
}

export type GXLFEngineStatus =
  | "idle"
  | "running"
  | "waiting_guard"
  | "paused"
  | "stopping"
  | "completed"
  | "failed"
  | "aborted"
  | "error";

export interface GXLFFault {
  behavior?: string;
  node_id?: string;
  system_name?: string;
  service_id?: string;
  instance_code?: string;
  command?: string;
}

export interface GXLFGuardContext {
  flags?: Record<string, unknown>;
  interlocks?: Record<string, unknown>;
  version?: number;
  strict_unknown_guards?: boolean;
}

export interface GXLFEngineEvent {
  status?: GXLFEngineStatus | string;
  event_type?: string;
  node_name?: string;
  node_id?: string;
  detail?: string;
  guard_block?: GXLFEngineEvent;
  guard_context?: GXLFGuardContext;
  failed_guards?: Array<{
    passed?: boolean;
    guard_type?: string;
    key?: string;
    expected?: unknown;
    actual?: unknown;
    reason?: string;
    raw?: unknown;
  }>;
  key?: string;
  value?: unknown;
  type?: string;
  action?: string;
  fault?: GXLFFault;
  faults?: GXLFFault[];
  system_name?: string;
  health_state?: string;
  service_health_overrides?: Record<string, string>;
  command?: string;
  reason?: string;
  message?: string;
  flow_status?: string;
  executed_nodes?: number;
  lifecycle_events?: number;
  speed_multiplier?: number;
  time_scale?: number;
  time_scale_override?: number | null;
  target_duration_seconds?: number;
  max_nodes?: number | null;
}

export interface LifecycleServiceState {
  serviceId: string;
  systemName: string;
  subsystemId?: SubsystemId;
  status: SubsystemStatus;
  seq: number;
  receivedAt: number;
  eventType: string;
  command?: string;
  nodeId?: string;
  nodeName?: string;
  taskState?: string;
  businessState?: string;
  healthState?: string;
  instanceCode?: string;
  beamLineNo?: number;
  beamGroupNo?: number;
  resultStatus?: string;
  message?: string;
}

export interface LifecycleSystemStats {
  systemName: string;
  subsystemId?: SubsystemId;
  expectedCount: number;
  seenCount: number;
  activeCount: number;
  readyCount: number;
  errorCount: number;
  succeededCount: number;
  latestSeq: number;
  latestCommand?: string;
  latestNodeId?: string;
  latestEventAt?: number;
  status: SubsystemStatus;
}
