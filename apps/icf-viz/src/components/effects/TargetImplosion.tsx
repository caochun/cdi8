import { useRef, memo } from "react";
import * as THREE from "three";
import { useFrame } from "@react-three/fiber";
import { useExperimentStore } from "@/state/experimentStore";
import { Phase } from "@/types";
import { COLORS, TARGET_CHAMBER_CENTER } from "@/lib/constants";

export const TargetImplosion = memo(function TargetImplosion() {
  const coreRef = useRef<THREE.Mesh>(null);
  const coreMaterialRef = useRef<THREE.MeshStandardMaterial>(null);
  const shockwaveRef = useRef<THREE.Mesh>(null);
  const shockwaveMaterialRef = useRef<THREE.MeshBasicMaterial>(null);
  const centralLightRef = useRef<THREE.PointLight>(null);

  const phase = useExperimentStore((s) => s.currentPhase);
  const targetImpactStartedAt = useExperimentStore((s) => s.targetImpactStartedAt);
  const targetGlow = useExperimentStore((s) => s.visuals.targetGlowIntensity);

  useFrame(() => {
    const effectiveElapsed = targetImpactStartedAt ? (Date.now() - targetImpactStartedAt) / 1000 : 999;
    const isImplosion = phase === Phase.TARGET_IMPLOSION || effectiveElapsed < 3.2;

    if (!isImplosion) {
      // Reset
      if (coreRef.current) coreRef.current.scale.setScalar(0.08);
      if (shockwaveRef.current) shockwaveRef.current.scale.setScalar(0);
      if (centralLightRef.current) {
        centralLightRef.current.intensity = THREE.MathUtils.lerp(
          centralLightRef.current.intensity,
          targetGlow * 20,
          0.05
        );
      }
      if (coreMaterialRef.current) {
        coreMaterialRef.current.emissiveIntensity = THREE.MathUtils.lerp(
          coreMaterialRef.current.emissiveIntensity,
          targetGlow * 2,
          0.05
        );
      }
      return;
    }

    const t = effectiveElapsed / 1.8;

    // Core: compress then expand
    if (coreRef.current) {
      let scale: number;
      if (t < 0.3) {
        // Compression: 0.08 → 0.02
        scale = THREE.MathUtils.lerp(0.08, 0.02, t / 0.3);
      } else if (t < 0.5) {
        // Ignition flash: 0.02 → 0.4
        scale = THREE.MathUtils.lerp(0.02, 0.4, (t - 0.3) / 0.2);
      } else {
        // Afterglow decay: 0.4 → 0.15
        scale = THREE.MathUtils.lerp(0.4, 0.15, (t - 0.5) / 0.5);
      }
      coreRef.current.scale.setScalar(Math.max(0.01, scale));
    }

    // Core color: gray → orange → white-hot → cool
    if (coreMaterialRef.current) {
      const mat = coreMaterialRef.current;
      if (t < 0.3) {
        mat.emissive.set(COLORS.plasma);
        mat.emissiveIntensity = THREE.MathUtils.lerp(1, 4, t / 0.3);
      } else if (t < 0.5) {
        mat.emissive.set(COLORS.fusionWhite);
        mat.emissiveIntensity = THREE.MathUtils.lerp(4, 8, (t - 0.3) / 0.2);
      } else {
        mat.emissive.set(COLORS.plasma);
        mat.emissiveIntensity = THREE.MathUtils.lerp(8, 0.5, (t - 0.5) / 0.5);
      }
    }

    // Central light
    if (centralLightRef.current) {
      if (t < 0.5) {
        centralLightRef.current.intensity = THREE.MathUtils.lerp(20, 200, t / 0.5);
        centralLightRef.current.color.set(COLORS.fusionWhite);
      } else {
        centralLightRef.current.intensity = THREE.MathUtils.lerp(200, 5, (t - 0.5) / 0.5);
        centralLightRef.current.color.set(COLORS.plasma);
      }
    }

    // Shockwave ring
    if (shockwaveRef.current && shockwaveMaterialRef.current) {
      if (t > 0.35 && t < 0.95) {
        const st = (t - 0.35) / 0.6;
        const ringScale = st * 8;
        shockwaveRef.current.scale.set(ringScale, ringScale, 1);
        shockwaveMaterialRef.current.opacity = (1 - st) * 0.6;
      } else {
        shockwaveRef.current.scale.setScalar(0);
        shockwaveMaterialRef.current.opacity = 0;
      }
    }
  });

  return (
    <group position={TARGET_CHAMBER_CENTER}>
      {/* Fusion core */}
      <mesh ref={coreRef} scale={0.08}>
        <sphereGeometry args={[1, 24, 24]} />
        <meshStandardMaterial
          ref={coreMaterialRef}
          color="#cccccc"
          emissive={COLORS.plasma}
          emissiveIntensity={0}
        />
      </mesh>

      {/* Shockwave ring */}
      <mesh ref={shockwaveRef} scale={0} rotation={[Math.PI / 2, 0, 0]}>
        <ringGeometry args={[0.8, 1, 32]} />
        <meshBasicMaterial
          ref={shockwaveMaterialRef}
          color={COLORS.fusionWhite}
          transparent
          opacity={0}
          side={THREE.DoubleSide}
          depthWrite={false}
        />
      </mesh>

      {/* Central point light */}
      <pointLight
        ref={centralLightRef}
        position={[0, 0, 0]}
        color={COLORS.fusionWhite}
        intensity={0}
        distance={80}
        decay={2}
      />
    </group>
  );
});
