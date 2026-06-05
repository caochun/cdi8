import { useRef } from "react";
import * as THREE from "three";
import { useExperimentStore } from "@/state/experimentStore";
import { Phase } from "@/types";

export function SceneLighting() {
  const dirLightRef = useRef<THREE.DirectionalLight>(null);
  const phase = useExperimentStore((s) => s.currentPhase);

  const isFiring =
    phase === Phase.FIRING || phase === Phase.TARGET_IMPLOSION;
  const ambientIntensity = isFiring ? 0.15 : 0.4;
  const dirIntensity = isFiring ? 0.3 : 0.8;

  return (
    <>
      <ambientLight intensity={ambientIntensity} color="#b0b0d0" />
      <directionalLight
        ref={dirLightRef}
        position={[40, 60, 30]}
        intensity={dirIntensity}
        color="#ffffff"
        castShadow
        shadow-mapSize-width={2048}
        shadow-mapSize-height={2048}
        shadow-camera-near={0.5}
        shadow-camera-far={200}
        shadow-camera-left={-80}
        shadow-camera-right={80}
        shadow-camera-top={80}
        shadow-camera-bottom={-80}
        shadow-bias={-0.0001}
      />
      <hemisphereLight
        args={["#2244aa", "#111122", 0.15]}
      />
    </>
  );
}
