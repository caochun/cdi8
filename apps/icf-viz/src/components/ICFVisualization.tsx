"use client";

import { Suspense } from "react";
import { ICFCanvas } from "./canvas/ICFCanvas";
import { LoadingScreen } from "./LoadingScreen";
import { PhaseLabel } from "./overlay/PhaseLabel";
import { NarratorBox } from "./overlay/NarratorBox";
import { CountdownOverlay } from "./overlay/CountdownOverlay";
import { SubsystemPanel } from "./overlay/SubsystemPanel";
import { ReadinessFlags } from "./overlay/ReadinessFlags";
import { CameraSelector } from "./overlay/CameraSelector";
import { EngineControlPanel } from "./overlay/EngineControlPanel";
import { FaultInjectionPanel } from "./overlay/FaultInjectionPanel";
import { useKeyboardControls } from "@/hooks/useKeyboardControls";
import { useGXLFLifecycleEvents } from "@/hooks/useGXLFLifecycleEvents";
import { useExperimentStore } from "@/state/experimentStore";
import { Phase } from "@/types";
import { DEFAULT_GXLF_EVENTS_URL } from "@/hooks/useGXLFLifecycleEvents";

function EventWaitingOverlay() {
  const phase = useExperimentStore((s) => s.currentPhase);
  const connectionStatus = useExperimentStore((s) => s.lifecycleConnectionStatus);
  const eventCount = useExperimentStore((s) => s.lifecycleEvents.length);
  const eventUrl = process.env.NEXT_PUBLIC_GXLF_EVENTS_URL ?? DEFAULT_GXLF_EVENTS_URL;

  if (phase !== Phase.IDLE || eventCount > 0) return null;

  const statusText =
    connectionStatus === "open"
      ? "事件流已连接，通过左下角控制面板启动引擎"
      : connectionStatus === "connecting"
        ? "正在连接 GXLF 生命周期事件流"
        : connectionStatus === "error"
          ? "事件桥未连接"
          : "准备连接 GXLF 生命周期事件流";

  return (
    <div className="absolute left-1/2 top-20 z-20 -translate-x-1/2 pointer-events-none">
      <div
        className="w-[min(34rem,calc(100vw-2rem))] rounded-lg px-4 py-3 text-center"
        style={{
          background: "rgba(10, 10, 20, 0.72)",
          backdropFilter: "blur(10px)",
          border: "1px solid rgba(80, 130, 255, 0.22)",
        }}
      >
        <div className="text-sm font-medium text-blue-100/85">{statusText}</div>
        <div className="mt-1 truncate font-mono text-[11px] text-blue-100/38">
          {eventUrl}
        </div>
        {connectionStatus === "error" && (
          <div className="mt-2 text-xs text-white/45">
            在仓库根目录运行 `make dev`，或单独启动 `make sim-bridge` 后刷新页面。
          </div>
        )}
      </div>
    </div>
  );
}

export default function ICFVisualization() {
  useKeyboardControls();
  useGXLFLifecycleEvents();

  return (
    <div className="relative w-full h-full">
      <Suspense fallback={<LoadingScreen />}>
        <ICFCanvas />
      </Suspense>

      <EventWaitingOverlay />
      <ReadinessFlags />
      <PhaseLabel />
      <SubsystemPanel />
      <CameraSelector />
      <NarratorBox />
      <CountdownOverlay />
      <EngineControlPanel />
      <FaultInjectionPanel />
    </div>
  );
}
