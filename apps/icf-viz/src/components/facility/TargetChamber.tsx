import { useRef, useMemo, memo } from "react";
import * as THREE from "three";
import { useFrame } from "@react-three/fiber";
import { useExperimentStore } from "@/state/experimentStore";
import { TARGET_CHAMBER_RADIUS, BEAM_COUNT, COLORS } from "@/lib/constants";

function generatePortPositions(count: number, radius: number): THREE.Vector3[] {
  const positions: THREE.Vector3[] = [];
  const goldenAngle = Math.PI * (3 - Math.sqrt(5));
  for (let i = 0; i < count; i++) {
    const y = 1 - (i / (count - 1)) * 2;
    const radiusAtY = Math.sqrt(1 - y * y);
    const theta = goldenAngle * i;
    positions.push(
      new THREE.Vector3(
        Math.cos(theta) * radiusAtY * radius,
        y * radius,
        Math.sin(theta) * radiusAtY * radius
      )
    );
  }
  return positions;
}

export const TargetChamber = memo(function TargetChamber() {
  const groupRef = useRef<THREE.Group>(null);
  const glowRef = useRef<THREE.PointLight>(null);
  const targetGlow = useExperimentStore((s) => s.visuals.targetGlowIntensity);

  const portPositions = useMemo(
    () => generatePortPositions(BEAM_COUNT, TARGET_CHAMBER_RADIUS),
    []
  );

  useFrame(() => {
    if (glowRef.current) {
      const target = targetGlow * 80;
      glowRef.current.intensity = THREE.MathUtils.lerp(
        glowRef.current.intensity,
        target,
        0.08
      );
    }
  });

  return (
    <group ref={groupRef}>
      {/* Main chamber sphere */}
      <mesh castShadow receiveShadow>
        <sphereGeometry args={[TARGET_CHAMBER_RADIUS, 64, 64]} />
        <meshStandardMaterial
          color={COLORS.steel}
          metalness={0.85}
          roughness={0.25}
          side={THREE.DoubleSide}
        />
      </mesh>

      {/* Equatorial ring */}
      <mesh rotation={[Math.PI / 2, 0, 0]}>
        <torusGeometry args={[TARGET_CHAMBER_RADIUS + 0.15, 0.3, 16, 64]} />
        <meshStandardMaterial
          color="#666688"
          metalness={0.9}
          roughness={0.2}
        />
      </mesh>

      {/* Beam entry ports */}
      {portPositions.map((pos, i) => {
        const dir = pos.clone().normalize();
        const quat = new THREE.Quaternion();
        quat.setFromUnitVectors(new THREE.Vector3(0, 1, 0), dir);
        return (
          <mesh
            key={i}
            position={pos}
            quaternion={quat}
          >
            <cylinderGeometry args={[0.2, 0.25, 0.5, 8]} />
            <meshStandardMaterial
              color="#333344"
              metalness={0.7}
              roughness={0.4}
            />
          </mesh>
        );
      })}

      {/* Support legs */}
      {[0, Math.PI / 2, Math.PI, Math.PI * 1.5].map((angle, i) => (
        <mesh
          key={`leg-${i}`}
          position={[
            Math.cos(angle) * (TARGET_CHAMBER_RADIUS + 1),
            -TARGET_CHAMBER_RADIUS - 1.5,
            Math.sin(angle) * (TARGET_CHAMBER_RADIUS + 1),
          ]}
        >
          <cylinderGeometry args={[0.3, 0.4, TARGET_CHAMBER_RADIUS * 2, 8]} />
          <meshStandardMaterial
            color={COLORS.steel}
            metalness={0.7}
            roughness={0.4}
          />
        </mesh>
      ))}

      {/* Center glow (fusion point) */}
      <pointLight
        ref={glowRef}
        position={[0, 0, 0]}
        intensity={0}
        color={COLORS.fusionWhite}
        distance={50}
        decay={2}
      />

      {/* Tiny target pellet at center */}
      <mesh>
        <sphereGeometry args={[0.08, 16, 16]} />
        <meshStandardMaterial
          color="#cccccc"
          emissive={COLORS.plasma}
          emissiveIntensity={targetGlow * 3}
        />
      </mesh>
    </group>
  );
});
