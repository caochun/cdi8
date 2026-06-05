import { InterlockId } from "@/types";

export interface InterlockDef {
  id: InterlockId;
  label: string;
  labelZh: string;
}

export const INTERLOCK_DEFS: InterlockDef[] = [
  { id: InterlockId.SHIELDING_DOOR, label: "Shielding Doors", labelZh: "屏蔽门锁闭" },
  { id: InterlockId.PERSONNEL_CLEAR, label: "Personnel Cleared", labelZh: "人员清零" },
  { id: InterlockId.VACUUM_NOMINAL, label: "Vacuum Nominal", labelZh: "靶室真空" },
  { id: InterlockId.LASER_ALIGNED, label: "Laser Aligned", labelZh: "光路准直" },
  { id: InterlockId.CAPACITORS_CHARGED, label: "Capacitors Charged", labelZh: "电容充电" },
  { id: InterlockId.TIMING_LOCKED, label: "Timing Locked", labelZh: "同步锁定" },
  { id: InterlockId.TARGET_POSITIONED, label: "Target Positioned", labelZh: "靶定位" },
  { id: InterlockId.DIAGNOSTICS_ARMED, label: "Diagnostics Armed", labelZh: "诊断就绪" },
  { id: InterlockId.MASTER_INTERLOCK, label: "Master Interlock", labelZh: "主联锁" },
];

export function createInitialInterlocks(): Record<InterlockId, boolean> {
  const result = {} as Record<InterlockId, boolean>;
  for (const def of INTERLOCK_DEFS) {
    result[def.id] = false;
  }
  return result;
}
