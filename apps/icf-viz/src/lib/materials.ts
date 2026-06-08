import { COLORS } from "./constants";

export const MATERIAL_PROPS = {
  steel: {
    color: COLORS.steel,
    metalness: 0.8,
    roughness: 0.3,
  },
  darkSteel: {
    color: "#555566",
    metalness: 0.75,
    roughness: 0.35,
  },
  concrete: {
    color: COLORS.concrete,
    metalness: 0.05,
    roughness: 0.95,
  },
  glass: {
    color: COLORS.glass,
    metalness: 0.1,
    roughness: 0.1,
    transparent: true,
    opacity: 0.35,
  },
  copper: {
    color: COLORS.copper,
    metalness: 0.85,
    roughness: 0.25,
  },
  darkPanel: {
    color: "#1a1a24",
    metalness: 0.3,
    roughness: 0.7,
  },
  floor: {
    color: COLORS.floor,
    metalness: 0.15,
    roughness: 0.85,
  },
  wall: {
    color: COLORS.wall,
    metalness: 0.1,
    roughness: 0.9,
  },
} as const;
