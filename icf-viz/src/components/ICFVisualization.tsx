"use client";

import { Suspense } from "react";
import { ICFCanvas } from "./canvas/ICFCanvas";
import { LoadingScreen } from "./LoadingScreen";
import { TimelineController } from "./overlay/TimelineController";
import { PhaseLabel } from "./overlay/PhaseLabel";
import { NarratorBox } from "./overlay/NarratorBox";
import { CountdownOverlay } from "./overlay/CountdownOverlay";
import { SubsystemPanel } from "./overlay/SubsystemPanel";
import { ReadinessFlags } from "./overlay/ReadinessFlags";
import { CameraSelector } from "./overlay/CameraSelector";
import { useKeyboardControls } from "@/hooks/useKeyboardControls";
import { useGXLFLifecycleEvents } from "@/hooks/useGXLFLifecycleEvents";
import { useExperimentStore } from "@/state/experimentStore";
import { Phase } from "@/types";
import { GXLF_DEVICE_INSTANCES } from "@/state/gxlfDeviceLayout";

function StartScreen() {
  const play = useExperimentStore((s) => s.play);
  const runMode = useExperimentStore((s) => s.runMode);
  const connectionStatus = useExperimentStore((s) => s.lifecycleConnectionStatus);
  const eventCount = useExperimentStore((s) => s.lifecycleEvents.length);

  const isEventMode = runMode === "event";
  const eventUrl = process.env.NEXT_PUBLIC_GXLF_EVENTS_URL;

  return (
    <div className="absolute inset-0 flex flex-col items-center justify-center z-30 pointer-events-auto">
      <div
        className="text-center"
        style={{
          background: "rgba(5, 5, 15, 0.7)",
          backdropFilter: "blur(20px)",
          padding: "3rem 4rem",
          borderRadius: "1rem",
          border: "1px solid rgba(60, 60, 120, 0.3)",
        }}
      >
        <h1 className="text-3xl font-bold mb-2 text-white/90">
          激光惯性约束聚变实验设施
        </h1>
        <p className="text-sm text-white/40 mb-1">
          Laser Inertial Confinement Fusion Facility
        </p>
        <p className="text-xs text-white/30 mb-8">
          60 束线 &middot; 16 类系统 &middot; {isEventMode ? "等待 GXLF 生命周期事件流" : "交互式实验流程演示"}
        </p>
        {isEventMode ? (
          <div className="px-8 py-3 rounded-lg border border-blue-400/20 bg-blue-500/10 text-blue-100/80 text-sm">
            <div>正在监听仿真事件总线</div>
            <div className="mt-2 text-[11px] text-blue-100/45 font-mono">
              {connectionStatus} / {eventCount} events
            </div>
            {eventUrl && (
              <div className="mt-1 text-[10px] text-blue-100/30 font-mono max-w-md truncate">
                {eventUrl}
              </div>
            )}
          </div>
        ) : (
          <button
            onClick={play}
            className="px-8 py-3 bg-blue-600 hover:bg-blue-500 text-white rounded-lg font-medium text-lg transition-all hover:scale-105 active:scale-95"
          >
            开始实验
          </button>
        )}
        <div className="text-[10px] text-white/20 mt-6 space-y-0.5">
          <p>鼠标拖动旋转视角 &middot; 滚轮缩放 &middot; 底部控制栏操作时间线</p>
          {!isEventMode && (
            <p>
              <kbd className="px-1 py-0.5 rounded bg-white/5 border border-white/10 text-white/30">Space</kbd> 播放/暂停
              &nbsp;&middot;&nbsp;
              <kbd className="px-1 py-0.5 rounded bg-white/5 border border-white/10 text-white/30">&larr;</kbd>
              <kbd className="px-1 py-0.5 rounded bg-white/5 border border-white/10 text-white/30">&rarr;</kbd> 切换阶段
              &nbsp;&middot;&nbsp;
              <kbd className="px-1 py-0.5 rounded bg-white/5 border border-white/10 text-white/30">1-4</kbd> 调速
              &nbsp;&middot;&nbsp;
              <kbd className="px-1 py-0.5 rounded bg-white/5 border border-white/10 text-white/30">R</kbd> 重置
            </p>
          )}
        </div>
      </div>
    </div>
  );
}

function CompleteScreen() {
  const reset = useExperimentStore((s) => s.reset);

  return (
    <div className="absolute inset-0 flex flex-col items-center justify-center z-30 pointer-events-auto">
      <div
        className="text-center"
        style={{
          background: "rgba(5, 5, 15, 0.7)",
          backdropFilter: "blur(20px)",
          padding: "3rem 4rem",
          borderRadius: "1rem",
          border: "1px solid rgba(60, 60, 120, 0.3)",
        }}
      >
        <div className="text-4xl mb-4">&#x2714;&#xFE0F;</div>
        <h2 className="text-2xl font-bold mb-2 text-green-400/90">
          实验完成
        </h2>
        <p className="text-sm text-white/50 mb-2">
          本次激光发射实验全流程演示结束
        </p>
        <div
          className="text-xs text-white/30 mb-6 py-3 px-4 rounded-lg mx-auto max-w-sm"
          style={{
            background: "rgba(34, 204, 68, 0.05)",
            border: "1px solid rgba(34, 204, 68, 0.15)",
          }}
        >
          <div className="grid grid-cols-2 gap-x-4 gap-y-1 text-left">
            <span>束线数量</span><span className="text-white/60">60</span>
            <span>总耗时</span><span className="text-white/60">~60 min (real)</span>
            <span>子系统</span><span className="text-white/60">15 类全参与</span>
            <span>安全联锁</span><span className="text-white/60">9 项全通过</span>
          </div>
        </div>
        <button
          onClick={reset}
          className="px-8 py-3 bg-blue-600 hover:bg-blue-500 text-white rounded-lg font-medium transition-all hover:scale-105 active:scale-95"
        >
          重新开始
        </button>
      </div>
    </div>
  );
}

function EventBridgeStatus() {
  const runMode = useExperimentStore((s) => s.runMode);
  const connectionStatus = useExperimentStore((s) => s.lifecycleConnectionStatus);
  const eventCount = useExperimentStore((s) => s.lifecycleEvents.length);
  const lastLifecycleEvent = useExperimentStore((s) => s.lastLifecycleEvent);
  const seenInstances = useExperimentStore(
    (s) => Object.keys(s.lifecycleServiceStates).length
  );

  if (runMode !== "event") return null;

  return (
    <div
      className="absolute top-3 left-3 z-20 pointer-events-none"
      style={{
        background: "rgba(10, 10, 20, 0.76)",
        backdropFilter: "blur(10px)",
        border: "1px solid rgba(80, 130, 255, 0.25)",
        borderRadius: "0.5rem",
        padding: "0.55rem 0.75rem",
      }}
    >
      <div className="text-[11px] text-white/45 uppercase tracking-wider">
        GXLF 事件驱动
      </div>
      <div className="mt-1 text-xs text-blue-100/80 font-mono">
        {connectionStatus} / {eventCount} events / {seenInstances}/{GXLF_DEVICE_INSTANCES.length} instances
      </div>
      {lastLifecycleEvent && (
        <>
          <div className="mt-1 max-w-xs truncate text-[10px] text-white/35">
            #{lastLifecycleEvent.seq} {lastLifecycleEvent.node_id} {lastLifecycleEvent.command} · {lastLifecycleEvent.system_name}
          </div>
          <div className="mt-0.5 text-[9px] text-white/25">
            顶部阶段为事件推断；右侧状态为服务实例聚合
          </div>
        </>
      )}
    </div>
  );
}

export default function ICFVisualization() {
  const phase = useExperimentStore((s) => s.currentPhase);
  const runMode = useExperimentStore((s) => s.runMode);
  const lastLifecycleEvent = useExperimentStore((s) => s.lastLifecycleEvent);
  useKeyboardControls();
  useGXLFLifecycleEvents();

  return (
    <div className="relative w-full h-full">
      <Suspense fallback={<LoadingScreen />}>
        <ICFCanvas />
      </Suspense>

      {phase === Phase.IDLE && runMode === "scripted" && <StartScreen />}
      {phase === Phase.COMPLETE && runMode === "scripted" && <CompleteScreen />}

      <EventBridgeStatus />
      <ReadinessFlags />
      <PhaseLabel />
      <SubsystemPanel />
      <CameraSelector />
      <NarratorBox />
      <CountdownOverlay />
      {runMode === "scripted" && <TimelineController />}
    </div>
  );
}
