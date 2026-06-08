import { useRef, memo } from "react";
import * as THREE from "three";
import { useFrame } from "@react-three/fiber";
import { useExperimentStore } from "@/state/experimentStore";
import { Phase } from "@/types";
import { COLORS } from "@/lib/constants";
import { GXLF_BEAM_COUNT, GXLF_BEAM_LAYOUTS } from "@/state/gxlfDeviceLayout";

export const BeamPropagation = memo(function BeamPropagation() {
  const groupRef = useRef<THREE.Group>(null);
  const phase = useExperimentStore((s) => s.currentPhase);
  const phaseElapsed = useExperimentStore((s) => s.phaseElapsed);
  const beamsVisible = useExperimentStore((s) => s.visuals.laserBeamsVisible);
  const intensity = useExperimentStore((s) => s.visuals.laserBeamsIntensity);

  const isFiring = phase === Phase.FIRING;
  const isImplosion = phase === Phase.TARGET_IMPLOSION;

  // Per-beam animated "bolt" meshes
  const boltsRef = useRef<(THREE.Mesh | null)[]>([]);
  const boltMatsRef = useRef<(THREE.MeshBasicMaterial | null)[]>([]);

  useFrame(() => {
    if (!isFiring && !isImplosion) {
      // Hide all bolts
      for (const bolt of boltsRef.current) {
        if (bolt) bolt.visible = false;
      }
      return;
    }

    for (let i = 0; i < GXLF_BEAM_COUNT; i++) {
      const bolt = boltsRef.current[i];
      const mat = boltMatsRef.current[i];
      if (!bolt || !mat) continue;
      const beam = GXLF_BEAM_LAYOUTS[i];
      const outerPos = new THREE.Vector3(...beam.outer);
      const innerPos = new THREE.Vector3(...beam.targetPoint);

      // Stagger each beam by a tiny offset for visual drama
      const stagger = (i / GXLF_BEAM_COUNT) * 1.5;
      const localT = Math.max(0, phaseElapsed - stagger);

      if (isFiring) {
        // Propagation: beam bolt travels from outer to inner over ~2 seconds
        const travelDuration = 2;
        const progress = Math.min(1, localT / travelDuration);

        if (progress <= 0) {
          bolt.visible = false;
          continue;
        }

        bolt.visible = true;

        // Bolt position: lerp along the beam path
        const boltPos = new THREE.Vector3().lerpVectors(
          outerPos,
          innerPos,
          progress
        );
        bolt.position.copy(boltPos);

        // Bolt length shrinks as it reaches target
        const boltLen = Math.max(0.5, (1 - progress) * 4 + 0.5);
        bolt.scale.set(1, boltLen, 1);

        // Orientation
        const beamDir = new THREE.Vector3()
          .subVectors(innerPos, outerPos)
          .normalize();
        bolt.quaternion.setFromUnitVectors(
          new THREE.Vector3(0, 1, 0),
          beamDir
        );

        // Color transition: green → UV purple as "frequency converts"
        const colorProgress = Math.min(1, progress * 1.5);
        if (colorProgress < 0.6) {
          mat.color.set(COLORS.laserGreen);
        } else {
          mat.color.set(COLORS.laserUV);
        }
        mat.opacity = Math.min(0.9, 0.4 + progress * 0.5);
      }

      if (isImplosion) {
        // All beams converged: glow at the inner end and fade out
        bolt.visible = true;
        bolt.position.copy(innerPos);
        bolt.scale.set(1, 1, 1);
        mat.color.set(COLORS.laserUV);
        mat.opacity = Math.max(0, 0.8 - phaseElapsed * 0.3);

        const beamDir = new THREE.Vector3()
          .subVectors(innerPos, outerPos)
          .normalize();
        bolt.quaternion.setFromUnitVectors(
          new THREE.Vector3(0, 1, 0),
          beamDir
        );
      }
    }
  });

  return (
    <group ref={groupRef}>
      {GXLF_BEAM_LAYOUTS.map((_, i) => (
        <mesh
          key={i}
          ref={(el) => {
            boltsRef.current[i] = el;
          }}
          visible={false}
        >
          <cylinderGeometry args={[0.08, 0.04, 1, 6]} />
          <meshBasicMaterial
            ref={(el) => {
              boltMatsRef.current[i] = el;
            }}
            color={COLORS.laserGreen}
            transparent
            opacity={0}
            depthWrite={false}
          />
        </mesh>
      ))}
    </group>
  );
});
