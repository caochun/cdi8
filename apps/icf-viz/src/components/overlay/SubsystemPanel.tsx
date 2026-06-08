"use client";

import { SUBSYSTEM_STATUS_COLORS } from "@/lib/constants";
import { GXLF_DEVICE_SYSTEM_ORDER } from "@/state/gxlfDeviceLayout";
import { useExperimentStore } from "@/state/experimentStore";
import { expectedCountForGXLFSystem } from "@/state/gxlfSystemMap";
import { SubsystemStatus } from "@/types";

export function SubsystemPanel() {
  const lastLifecycleEvent = useExperimentStore((s) => s.lastLifecycleEvent);
  const connectionStatus = useExperimentStore((s) => s.lifecycleConnectionStatus);
  const eventCount = useExperimentStore((s) => s.lifecycleEvents.length);
  const systemStats = useExperimentStore((s) => s.lifecycleSystemStats);
  const seenSystems = Object.keys(systemStats).length;
  const seenInstances = Object.values(systemStats).reduce((sum, item) => sum + item.seenCount, 0);
  const expectedInstances = GXLF_DEVICE_SYSTEM_ORDER.reduce(
    (sum, systemName) => sum + expectedCountForGXLFSystem(systemName),
    0
  );

  return (
    <div
      className="absolute top-16 right-3 z-10 w-64 pointer-events-auto"
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
        系统服务事件
      </div>
      <div className="px-3 py-2 border-b border-white/5 text-[10px] text-white/35">
        <span className="text-white/50">事件总线</span>
        <span className="ml-2 font-mono">{connectionStatus}</span>
        <span className="ml-2 font-mono">{eventCount} events</span>
        <div className="mt-1 font-mono">
          {seenSystems}/{GXLF_DEVICE_SYSTEM_ORDER.length} systems · {seenInstances}/{expectedInstances} instances
        </div>
      </div>
      <div className="py-1">
        {GXLF_DEVICE_SYSTEM_ORDER.map((systemName) => {
          const stats = systemStats[systemName];
          const status = stats?.status ?? SubsystemStatus.OFF;
          const color = SUBSYSTEM_STATUS_COLORS[status] || "#555555";
          return (
            <div key={systemName} className="px-3 py-1 text-xs">
              <div className="flex items-center gap-2">
                <span
                  className="w-2 h-2 rounded-full flex-shrink-0"
                  style={{
                    backgroundColor: color,
                    boxShadow:
                      status !== SubsystemStatus.OFF && status !== SubsystemStatus.STANDBY
                        ? `0 0 6px ${color}`
                        : "none",
                  }}
                />
                <span className="flex-1 text-white/60 truncate">{systemName}</span>
                <span className="text-[10px] font-mono" style={{ color }}>
                  {status}
                </span>
              </div>
              <div className="ml-4 mt-0.5 flex items-center gap-2 text-[9px] text-white/30 font-mono">
                <span>{stats?.seenCount ?? 0}/{expectedCountForGXLFSystem(systemName)}</span>
                {stats?.latestNodeId && <span>{stats.latestNodeId}</span>}
                {stats?.latestCommand && <span className="truncate">{stats.latestCommand}</span>}
              </div>
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
          <div className="truncate">{lastLifecycleEvent.system_name}</div>
        </div>
      )}
    </div>
  );
}
