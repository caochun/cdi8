import { create } from "zustand";

interface CameraOverride {
  overridePreset: string | null;
  manualControl: boolean;
  setOverridePreset: (preset: string | null) => void;
  setSystemOverridePreset: (preset: string | null) => void;
  setManualControl: () => void;
  setAutoFollow: () => void;
  clearOverride: () => void;
  clearOverrideForPhaseChange: () => void;
}

export const useCameraOverride = create<CameraOverride>((set) => ({
  overridePreset: null,
  manualControl: false,
  setOverridePreset: (preset) => set({ overridePreset: preset, manualControl: false }),
  setSystemOverridePreset: (preset) =>
    set((state) => (state.manualControl ? state : { overridePreset: preset, manualControl: false })),
  setManualControl: () => set({ overridePreset: null, manualControl: true }),
  setAutoFollow: () => set({ overridePreset: null, manualControl: false }),
  clearOverride: () => set({ overridePreset: null, manualControl: false }),
  clearOverrideForPhaseChange: () =>
    set((state) => (state.manualControl ? state : { overridePreset: null, manualControl: false })),
}));
