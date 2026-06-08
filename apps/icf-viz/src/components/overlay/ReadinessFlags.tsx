"use client";

import { useExperimentStore } from "@/state/experimentStore";
import { INTERLOCK_DEFS } from "@/state/interlocks";
import { InterlockId, Phase } from "@/types";

const EVENT_BACKED_FLAGS: InterlockId[] = [
  InterlockId.SHIELDING_DOOR,
  InterlockId.PERSONNEL_CLEAR,
  InterlockId.TIMING_LOCKED,
  InterlockId.DIAGNOSTICS_ARMED,
];

export function ReadinessFlags() {
  const phase = useExperimentStore((s) => s.currentPhase);
  const interlocks = useExperimentStore((s) => s.interlocks);

  if (phase === Phase.IDLE || phase === Phase.COMPLETE) return null;

  return (
    <div
      className="absolute top-3 left-1/2 -translate-x-1/2 z-10 flex items-center gap-1 px-3 py-1.5 pointer-events-none"
      style={{
        background: "rgba(10, 10, 20, 0.75)",
        backdropFilter: "blur(10px)",
        border: "1px solid rgba(60, 60, 100, 0.3)",
        borderRadius: "9999px",
      }}
    >
      {EVENT_BACKED_FLAGS.map((id) => {
        const def = INTERLOCK_DEFS.find((item) => item.id === id);
        if (!def) return null;
        const ready = interlocks[def.id];
        const color = ready ? "#22cc44" : "#cc2222";
        return (
          <div key={def.id} className="flex flex-col items-center gap-0.5" title={def.labelZh}>
            <span
              className="w-2.5 h-2.5 rounded-full transition-colors duration-700"
              style={{
                backgroundColor: color,
                boxShadow: ready
                  ? "0 0 8px rgba(34, 204, 68, 0.6)"
                  : "0 0 4px rgba(204, 34, 34, 0.3)",
              }}
            />
            <span className="text-[8px] text-white/30 max-w-[48px] text-center leading-tight truncate">
              {def.labelZh}
            </span>
          </div>
        );
      })}
    </div>
  );
}
