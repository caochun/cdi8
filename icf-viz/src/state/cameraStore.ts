import { create } from "zustand";

interface CameraOverride {
  overridePreset: string | null;
  setOverridePreset: (preset: string | null) => void;
  clearOverride: () => void;
}

export const useCameraOverride = create<CameraOverride>((set) => ({
  overridePreset: null,
  setOverridePreset: (preset) => set({ overridePreset: preset }),
  clearOverride: () => set({ overridePreset: null }),
}));
