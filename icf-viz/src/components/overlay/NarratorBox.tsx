"use client";

import { useExperimentStore } from "@/state/experimentStore";
import { PHASES } from "@/state/phases";
import { Phase } from "@/types";

export function NarratorBox() {
  const phase = useExperimentStore((s) => s.currentPhase);
  const runMode = useExperimentStore((s) => s.runMode);
  const lastLifecycleEvent = useExperimentStore((s) => s.lastLifecycleEvent);
  const config = PHASES[phase];

  if (phase === Phase.IDLE || phase === Phase.COMPLETE) return null;

  return (
    <div className="absolute bottom-20 left-1/2 -translate-x-1/2 pointer-events-none z-10 max-w-2xl w-full px-4">
      <div
        className="text-center text-sm leading-relaxed px-6 py-3 rounded-lg"
        style={{
          background: "rgba(10, 10, 20, 0.75)",
          backdropFilter: "blur(8px)",
          border: "1px solid rgba(60, 60, 100, 0.3)",
          color: "rgba(200, 200, 230, 0.85)",
        }}
      >
        {runMode === "event" && lastLifecycleEvent ? (
          <span>
            #{lastLifecycleEvent.seq} {lastLifecycleEvent.system_name}
            {" / "}
            {lastLifecycleEvent.service_id ?? lastLifecycleEvent.instance_code}
            {" / "}
            {lastLifecycleEvent.command ?? lastLifecycleEvent.event_type}
            {" / "}
            {lastLifecycleEvent.task_state_after ?? lastLifecycleEvent.business_state_after ?? lastLifecycleEvent.event_type}
          </span>
        ) : (
          config.description
        )}
      </div>
    </div>
  );
}
