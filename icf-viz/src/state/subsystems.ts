import { SubsystemId, SubsystemStatus } from "@/types";

export interface SubsystemDef {
  id: SubsystemId;
  label: string;
  labelZh: string;
}

export const SUBSYSTEM_DEFS: SubsystemDef[] = [
  { id: SubsystemId.SEED_SOURCE, label: "Seed Source", labelZh: "种子源" },
  { id: SubsystemId.BROADBAND_INJECT, label: "Broadband Inject", labelZh: "二倍频宽带注入" },
  { id: SubsystemId.PRE_AMPLIFIER, label: "Pre-Amplifier", labelZh: "预放大器" },
  { id: SubsystemId.MULTI_PASS_AMP, label: "Multi-Pass Amplifier", labelZh: "多程放大器" },
  { id: SubsystemId.PUMP, label: "Pump System", labelZh: "泵浦系统" },
  { id: SubsystemId.FREQUENCY_CONV, label: "Frequency Conversion", labelZh: "频率转换" },
  { id: SubsystemId.TARGET_ALIGN, label: "Target Alignment", labelZh: "靶瞄准定位" },
  { id: SubsystemId.TARGET_CHAMBER, label: "Target Chamber", labelZh: "靶室" },
  { id: SubsystemId.DIAGNOSTICS, label: "Diagnostics", labelZh: "物理诊断" },
  { id: SubsystemId.SYNC, label: "Synchronization", labelZh: "同步系统" },
  { id: SubsystemId.SWITCH_DRIVER, label: "Switch Driver", labelZh: "开关驱动源" },
  { id: SubsystemId.COOLING, label: "Cooling System", labelZh: "冷却系统" },
  { id: SubsystemId.MEASUREMENT, label: "Measurement", labelZh: "测量取样" },
  { id: SubsystemId.SAFETY, label: "Safety Interlocks", labelZh: "安全联锁" },
  { id: SubsystemId.CONTROL_ENV, label: "Environment Control", labelZh: "控制环境" },
  { id: SubsystemId.SHIELDING_DOOR, label: "Shielding Door", labelZh: "屏蔽门控制" },
  { id: SubsystemId.PERSONNEL_COUNTER, label: "Personnel Counter", labelZh: "人员计数" },
];

export function createInitialSubsystems(): Record<SubsystemId, SubsystemStatus> {
  const result = {} as Record<SubsystemId, SubsystemStatus>;
  for (const def of SUBSYSTEM_DEFS) {
    result[def.id] = SubsystemStatus.OFF;
  }
  result[SubsystemId.COOLING] = SubsystemStatus.STANDBY;
  return result;
}
