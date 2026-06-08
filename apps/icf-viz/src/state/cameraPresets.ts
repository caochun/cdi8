import type { CameraPreset } from "@/types";

export const CAMERA_PRESETS: Record<string, CameraPreset> = {
  overview: {
    position: [0, 54, 58],
    target: [0, 6, 0],
    fov: 54,
    label: "全景总览",
  },
  targetChamber: {
    position: [24, 22, 28],
    target: [0, 8, 0],
    fov: 45,
    label: "靶室",
  },
  controlRoom: {
    position: [-62, 18, 44],
    target: [-48, 4, 28],
    fov: 55,
    label: "总控室",
  },
  beamPath: {
    position: [-42, 16, 46],
    target: [0, 7, 0],
    fov: 40,
    label: "束线路径",
  },
  amplifierHall: {
    position: [-26, 24, 58],
    target: [0, 5, 0],
    fov: 50,
    label: "放大器厅",
  },
  capacitorBank: {
    position: [58, 18, -40],
    target: [38, 3, -26],
    fov: 45,
    label: "电容器组",
  },
  cinematic: {
    position: [46, 48, 56],
    target: [0, 6, 0],
    fov: 50,
    label: "环绕漫游",
  },
};
