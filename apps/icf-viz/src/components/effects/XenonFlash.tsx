import { useRef, memo } from "react";
import * as THREE from "three";
import { useFrame } from "@react-three/fiber";
import { useExperimentStore } from "@/state/experimentStore";
import { Phase } from "@/types";
import { TARGET_CHAMBER_CENTER } from "@/lib/constants";

const FLASH_POSITIONS: [number, number, number][] = [
  [-42, 5, -28],
  [-34, 5, 30],
  [38, 5, -28],
  [42, 5, 28],
  [0, 9, 0],
];

export const XenonFlash = memo(function XenonFlash() {
  const lightsRef = useRef<(THREE.PointLight | null)[]>([]);
  const phase = useExperimentStore((s) => s.currentPhase);
  const xenonActive = useExperimentStore((s) => s.visuals.xenonFlashActive);
  const phaseElapsed = useExperimentStore((s) => s.phaseElapsed);

  useFrame(() => {
    for (const light of lightsRef.current) {
      if (!light) continue;
      if (xenonActive && phase === Phase.FIRING) {
        // Intense flash that peaks and decays over the first 2 seconds
        const t = Math.min(phaseElapsed / 2, 1);
        const envelope = t < 0.15
          ? t / 0.15 // ramp up
          : Math.exp(-(t - 0.15) * 4); // exponential decay
        light.intensity = THREE.MathUtils.lerp(
          light.intensity,
          envelope * 120,
          0.15
        );
      } else {
        light.intensity = THREE.MathUtils.lerp(light.intensity, 0, 0.08);
      }
    }
  });

  return (
    <group>
      {FLASH_POSITIONS.map((pos, i) => (
        <pointLight
          key={i}
          ref={(el) => { lightsRef.current[i] = el; }}
          position={pos}
          color="#ffffee"
          intensity={0}
          distance={60}
          decay={2}
        />
      ))}

      {/* Ambient flash wash — covers the whole scene briefly */}
      {xenonActive && phase === Phase.FIRING && phaseElapsed < 1.5 && (
        <pointLight
          position={TARGET_CHAMBER_CENTER}
          color="#ffffdd"
          intensity={Math.max(0, (1.5 - phaseElapsed) * 40)}
          distance={120}
          decay={1.5}
        />
      )}
    </group>
  );
});
