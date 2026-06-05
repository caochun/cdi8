import { create } from "zustand";
import {
  Phase,
  SubsystemId,
  SubsystemStatus,
  InterlockId,
  type VisualParams,
  type GXLFLifecycleEvent,
  type LifecycleServiceState,
  type LifecycleSystemStats,
} from "@/types";
import { PHASES, PHASE_ORDER, getNextPhase, getPrevPhase } from "./phases";
import { createInitialSubsystems } from "./subsystems";
import { createInitialInterlocks } from "./interlocks";
import { useCameraOverride } from "./cameraStore";
import {
  interlockChangesForLifecycleEvent,
  deriveSystemStats,
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
  runMode: "scripted" | "event";
  currentPhase: Phase;
  phaseElapsed: number;
  totalElapsed: number;
  isPlaying: boolean;
  playbackSpeed: number;
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

  setRunMode: (mode: "scripted" | "event") => void;
  setLifecycleConnectionStatus: (status: "idle" | "connecting" | "open" | "error") => void;
  play: () => void;
  pause: () => void;
  togglePlay: () => void;
  setSpeed: (speed: number) => void;
  applyLifecycleEvent: (event: GXLFLifecycleEvent) => void;
  jumpToPhase: (phase: Phase) => void;
  stepForward: () => void;
  stepBackward: () => void;
  tick: (delta: number) => void;
  reset: () => void;
}

function applyPhaseTransition(
  state: ExperimentState,
  phase: Phase
): Partial<ExperimentState> {
  const config = PHASES[phase];
  const subsystems = { ...state.subsystems };
  const interlocks = { ...state.interlocks };
  const visuals = { ...state.visuals };

  for (const [key, val] of Object.entries(config.subsystemChanges)) {
    subsystems[key as SubsystemId] = val as SubsystemStatus;
  }
  for (const [key, val] of Object.entries(config.interlockChanges)) {
    interlocks[key as InterlockId] = val as boolean;
  }
  for (const [key, val] of Object.entries(config.visuals)) {
    (visuals as Record<string, unknown>)[key] = val;
  }

  return {
    currentPhase: phase,
    phaseElapsed: 0,
    subsystems,
    interlocks,
    visuals,
    countdownValue: phase === Phase.COUNTDOWN ? 5 : -1,
  };
}

function computeTotalElapsed(targetPhase: Phase): number {
  let total = 0;
  for (const p of PHASE_ORDER) {
    if (p === targetPhase) break;
    const d = PHASES[p].compressedDuration;
    if (d !== Infinity) total += d;
  }
  return total;
}

function phaseForLifecycleEvent(event: GXLFLifecycleEvent): Phase {
  const nodeNumber = Number(event.node_id?.replace(/^N/, ""));
  const command = event.command ?? "";
  const stage = event.stage_id ?? "";

  if (event.node_name === "正式发射完成" || event.node_id === "N37") {
    return Phase.FIRING;
  }
  if (command === "SyncTrigger") return Phase.COUNTDOWN;
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
  const subsystemId = subsystemForGXLFSystem(event.system_name);
  return {
    serviceId: serviceKeyForLifecycleEvent(event),
    systemName: event.system_name,
    subsystemId,
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

export const useExperimentStore = create<ExperimentState>((set, get) => ({
  runMode: "scripted",
  currentPhase: Phase.IDLE,
  phaseElapsed: 0,
  totalElapsed: 0,
  isPlaying: false,
  playbackSpeed: 1,
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

  setRunMode: (mode: "scripted" | "event") => set({ runMode: mode }),
  setLifecycleConnectionStatus: (status: "idle" | "connecting" | "open" | "error") =>
    set({ lifecycleConnectionStatus: status }),

  play: () => {
    const s = get();
    if (s.currentPhase === Phase.IDLE) {
      set({
        ...applyPhaseTransition(s, Phase.PARAM_DISPATCH),
        isPlaying: true,
        totalElapsed: 0,
      });
    } else if (s.currentPhase === Phase.COMPLETE) {
      // no-op, use reset
    } else {
      set({ isPlaying: true });
    }
  },
  pause: () => set({ isPlaying: false }),
  togglePlay: () => {
    const s = get();
    if (s.isPlaying) {
      s.pause();
    } else {
      s.play();
    }
  },
  setSpeed: (speed: number) => set({ playbackSpeed: speed }),

  applyLifecycleEvent: (event: GXLFLifecycleEvent) => {
    set((state) => {
      const subsystems = { ...state.subsystems };
      const interlocks = {
        ...state.interlocks,
        ...interlockChangesForLifecycleEvent(event),
      };
      const visuals = {
        ...state.visuals,
        ...visualChangesForLifecycleEvent(event),
      };
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

      if (systemStats.subsystemId) {
        subsystems[systemStats.subsystemId] = systemStats.status;
      }

      const nextPhase = phaseForLifecycleEvent(event);
      const startsCountdown =
        event.command === "SyncTrigger" &&
        (state.countdownNodeId !== event.node_id || state.countdownStartedAt === null);
      const startsFiring =
        event.node_name === "正式发射完成" ||
        event.node_id === "N37" ||
        event.command === "EmergencyTrigger";
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
            useCameraOverride.getState().setOverridePreset("targetChamber");
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
        runMode: "event",
        lifecycleConnectionStatus: "open",
        currentPhase: nextPhase,
        phaseElapsed: 0,
        totalElapsed: event.seq,
        isPlaying: false,
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

  jumpToPhase: (phase: Phase) => {
    const s = get();
    let state = { ...s };
    // Replay all transitions up to target phase
    const subsystems = createInitialSubsystems();
    const interlocks = createInitialInterlocks();
    const visuals = { ...DEFAULT_VISUALS };

    for (const p of PHASE_ORDER) {
      const config = PHASES[p];
      for (const [k, v] of Object.entries(config.subsystemChanges)) {
        subsystems[k as SubsystemId] = v as SubsystemStatus;
      }
      for (const [k, v] of Object.entries(config.interlockChanges)) {
        interlocks[k as InterlockId] = v as boolean;
      }
      for (const [k, v] of Object.entries(config.visuals)) {
        (visuals as Record<string, unknown>)[k] = v;
      }
      if (p === phase) break;
    }

    set({
      currentPhase: phase,
      phaseElapsed: 0,
      totalElapsed: computeTotalElapsed(phase),
      subsystems,
      interlocks,
      visuals,
      countdownValue: phase === Phase.COUNTDOWN ? 5 : -1,
      isPlaying: phase !== Phase.IDLE && phase !== Phase.COMPLETE,
    });
  },

  stepForward: () => {
    const s = get();
    const next = getNextPhase(s.currentPhase);
    if (next) {
      set({
        ...applyPhaseTransition(s, next),
        totalElapsed: computeTotalElapsed(next),
      });
    }
  },

  stepBackward: () => {
    const s = get();
    const prev = getPrevPhase(s.currentPhase);
    if (prev) {
      get().jumpToPhase(prev);
    }
  },

  tick: (delta: number) => {
    const s = get();
    if (!s.isPlaying) return;

    const config = PHASES[s.currentPhase];
    const newElapsed = s.phaseElapsed + delta;
    const newTotal = s.totalElapsed + delta;

    // Countdown special handling
    if (s.currentPhase === Phase.COUNTDOWN) {
      const newCountdown = Math.max(0, 5 - newElapsed);
      set({
        phaseElapsed: newElapsed,
        totalElapsed: newTotal,
        countdownValue: newCountdown,
      });
    }

    // Charging: animate capacitor level
    if (s.currentPhase === Phase.CHARGING) {
      const progress = Math.min(1, newElapsed / config.compressedDuration);
      set({
        phaseElapsed: newElapsed,
        totalElapsed: newTotal,
        visuals: {
          ...s.visuals,
          capacitorChargeLevel: progress,
        },
      });
    }

    if (newElapsed >= config.compressedDuration) {
      const next = getNextPhase(s.currentPhase);
      if (next) {
        set({
          ...applyPhaseTransition(s, next),
          totalElapsed: newTotal,
        });
      } else {
        set({ isPlaying: false, phaseElapsed: newElapsed, totalElapsed: newTotal });
      }
    } else {
      set({ phaseElapsed: newElapsed, totalElapsed: newTotal });
    }
  },

  reset: () => {
    set({
      currentPhase: Phase.IDLE,
      phaseElapsed: 0,
      totalElapsed: 0,
      isPlaying: false,
      playbackSpeed: 1,
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
    });
  },
}));
