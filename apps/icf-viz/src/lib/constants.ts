export const BEAM_COUNT = 60;
export const TARGET_CHAMBER_RADIUS = 5;
export const BEAM_LENGTH = 35;
export const BUILDING_WIDTH = 120;
export const BUILDING_DEPTH = 80;
export const BUILDING_HEIGHT = 25;
export const FLOOR_Y = 0;
export const TARGET_CHAMBER_CENTER: [number, number, number] = [0, 8, 0];

export const COLORS = {
  steel: "#8a8a8a",
  concrete: "#b0a89a",
  glass: "#a0d0ff",
  copper: "#b87333",
  capacitorEmpty: "#555555",
  capacitorFull: "#ff4400",
  laserIR: "#ff2200",
  laserUV: "#7733ff",
  laserGreen: "#00ff44",
  warningAmber: "#ffaa00",
  warningRed: "#ff0000",
  xenonFlash: "#ffffff",
  plasma: "#ff8800",
  fusionWhite: "#ffffee",
  floor: "#2a2a2e",
  wall: "#3a3a3e",
  screenGlow: "#60a5fa",
};

export const SUBSYSTEM_STATUS_COLORS: Record<string, string> = {
  OFF: "#555555",
  STANDBY: "#888888",
  WARMING: "#ddaa00",
  READY: "#22cc44",
  ACTIVE: "#22aaff",
  FIRING: "#ff4444",
  COLLECTING: "#aa66ff",
  ERROR: "#ff0000",
};
