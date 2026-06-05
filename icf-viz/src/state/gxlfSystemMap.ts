import {
  InterlockId,
  SubsystemId,
  SubsystemStatus,
  type GXLFLifecycleEvent,
  type GXLFSystemName,
  type LifecycleServiceState,
  type LifecycleSystemStats,
  type VisualParams,
} from "@/types";

export const GXLF_SYSTEM_TO_SUBSYSTEM: Record<GXLFSystemName, SubsystemId> = {
  光纤种子源组件: SubsystemId.SEED_SOURCE,
  二倍频宽带激光注入组件: SubsystemId.BROADBAND_INJECT,
  再生与双程放大组件: SubsystemId.PRE_AMPLIFIER,
  多程放大组件: SubsystemId.MULTI_PASS_AMP,
  泵浦分系统: SubsystemId.PUMP,
  测量取样组件: SubsystemId.MEASUREMENT,
  频率转换分系统: SubsystemId.FREQUENCY_CONV,
  靶瞄准定位系统: SubsystemId.TARGET_ALIGN,
  真空靶室分系统: SubsystemId.TARGET_CHAMBER,
  物理实验诊断分系统: SubsystemId.DIAGNOSTICS,
  集中同步分系统: SubsystemId.SYNC,
  开关驱动源组件: SubsystemId.SWITCH_DRIVER,
  冷却分系统: SubsystemId.COOLING,
  控制环境组件: SubsystemId.CONTROL_ENV,
  建安工程安防系统屏蔽门控制接口: SubsystemId.SHIELDING_DOOR,
  人员进出计数接口: SubsystemId.PERSONNEL_COUNTER,
};

export const GXLF_SYSTEM_ALIASES: Record<string, SubsystemId> = {
  安全联锁: SubsystemId.SAFETY,
  屏蔽门: SubsystemId.SHIELDING_DOOR,
  人员计数: SubsystemId.PERSONNEL_COUNTER,
};

export const GXLF_SYSTEM_EXPECTED_COUNTS: Record<GXLFSystemName, number> = {
  光纤种子源组件: 1,
  二倍频宽带激光注入组件: 1,
  再生与双程放大组件: 10,
  多程放大组件: 10,
  泵浦分系统: 1,
  测量取样组件: 60,
  频率转换分系统: 60,
  靶瞄准定位系统: 1,
  真空靶室分系统: 1,
  物理实验诊断分系统: 1,
  集中同步分系统: 60,
  开关驱动源组件: 1,
  冷却分系统: 1,
  控制环境组件: 1,
  建安工程安防系统屏蔽门控制接口: 1,
  人员进出计数接口: 1,
};

export function subsystemForGXLFSystem(systemName: string): SubsystemId | undefined {
  return (
    GXLF_SYSTEM_TO_SUBSYSTEM[systemName as GXLFSystemName] ??
    GXLF_SYSTEM_ALIASES[systemName]
  );
}

export function statusForLifecycleEvent(event: GXLFLifecycleEvent): SubsystemStatus {
  if (event.event_type === "command_rejected" || event.result_status === "rejected") {
    return SubsystemStatus.ERROR;
  }
  if (event.task_state_after === "failed" || event.task_state_after === "timeout") {
    return SubsystemStatus.ERROR;
  }
  if (event.task_state_after === "succeeded") {
    return statusForBusinessState(event.business_state_after);
  }
  if (event.task_state_after === "executing" || event.health_state_after === "busy") {
    return SubsystemStatus.ACTIVE;
  }
  if (event.event_type === "command_received") {
    return SubsystemStatus.WARMING;
  }
  return statusForBusinessState(event.business_state_after);
}

function statusForBusinessState(state?: string): SubsystemStatus {
  if (!state) return SubsystemStatus.ACTIVE;
  if (state.includes("fault") || state.includes("error") || state.includes("rejected")) {
    return SubsystemStatus.ERROR;
  }
  if (state.includes("firing") || state.includes("trigger") || state.includes("shot")) {
    return SubsystemStatus.FIRING;
  }
  if (state.includes("collect") || state.includes("analys")) {
    return SubsystemStatus.COLLECTING;
  }
  if (state.includes("ready") || state.includes("aligned") || state.includes("charged")) {
    return SubsystemStatus.READY;
  }
  if (state.includes("idle") || state.includes("standby") || state.includes("retracted")) {
    return SubsystemStatus.STANDBY;
  }
  return SubsystemStatus.ACTIVE;
}

export function interlockChangesForLifecycleEvent(
  event: GXLFLifecycleEvent
): Partial<Record<InterlockId, boolean>> {
  const changes: Partial<Record<InterlockId, boolean>> = {};
  const command = event.command ?? "";
  const state = event.business_state_after ?? "";

  if (event.system_name === "建安工程安防系统屏蔽门控制接口") {
    if (command.includes("Close") || state.includes("closed") || state.includes("locked")) {
      changes[InterlockId.SHIELDING_DOOR] = true;
    }
    if (command.includes("Open") || state.includes("open")) {
      changes[InterlockId.SHIELDING_DOOR] = false;
    }
  }
  if (event.system_name === "人员进出计数接口") {
    if (state.includes("zero") || state.includes("clear")) {
      changes[InterlockId.PERSONNEL_CLEAR] = true;
    }
  }
  if (command === "SyncTrigger") {
    changes[InterlockId.TIMING_LOCKED] = true;
  }
  if (command.includes("Diagnostic")) {
    changes[InterlockId.DIAGNOSTICS_ARMED] = true;
  }

  return changes;
}

export function visualChangesForLifecycleEvent(
  event: GXLFLifecycleEvent
): Partial<VisualParams> {
  const command = event.command ?? "";
  const state = event.business_state_after ?? "";
  const changes: Partial<VisualParams> = {};

  if (event.system_name === "建安工程安防系统屏蔽门控制接口") {
    if (command.includes("Close") || state.includes("closed") || state.includes("locked")) {
      changes.shieldingDoorsOpen = false;
    }
    if (command.includes("Open") || state.includes("open")) {
      changes.shieldingDoorsOpen = true;
    }
  }
  if (command === "WarningLightAndMusic" || command === "WarningMusicOn") {
    changes.warningLightsActive = true;
    changes.warningLightsColor = "red";
  }
  if (command === "WarningLightOff") {
    changes.warningLightsActive = false;
  }
  if (command.includes("Charge")) {
    changes.capacitorChargeLevel = event.task_state_after === "succeeded" ? 1 : 0.45;
  }
  if (command === "BeamlineAlign" || command === "TargetBeamGuidance") {
    changes.laserBeamsVisible = true;
    changes.laserBeamsIntensity = 0.2;
  }
  if (command === "SyncTrigger") {
    changes.laserBeamsVisible = true;
    changes.laserBeamsIntensity = 0.8;
    changes.bloomIntensity = 1.4;
  }
  if (command.includes("Data") || state.includes("analys")) {
    changes.targetGlowIntensity = 0.35;
  }

  return changes;
}

export function expectedCountForGXLFSystem(systemName: string): number {
  return GXLF_SYSTEM_EXPECTED_COUNTS[systemName as GXLFSystemName] ?? 1;
}

export function deriveSystemStats(
  systemName: string,
  services: LifecycleServiceState[]
): LifecycleSystemStats {
  const latest = services.reduce<LifecycleServiceState | undefined>((acc, item) => {
    if (!acc || item.seq >= acc.seq) return item;
    return acc;
  }, undefined);
  const status = deriveAggregateStatus(services, latest?.status ?? SubsystemStatus.OFF);

  return {
    systemName,
    subsystemId: subsystemForGXLFSystem(systemName),
    expectedCount: expectedCountForGXLFSystem(systemName),
    seenCount: services.length,
    activeCount: services.filter((s) => s.status === SubsystemStatus.ACTIVE || s.status === SubsystemStatus.FIRING || s.status === SubsystemStatus.WARMING).length,
    readyCount: services.filter((s) => s.status === SubsystemStatus.READY).length,
    errorCount: services.filter((s) => s.status === SubsystemStatus.ERROR).length,
    succeededCount: services.filter((s) => s.taskState === "succeeded").length,
    latestSeq: latest?.seq ?? 0,
    latestCommand: latest?.command,
    latestNodeId: latest?.nodeId,
    latestEventAt: latest?.receivedAt,
    status,
  };
}

function deriveAggregateStatus(
  services: LifecycleServiceState[],
  fallback: SubsystemStatus
): SubsystemStatus {
  if (services.some((s) => s.status === SubsystemStatus.ERROR)) return SubsystemStatus.ERROR;
  if (services.some((s) => s.status === SubsystemStatus.FIRING)) return SubsystemStatus.FIRING;
  if (services.some((s) => s.status === SubsystemStatus.ACTIVE || s.status === SubsystemStatus.WARMING)) {
    return SubsystemStatus.ACTIVE;
  }
  if (services.length > 0 && services.every((s) => s.status === SubsystemStatus.READY)) {
    return SubsystemStatus.READY;
  }
  if (services.some((s) => s.status === SubsystemStatus.COLLECTING)) return SubsystemStatus.COLLECTING;
  if (services.some((s) => s.status === SubsystemStatus.READY)) return SubsystemStatus.READY;
  if (services.some((s) => s.status === SubsystemStatus.STANDBY)) return SubsystemStatus.STANDBY;
  return fallback;
}
