"use client";

import { useExperimentStore } from "@/state/experimentStore";
import { SUBSYSTEM_DEFS } from "@/state/subsystems";
import { SUBSYSTEM_STATUS_COLORS } from "@/lib/constants";
import { Phase } from "@/types";

export function SubsystemPanel() {
  const phase = useExperimentStore((s) => s.currentPhase);
  const subsystems = useExperimentStore((s) => s.subsystems);
  const lastLifecycleEvent = useExperimentStore((s) => s.lastLifecycleEvent);
  const runMode = useExperimentStore((s) => s.runMode);
  const connectionStatus = useExperimentStore((s) => s.lifecycleConnectionStatus);
  const eventCount = useExperimentStore((s) => s.lifecycleEvents.length);
  const systemStats = useExperimentStore((s) => s.lifecycleSystemStats);

  if (phase === Phase.IDLE || phase === Phase.COMPLETE) return null;

  return (
    <div
      className="absolute top-16 right-3 z-10 w-52 pointer-events-auto"
      style={{
        background: "rgba(10, 10, 20, 0.8)",
        backdropFilter: "blur(10px)",
        border: "1px solid rgba(60, 60, 100, 0.3)",
        borderRadius: "0.5rem",
        maxHeight: "calc(100vh - 140px)",
        overflowY: "auto",
      }}
    >
      <div className="px-3 py-2 text-xs text-white/40 uppercase tracking-wider border-b border-white/5">
        子系统状态
      </div>
      {runMode === "event" && (
        <div className="px-3 py-2 border-b border-white/5 text-[10px] text-white/35">
          <span className="text-white/50">事件总线</span>
          <span className="ml-2 font-mono">{connectionStatus}</span>
          <span className="ml-2 font-mono">{eventCount} events</span>
        </div>
      )}
      <div className="py-1">
        {SUBSYSTEM_DEFS.map((def) => {
          const status = subsystems[def.id];
          const color = SUBSYSTEM_STATUS_COLORS[status] || "#555555";
          const stats = Object.values(systemStats).find((item) => item.subsystemId === def.id);
          return (
            <div
              key={def.id}
              className="px-3 py-1 text-xs"
            >
              <div className="flex items-center gap-2">
                <span
                  className="w-2 h-2 rounded-full flex-shrink-0"
                  style={{
                    backgroundColor: color,
                    boxShadow:
                      status !== "OFF" && status !== "STANDBY"
                        ? `0 0 6px ${color}`
                        : "none",
                  }}
                />
                <span className="flex-1 text-white/60 truncate">
                  {def.labelZh}
                </span>
                <span
                  className="text-[10px] font-mono"
                  style={{ color }}
                >
                  {status}
                </span>
              </div>
              {runMode === "event" && stats && (
                <div className="ml-4 mt-0.5 flex items-center gap-2 text-[9px] text-white/30 font-mono">
                  <span>{stats.seenCount}/{stats.expectedCount}</span>
                  {stats.latestCommand && <span className="truncate">{stats.latestCommand}</span>}
                </div>
              )}
            </div>
          );
        })}
      </div>
      {lastLifecycleEvent && (
        <div className="px-3 py-2 border-t border-white/5 text-[10px] text-white/35">
          <div className="text-white/50 mb-1">最近事件</div>
          <div className="truncate">
            #{lastLifecycleEvent.seq} {lastLifecycleEvent.node_id} {lastLifecycleEvent.command}
          </div>
          <div className="truncate">
            {lastLifecycleEvent.system_name}
          </div>
        </div>
      )}
    </div>
  );
}
