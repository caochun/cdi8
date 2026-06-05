"use client";

import { useMemo } from "react";
import { useExperimentStore } from "@/state/experimentStore";
import {
  PHASES,
  PHASE_ORDER,
  getTotalCompressedDuration,
} from "@/state/phases";
import { Phase } from "@/types";
import {
  Play,
  Pause,
  SkipForward,
  SkipBack,
  RotateCcw,
} from "lucide-react";

const SPEEDS = [0.5, 1, 2, 4];

const PHASE_COLORS: Partial<Record<Phase, string>> = {
  [Phase.PARAM_DISPATCH]: "#4488ff",
  [Phase.PARALLEL_STARTUP]: "#4488ff",
  [Phase.CLEARANCE_LOCKDOWN]: "#ddaa00",
  [Phase.ENERGY_ALIGNMENT]: "#4488ff",
  [Phase.READINESS_CHECK]: "#22cc44",
  [Phase.FINAL_PREP]: "#ddaa00",
  [Phase.CHARGING]: "#ff8800",
  [Phase.COUNTDOWN]: "#ff4444",
  [Phase.FIRING]: "#ff2222",
  [Phase.TARGET_IMPLOSION]: "#ff2222",
  [Phase.DATA_COLLECTION]: "#aa66ff",
  [Phase.POST_PROCESS]: "#4488ff",
};

export function TimelineController() {
  const phase = useExperimentStore((s) => s.currentPhase);
  const isPlaying = useExperimentStore((s) => s.isPlaying);
  const speed = useExperimentStore((s) => s.playbackSpeed);
  const totalElapsed = useExperimentStore((s) => s.totalElapsed);
  const play = useExperimentStore((s) => s.play);
  const togglePlay = useExperimentStore((s) => s.togglePlay);
  const setSpeed = useExperimentStore((s) => s.setSpeed);
  const stepForward = useExperimentStore((s) => s.stepForward);
  const stepBackward = useExperimentStore((s) => s.stepBackward);
  const jumpToPhase = useExperimentStore((s) => s.jumpToPhase);
  const reset = useExperimentStore((s) => s.reset);

  const totalDuration = getTotalCompressedDuration();
  const progress =
    totalDuration > 0 ? Math.min(1, totalElapsed / totalDuration) : 0;
  const config = PHASES[phase];

  const segments = useMemo(() => {
    const segs: { phase: Phase; start: number; end: number; color: string }[] = [];
    let accum = 0;
    for (const p of PHASE_ORDER) {
      const d = PHASES[p].compressedDuration;
      if (d === Infinity) continue;
      const start = accum / totalDuration;
      accum += d;
      const end = accum / totalDuration;
      segs.push({
        phase: p,
        start,
        end,
        color: PHASE_COLORS[p] || "#4488ff",
      });
    }
    return segs;
  }, [totalDuration]);

  const handleScrub = (e: React.MouseEvent<HTMLDivElement>) => {
    const rect = e.currentTarget.getBoundingClientRect();
    const pct = Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width));
    let accum = 0;
    for (const p of PHASE_ORDER) {
      const d = PHASES[p].compressedDuration;
      if (d === Infinity) continue;
      if (accum + d >= pct * totalDuration) {
        jumpToPhase(p);
        return;
      }
      accum += d;
    }
  };

  const isFiring = phase === Phase.FIRING || phase === Phase.TARGET_IMPLOSION;

  return (
    <div
      className="absolute bottom-0 left-0 right-0 z-20"
      style={{
        background: "rgba(10, 10, 20, 0.88)",
        backdropFilter: "blur(12px)",
        borderTop: `1px solid ${isFiring ? "rgba(255, 60, 60, 0.3)" : "rgba(60, 60, 100, 0.3)"}`,
      }}
    >
      {/* Segmented progress bar */}
      <div
        className="h-1 relative cursor-pointer"
        onClick={handleScrub}
      >
        {segments.map((seg) => (
          <div
            key={seg.phase}
            className="absolute top-0 h-full"
            style={{
              left: `${seg.start * 100}%`,
              width: `${(seg.end - seg.start) * 100}%`,
              background:
                progress >= seg.end
                  ? seg.color
                  : progress > seg.start
                    ? `linear-gradient(to right, ${seg.color} ${((progress - seg.start) / (seg.end - seg.start)) * 100}%, rgba(40,40,60,0.5) ${((progress - seg.start) / (seg.end - seg.start)) * 100}%)`
                    : "rgba(40,40,60,0.3)",
              borderRight: "1px solid rgba(10,10,20,0.8)",
            }}
          />
        ))}
        {/* Playhead */}
        <div
          className="absolute top-0 w-0.5 h-full bg-white shadow-[0_0_6px_rgba(255,255,255,0.5)]"
          style={{ left: `${progress * 100}%` }}
        />
      </div>

      {/* Controls row */}
      <div className="px-4 py-2.5 flex items-center gap-3">
        <div className="flex items-center gap-1">
          <button
            onClick={reset}
            className="p-1.5 rounded hover:bg-white/10 text-white/50 hover:text-white transition-colors"
            title="重置 (R)"
          >
            <RotateCcw size={14} />
          </button>
          <button
            onClick={stepBackward}
            className="p-1.5 rounded hover:bg-white/10 text-white/50 hover:text-white transition-colors"
            title="上一阶段 (←)"
          >
            <SkipBack size={14} />
          </button>
          <button
            onClick={phase === Phase.IDLE ? play : togglePlay}
            className={`p-2 rounded-full text-white transition-all ${
              isFiring
                ? "bg-red-600 hover:bg-red-500"
                : "bg-blue-600 hover:bg-blue-500"
            }`}
            title={isPlaying ? "暂停 (Space)" : "播放 (Space)"}
          >
            {isPlaying ? <Pause size={16} /> : <Play size={16} />}
          </button>
          <button
            onClick={stepForward}
            className="p-1.5 rounded hover:bg-white/10 text-white/50 hover:text-white transition-colors"
            title="下一阶段 (→)"
          >
            <SkipForward size={14} />
          </button>
        </div>

        <div className="flex items-center gap-0.5 text-[11px]">
          {SPEEDS.map((s) => (
            <button
              key={s}
              onClick={() => setSpeed(s)}
              className={`px-1.5 py-0.5 rounded transition-colors ${
                speed === s
                  ? "bg-white/15 text-white"
                  : "text-white/35 hover:text-white/70 hover:bg-white/5"
              }`}
            >
              {s}x
            </button>
          ))}
        </div>

        <div className="flex-1" />

        <div
          className="text-xs font-medium px-2 py-0.5 rounded"
          style={{
            color: PHASE_COLORS[phase] || "#8888aa",
            background: `${PHASE_COLORS[phase] || "#8888aa"}15`,
          }}
        >
          {config.labelZh}
        </div>

        <div className="text-[11px] tabular-nums text-white/30">
          {Math.floor(totalElapsed)}s / {Math.floor(totalDuration)}s
        </div>
      </div>
    </div>
  );
}
