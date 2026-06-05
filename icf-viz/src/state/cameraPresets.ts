import type { CameraPreset } from "@/types";

export const CAMERA_PRESETS: Record<string, CameraPreset> = {
  overview: {
    position: [0, 78, 105],
    target: [0, 4, 0],
    fov: 50,
    label: "全景总览",
  },
  targetChamber: {
    position: [58, 20, 28],
    target: [42, 8, 0],
    fov: 45,
    label: "靶室",
  },
  controlRoom: {
    position: [-64, 14, 18],
    target: [-52, 4, 0],
    fov: 55,
    label: "总控室",
  },
  beamPath: {
    position: [-18, 12, 42],
    target: [-4, 3, 0],
    fov: 40,
    label: "束线路径",
  },
  amplifierHall: {
    position: [-8, 18, 38],
    target: [-8, 4, 0],
    fov: 50,
    label: "放大器厅",
  },
  capacitorBank: {
    position: [18, 14, 44],
    target: [-6, 3, 30],
    fov: 45,
    label: "电容器组",
  },
  cinematic: {
    position: [0, 58, 92],
    target: [0, 4, 0],
    fov: 50,
    label: "环绕漫游",
  },
};
