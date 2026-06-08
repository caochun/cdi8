"use client";

import { useExperimentStore } from "@/state/experimentStore";
import { PHASES } from "@/state/phases";
import { Phase } from "@/types";

export function PhaseLabel() {
  const phase = useExperimentStore((s) => s.currentPhase);
  const lastLifecycleEvent = useExperimentStore((s) => s.lastLifecycleEvent);
  const config = PHASES[phase];

  if (phase === Phase.IDLE || phase === Phase.COMPLETE) return null;

  const isFiring = phase === Phase.FIRING || phase === Phase.TARGET_IMPLOSION;
  const title = lastLifecycleEvent
    ? `${lastLifecycleEvent.node_id ?? ""} ${lastLifecycleEvent.node_name ?? ""}`.trim()
    : config.label;
  const subtitle = lastLifecycleEvent
    ? `${lastLifecycleEvent.command ?? lastLifecycleEvent.event_type} / ${lastLifecycleEvent.task_state_after ?? lastLifecycleEvent.event_type}`
    : config.labelZh;

  return (
    <div className="absolute top-16 left-1/2 -translate-x-1/2 pointer-events-none z-10">
      <div
        className={`text-center transition-all duration-500 ${
          isFiring ? "scale-110" : ""
        }`}
      >
        <div
          className={`text-xs tracking-[0.3em] uppercase mb-1 ${
            isFiring ? "text-red-400" : "text-blue-400/70"
          }`}
        >
          {title}
        </div>
        <div
          className={`text-lg font-bold ${
            isFiring ? "text-red-300" : "text-white/90"
          }`}
        >
          {subtitle}
        </div>
      </div>
    </div>
  );
}
