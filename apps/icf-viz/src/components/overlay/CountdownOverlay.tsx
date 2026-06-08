"use client";

import { useEffect, useMemo, useState } from "react";
import { useExperimentStore } from "@/state/experimentStore";
import { Phase } from "@/types";

function useNow(active: boolean) {
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    if (!active) return;
    let frame = 0;
    const tick = () => {
      setNow(Date.now());
      frame = requestAnimationFrame(tick);
    };
    frame = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(frame);
  }, [active]);

  return now;
}

export function CountdownOverlay() {
  const phase = useExperimentStore((s) => s.currentPhase);
  const countdownStartedAt = useExperimentStore((s) => s.countdownStartedAt);
  const countdownDisplaySeconds = useExperimentStore((s) => s.countdownDisplaySeconds);
  const countdownDurationSeconds = useExperimentStore((s) => s.countdownDurationSeconds);
  const firingStartedAt = useExperimentStore((s) => s.firingStartedAt);
  const targetImpactStartedAt = useExperimentStore((s) => s.targetImpactStartedAt);
  const countdownActive = Boolean(countdownStartedAt);
  const firingActive = phase === Phase.FIRING && Boolean(firingStartedAt);
  const implosionActive = phase === Phase.TARGET_IMPLOSION && Boolean(targetImpactStartedAt);
  const now = useNow(countdownActive || firingActive || implosionActive);
  const effectiveCountdown = useMemo(() => {
    if (!countdownStartedAt) return -1;
    const elapsedSeconds = Math.max(0, (now - countdownStartedAt) / 1000);
    const duration = Math.max(0.001, countdownDurationSeconds);
    const progress = Math.min(1, elapsedSeconds / duration);
    return Math.max(0, countdownDisplaySeconds * (1 - progress));
  }, [countdownDisplaySeconds, countdownDurationSeconds, countdownStartedAt, now]);
  const effectivePhaseElapsed = firingStartedAt
    ? Math.max(0, (now - firingStartedAt) / 1000)
    : 999;
  const effectiveImplosionElapsed = targetImpactStartedAt
    ? Math.max(0, (now - targetImpactStartedAt) / 1000)
    : 999;

  if (effectiveCountdown > 0) {
    const num = Math.ceil(effectiveCountdown);
    const isUrgent = num <= 2;
    return (
      <div className="absolute inset-0 flex items-center justify-center pointer-events-none z-30">
        {/* Background pulse */}
        <div
          className="absolute inset-0 transition-opacity duration-200"
          style={{
            background: isUrgent
              ? "radial-gradient(circle, rgba(255,30,30,0.08) 0%, transparent 70%)"
              : "radial-gradient(circle, rgba(255,170,0,0.05) 0%, transparent 70%)",
          }}
        />
        {/* Number */}
        <div
          className="relative"
          key={num}
          style={{
            animation: "countdown-pop 0.4s ease-out",
          }}
        >
          <div
            className="text-[14rem] font-black tabular-nums leading-none select-none"
            style={{
              color: isUrgent ? "#ff3333" : "#ffaa00",
              textShadow: isUrgent
                ? "0 0 80px rgba(255,50,50,0.7), 0 0 160px rgba(255,50,50,0.3)"
                : "0 0 60px rgba(255,170,0,0.5), 0 0 120px rgba(255,170,0,0.2)",
            }}
          >
            {num}
          </div>
        </div>
        <style>{`
          @keyframes countdown-pop {
            0% { transform: scale(1.3); opacity: 0.5; }
            100% { transform: scale(1); opacity: 1; }
          }
        `}</style>
      </div>
    );
  }

  if (phase === Phase.FIRING && effectivePhaseElapsed < 1.5) {
    const flashOpacity = Math.max(0, (1 - effectivePhaseElapsed / 1.5) * 0.6);
    return (
      <div className="absolute inset-0 pointer-events-none z-30">
        {/* White flash */}
        <div
          className="absolute inset-0"
          style={{
            background: `rgba(255,255,240,${flashOpacity})`,
          }}
        />
        {/* FIRE text */}
        <div className="absolute inset-0 flex items-center justify-center">
          <div
            className="text-7xl font-black tracking-[0.5em] uppercase select-none"
            style={{
              color: `rgba(255, 68, 68, ${Math.max(0, 1 - effectivePhaseElapsed / 2)})`,
              textShadow:
                "0 0 40px rgba(255,50,50,0.8), 0 0 80px rgba(255,50,50,0.4), 0 0 120px rgba(255,50,50,0.2)",
            }}
          >
            FIRE
          </div>
        </div>
      </div>
    );
  }

  if (phase === Phase.TARGET_IMPLOSION && effectiveImplosionElapsed < 0.8) {
    const glowOpacity = Math.max(0, (1 - effectiveImplosionElapsed / 0.8) * 0.3);
    return (
      <div className="absolute inset-0 pointer-events-none z-30">
        <div
          className="absolute inset-0"
          style={{
            background: `radial-gradient(circle, rgba(255,200,100,${glowOpacity}) 0%, transparent 50%)`,
          }}
        />
      </div>
    );
  }

  return null;
}
