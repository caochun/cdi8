"use client";

import { useExperimentStore } from "@/state/experimentStore";
import { useCameraOverride } from "@/state/cameraStore";
import { CAMERA_PRESETS } from "@/state/cameraPresets";
import { PHASES } from "@/state/phases";
import { Phase } from "@/types";

export function CameraSelector() {
  const phase = useExperimentStore((s) => s.currentPhase);
  const overridePreset = useCameraOverride((s) => s.overridePreset);
  const manualControl = useCameraOverride((s) => s.manualControl);
  const setOverride = useCameraOverride((s) => s.setOverridePreset);
  const setAutoFollow = useCameraOverride((s) => s.setAutoFollow);

  if (phase === Phase.IDLE || phase === Phase.COMPLETE) return null;

  const phaseCamera = PHASES[phase].camera;
  const activePreset = manualControl ? null : overridePreset || phaseCamera;

  return (
    <div
      className="absolute top-16 left-3 z-10 pointer-events-auto"
      style={{
        background: "rgba(10, 10, 20, 0.75)",
        backdropFilter: "blur(10px)",
        border: "1px solid rgba(60, 60, 100, 0.3)",
        borderRadius: "0.5rem",
      }}
    >
      <div className="px-3 py-2 text-xs text-white/40 uppercase tracking-wider border-b border-white/5">
        {manualControl ? "自由视角" : "视角"}
      </div>
      <div className="py-1">
        <button
          className={`block w-full px-3 py-1.5 text-left text-xs transition-colors ${
            !manualControl && !overridePreset
              ? "bg-blue-500/10 text-blue-400"
              : "text-white/50 hover:bg-white/5 hover:text-white"
          }`}
          onClick={setAutoFollow}
        >
          自动跟随
        </button>
        {Object.entries(CAMERA_PRESETS).map(([key, preset]) => {
          const isActive = activePreset === key;
          return (
            <button
              key={key}
              className={`block w-full text-left px-3 py-1.5 text-xs transition-colors ${
                isActive
                  ? "text-blue-400 bg-blue-500/10"
                  : "text-white/50 hover:text-white hover:bg-white/5"
              }`}
              onClick={() => setOverride(key)}
            >
              {preset.label}
            </button>
          );
        })}
      </div>
    </div>
  );
}
