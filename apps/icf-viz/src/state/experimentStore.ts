import { create } from "zustand";
import {
  InterlockId,
  Phase,
  SubsystemId,
  SubsystemStatus,
  type GXLFLifecycleEvent,
  type GXLFEngineEvent,
  type GXLFEngineStatus,
  type GXLFFault,
  type GXLFGuardContext,
  type LifecycleServiceState,
  type LifecycleSystemStats,
  type VisualParams,
} from "@/types";
import { createInitialInterlocks } from "./interlocks";
import { createInitialSubsystems } from "./subsystems";
import { useCameraOverride } from "./cameraStore";
import {
  deriveSystemStats,
  interlockChangesForLifecycleEvent,
  isMainShotSyncTrigger,
  statusForLifecycleEvent,
  subsystemForGXLFSystem,
  visualChangesForLifecycleEvent,
} from "./gxlfSystemMap";

const DEFAULT_VISUALS: VisualParams = {
  laserBeamsVisible: false,
  laserBeamsIntensity: 0,
  xenonFlashActive: false,
  warningLightsActive: false,
  warningLightsColor: "amber",
  targetGlowIntensity: 0,
  capacitorChargeLevel: 0,
  shieldingDoorsOpen: true,
  bloomIntensity: 0.2,
  coolingFlowing: true,
};

export interface ExperimentState {
  currentPhase: Phase;
  phaseElapsed: number;
  totalElapsed: number;
  subsystems: Record<SubsystemId, SubsystemStatus>;
  interlocks: Record<InterlockId, boolean>;
  visuals: VisualParams;
  countdownValue: number;
  countdownStartedAt: number | null;
  countdownNodeId: string | null;
  countdownDisplaySeconds: number;
  countdownDurationSeconds: number;
  firingStartedAt: number | null;
  targetImpactStartedAt: number | null;
  lastLifecycleEvent: GXLFLifecycleEvent | null;
  lifecycleEvents: GXLFLifecycleEvent[];
  lifecycleServiceStates: Record<string, LifecycleServiceState>;
  lifecycleSystemStats: Record<string, LifecycleSystemStats>;
  lifecycleConnectionStatus: "idle" | "connecting" | "open" | "error";
  engineStatus: GXLFEngineStatus | string;
  engineLastCommand: string | null;
  engineMessage: string | null;
  engineSummary: GXLFEngineEvent | null;
  activeFaults: GXLFFault[];
  serviceHealthOverrides: Record<string, string>;
  guardContext: GXLFGuardContext | null;

  setLifecycleConnectionStatus: (status: "idle" | "connecting" | "open" | "error") => void;
  applyLifecycleEvent: (event: GXLFLifecycleEvent) => void;
  applyEngineEvent: (eventName: string, event: GXLFEngineEvent) => void;
  reset: () => void;
}

function phaseForLifecycleEvent(event: GXLFLifecycleEvent): Phase {
  const nodeNumber = Number(event.node_id?.replace(/^N/, ""));
  const command = event.command ?? "";
  const stage = event.stage_id ?? "";

  if (event.node_name === "正式发射完成" || event.node_id === "N37") return Phase.FIRING;
  if (isMainShotSyncTrigger(event)) return Phase.COUNTDOWN;
  if (command.includes("Charge")) return Phase.CHARGING;
  if (command.includes("Data") || command.includes("Collect") || command.includes("Analysis")) {
    return Phase.DATA_COLLECTION;
  }
  if (stage.includes("发射后处理") || (Number.isFinite(nodeNumber) && nodeNumber >= 38)) {
    return Phase.POST_PROCESS;
  }
  if (stage.includes("正式发射")) {
    if (Number.isFinite(nodeNumber) && nodeNumber >= 34) return Phase.FIRING;
    return Phase.FINAL_PREP;
  }
  if (command.includes("Warning")) return Phase.FINAL_PREP;
  if (command.includes("Align") || command.includes("Guidance") || command.includes("Target")) {
    return Phase.ENERGY_ALIGNMENT;
  }
  if (Number.isFinite(nodeNumber) && nodeNumber >= 20) return Phase.READINESS_CHECK;
  if (Number.isFinite(nodeNumber) && nodeNumber >= 7) return Phase.CLEARANCE_LOCKDOWN;
  if (Number.isFinite(nodeNumber) && nodeNumber >= 2) return Phase.PARALLEL_STARTUP;
  return Phase.PARAM_DISPATCH;
}

function serviceKeyForLifecycleEvent(event: GXLFLifecycleEvent): string {
  if (event.service_id) return event.service_id;
  if (event.instance_code) return `${event.system_name}.${event.instance_code}`;
  if (event.beam_line_no) return `${event.system_name}.bl${String(event.beam_line_no).padStart(2, "0")}`;
  if (event.beam_group_no) return `${event.system_name}.bg${String(event.beam_group_no).padStart(2, "0")}`;
  return `${event.system_name}.svc01`;
}

function lifecycleServiceStateForEvent(
  event: GXLFLifecycleEvent,
  receivedAt: number
): LifecycleServiceState {
  return {
    serviceId: serviceKeyForLifecycleEvent(event),
    systemName: event.system_name,
    subsystemId: subsystemForGXLFSystem(event.system_name),
    status: statusForLifecycleEvent(event),
    seq: event.seq,
    receivedAt,
    eventType: event.event_type,
    command: event.command,
    nodeId: event.node_id,
    nodeName: event.node_name,
    taskState: event.task_state_after,
    businessState: event.business_state_after,
    healthState: event.health_state_after,
    instanceCode: event.instance_code,
    beamLineNo: event.beam_line_no,
    beamGroupNo: event.beam_group_no,
    resultStatus: event.result_status,
    message: event.message,
  };
}

function engineMessageForEvent(eventName: string, event: GXLFEngineEvent, fallback: string | null): string | null {
  if (eventName === "gxlf.engine.event") {
    if (event.event_type === "node_guard_blocked") {
      const guardReason = event.failed_guards?.find((guard) => guard.reason)?.reason;
      return `等待运行条件 ${event.node_id ?? ""} ${event.node_name ?? ""}${guardReason ? `: ${guardReason}` : ""}`.trim();
    }
    if (event.event_type === "node_guard_passed") {
      return `运行条件已满足 ${event.node_id ?? ""} ${event.node_name ?? ""}`.trim();
    }
    if (event.detail) return `${event.event_type ?? "engine"}: ${event.detail}`;
  }
  if (eventName === "gxlf.guard.update") {
    return `运行条件已更新 ${event.key ?? ""}=${String(event.value)}`.trim();
  }
  if (eventName === "gxlf.fault.update") {
    if (event.action === "inject" && event.fault) {
      return `已注入故障 ${event.fault.node_id ?? ""} ${event.fault.behavior ?? ""}`.trim();
    }
    if (event.action === "clear") return "已清除测试注入并恢复运行条件";
    if (event.action === "service_health") {
      return `服务健康状态 ${event.system_name ?? ""}=${event.health_state ?? ""}`.trim();
    }
  }
  if (eventName === "gxlf.engine.status" && event.status === "waiting_guard" && event.guard_block) {
    const block = event.guard_block;
    const guardReason = block.failed_guards?.find((guard) => guard.reason)?.reason;
    return `等待运行条件 ${block.node_id ?? ""} ${block.node_name ?? ""}${guardReason ? `: ${guardReason}` : ""}`.trim();
  }
  return event.message ?? event.reason ?? fallback;
}

function nextFaultState(
  eventName: string,
  event: GXLFEngineEvent,
  state: ExperimentState
): Pick<ExperimentState, "activeFaults" | "serviceHealthOverrides" | "guardContext"> {
  if (eventName === "gxlf.fault.update") {
    return {
      activeFaults: event.faults ?? state.activeFaults,
      serviceHealthOverrides: event.service_health_overrides ?? state.serviceHealthOverrides,
      guardContext: event.guard_context ?? state.guardContext,
    };
  }
  if (eventName === "gxlf.guard.update" || eventName === "gxlf.engine.status") {
    return {
      activeFaults: event.faults ?? state.activeFaults,
      serviceHealthOverrides: event.service_health_overrides ?? state.serviceHealthOverrides,
      guardContext: event.guard_context ?? state.guardContext,
    };
  }
  return {
    activeFaults: state.activeFaults,
    serviceHealthOverrides: state.serviceHealthOverrides,
    guardContext: state.guardContext,
  };
}

export const useExperimentStore = create<ExperimentState>((set, get) => ({
  currentPhase: Phase.IDLE,
  phaseElapsed: 0,
  totalElapsed: 0,
  subsystems: createInitialSubsystems(),
  interlocks: createInitialInterlocks(),
  visuals: { ...DEFAULT_VISUALS },
  countdownValue: -1,
  countdownStartedAt: null,
  countdownNodeId: null,
  countdownDisplaySeconds: 5,
  countdownDurationSeconds: 5,
  firingStartedAt: null,
  targetImpactStartedAt: null,
  lastLifecycleEvent: null,
  lifecycleEvents: [],
  lifecycleServiceStates: {},
  lifecycleSystemStats: {},
  lifecycleConnectionStatus: "idle",
  engineStatus: "idle",
  engineLastCommand: null,
  engineMessage: null,
  engineSummary: null,
  activeFaults: [],
  serviceHealthOverrides: {},
  guardContext: null,

  setLifecycleConnectionStatus: (status) => set({ lifecycleConnectionStatus: status }),

  applyLifecycleEvent: (event) => {
    set((state) => {
      const serviceState = lifecycleServiceStateForEvent(event, Date.now());
      const lifecycleServiceStates = {
        ...state.lifecycleServiceStates,
        [serviceState.serviceId]: serviceState,
      };
      const servicesForSystem = Object.values(lifecycleServiceStates).filter(
        (service) => service.systemName === event.system_name
      );
      const systemStats = deriveSystemStats(event.system_name, servicesForSystem);
      const lifecycleSystemStats = {
        ...state.lifecycleSystemStats,
        [event.system_name]: systemStats,
      };
      const subsystems = {
        ...state.subsystems,
      };
      if (systemStats.subsystemId) subsystems[systemStats.subsystemId] = systemStats.status;

      const nextPhase = phaseForLifecycleEvent(event);
      const interlocks = {
        ...state.interlocks,
        ...interlockChangesForLifecycleEvent(event),
      };
      const eventVisuals = visualChangesForLifecycleEvent(event);
      const visuals = {
        ...state.visuals,
        ...(event.replayed && isMainShotSyncTrigger(event) ? {} : eventVisuals),
      };
      const startsCountdown =
        !event.replayed &&
        isMainShotSyncTrigger(event) &&
        (state.countdownNodeId !== event.node_id || state.countdownStartedAt === null);
      const startsFiring =
        !event.replayed &&
        (event.node_name === "正式发射完成" ||
          event.node_id === "N37" ||
          event.command === "EmergencyTrigger");
      const countdownDisplaySeconds = event.sim_business_countdown_seconds ?? 5;
      const countdownDurationSeconds =
        event.sim_node_delay_seconds && event.sim_node_delay_seconds > 0
          ? event.sim_node_delay_seconds
          : event.sim_delay_seconds && event.sim_delay_seconds > 0
            ? event.sim_delay_seconds
            : countdownDisplaySeconds;

      if (startsCountdown && typeof window !== "undefined") {
        window.setTimeout(() => {
          const current = get();
          if (current.countdownNodeId === (event.node_id ?? null)) {
            useCameraOverride.getState().setSystemOverridePreset("targetChamber");
            set({
              targetImpactStartedAt: Date.now(),
              firingStartedAt: current.firingStartedAt ?? Date.now(),
              currentPhase: Phase.FIRING,
              phaseElapsed: 0,
              visuals: {
                ...current.visuals,
                targetGlowIntensity: 1,
                xenonFlashActive: true,
                bloomIntensity: 1.6,
              },
            });
          }
        }, Math.max(0, countdownDurationSeconds * 1000));
      }

      return {
        lifecycleConnectionStatus: "open",
        currentPhase: nextPhase,
        phaseElapsed: 0,
        totalElapsed: event.seq,
        subsystems,
        interlocks,
        visuals,
        countdownValue: startsCountdown ? 5 : state.countdownValue,
        countdownStartedAt: startsCountdown ? Date.now() : state.countdownStartedAt,
        countdownNodeId: startsCountdown ? event.node_id ?? null : state.countdownNodeId,
        countdownDisplaySeconds: startsCountdown ? countdownDisplaySeconds : state.countdownDisplaySeconds,
        countdownDurationSeconds: startsCountdown ? countdownDurationSeconds : state.countdownDurationSeconds,
        firingStartedAt: startsFiring ? Date.now() : state.firingStartedAt,
        targetImpactStartedAt: startsFiring ? Date.now() : state.targetImpactStartedAt,
        lastLifecycleEvent: event,
        lifecycleEvents: [...state.lifecycleEvents.slice(-199), event],
        lifecycleServiceStates,
        lifecycleSystemStats,
      };
    });
  },

  applyEngineEvent: (eventName, event) => {
    if (eventName === "gxlf.engine.status" && event.status === "idle" && event.reason === "reset") {
      get().reset();
    }
    set((state) => {
      const faultState = nextFaultState(eventName, event, state);
      const nextStatus =
        eventName === "gxlf.engine.status"
          ? event.status ?? state.engineStatus
          : eventName === "gxlf.engine.event" && event.event_type === "node_guard_blocked"
            ? "waiting_guard"
            : eventName === "gxlf.engine.event" && event.event_type === "node_guard_passed"
              ? "running"
          : eventName === "gxlf.bridge.complete"
            ? event.flow_status === "completed"
              ? "completed"
              : event.flow_status ?? state.engineStatus
            : eventName === "gxlf.bridge.error"
              ? "error"
              : state.engineStatus;
      return {
        lifecycleConnectionStatus: "open",
        engineStatus: nextStatus,
        engineLastCommand: event.command ?? state.engineLastCommand,
        engineMessage: engineMessageForEvent(eventName, event, state.engineMessage),
        ...faultState,
        engineSummary:
          eventName === "gxlf.bridge.complete" || eventName === "gxlf.bridge.error"
            ? event
            : state.engineSummary,
      };
    });
  },

  reset: () => {
    set({
      currentPhase: Phase.IDLE,
      phaseElapsed: 0,
      totalElapsed: 0,
      subsystems: createInitialSubsystems(),
      interlocks: createInitialInterlocks(),
      visuals: { ...DEFAULT_VISUALS },
      countdownValue: -1,
      countdownStartedAt: null,
      countdownNodeId: null,
      countdownDisplaySeconds: 5,
      countdownDurationSeconds: 5,
      firingStartedAt: null,
      targetImpactStartedAt: null,
      lastLifecycleEvent: null,
      lifecycleEvents: [],
      lifecycleServiceStates: {},
      lifecycleSystemStats: {},
      engineStatus: "idle",
      engineLastCommand: null,
      engineMessage: null,
      engineSummary: null,
      activeFaults: [],
      serviceHealthOverrides: {},
      guardContext: null,
    });
  },
}));
