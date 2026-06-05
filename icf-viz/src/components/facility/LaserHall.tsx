import { memo, useMemo } from "react";
import * as THREE from "three";
import { COLORS } from "@/lib/constants";
import { useExperimentStore } from "@/state/experimentStore";
import { SubsystemId, SubsystemStatus } from "@/types";

function AmplifierSlab({
  position,
  rotation,
}: {
  position: [number, number, number];
  rotation?: [number, number, number];
}) {
  return (
    <group position={position} rotation={rotation}>
      {/* Glass slab (Nd:glass) */}
      <mesh castShadow>
        <boxGeometry args={[2.5, 3, 0.15]} />
        <meshStandardMaterial
          color={COLORS.glass}
          metalness={0.05}
          roughness={0.1}
          transparent
          opacity={0.45}
        />
      </mesh>
      {/* Frame */}
      <mesh>
        <boxGeometry args={[2.8, 3.3, 0.08]} />
        <meshStandardMaterial color="#555566" metalness={0.7} roughness={0.3} />
      </mesh>
    </group>
  );
}

function SpatialFilter({
  position,
  length,
  rotation,
}: {
  position: [number, number, number];
  length: number;
  rotation?: [number, number, number];
}) {
  return (
    <group position={position} rotation={rotation}>
      {/* Tube */}
      <mesh>
        <cylinderGeometry args={[0.5, 0.5, length, 12]} />
        <meshStandardMaterial color={COLORS.steel} metalness={0.8} roughness={0.25} />
      </mesh>
      {/* End caps (lenses) */}
      {[-length / 2, length / 2].map((y, i) => (
        <mesh key={i} position={[0, y, 0]}>
          <sphereGeometry args={[0.55, 12, 8, 0, Math.PI * 2, 0, Math.PI / 2]} />
          <meshStandardMaterial
            color={COLORS.glass}
            metalness={0.1}
            roughness={0.1}
            transparent
            opacity={0.3}
          />
        </mesh>
      ))}
    </group>
  );
}

function KDPCrystal({ position }: { position: [number, number, number] }) {
  const freqStatus = useExperimentStore(
    (s) => s.subsystems[SubsystemId.FREQUENCY_CONV]
  );
  const isActive =
    freqStatus === SubsystemStatus.ACTIVE || freqStatus === SubsystemStatus.FIRING;

  return (
    <group position={position}>
      <mesh castShadow>
        <boxGeometry args={[1.5, 1.5, 0.8]} />
        <meshStandardMaterial
          color={isActive ? "#aabbff" : "#888899"}
          metalness={0.15}
          roughness={0.15}
          transparent
          opacity={0.5}
          emissive={isActive ? COLORS.laserUV : "#000000"}
          emissiveIntensity={isActive ? 0.6 : 0}
        />
      </mesh>
      {/* Housing */}
      <mesh>
        <boxGeometry args={[1.8, 1.8, 0.4]} />
        <meshStandardMaterial color="#444455" metalness={0.6} roughness={0.4} />
      </mesh>
    </group>
  );
}

export const LaserHall = memo(function LaserHall() {
  const chains = useMemo(() => {
    const result: { x: number; z: number; angle: number }[] = [];
    const count = 10;
    for (let i = 0; i < count; i++) {
      const angle = (i / count) * Math.PI * 2;
      const r = 22;
      result.push({
        x: Math.cos(angle) * r,
        z: Math.sin(angle) * r,
        angle: -angle + Math.PI / 2,
      });
    }
    return result;
  }, []);

  return (
    <group>
      {chains.map(({ x, z, angle }, i) => (
        <group key={i} position={[x, 0, z]} rotation={[0, angle, 0]}>
          {/* Amplifier slabs along chain */}
          <AmplifierSlab position={[0, 3, -3]} />
          <AmplifierSlab position={[0, 3, 0]} />
          <AmplifierSlab position={[0, 3, 3]} />

          {/* Spatial filter between slabs */}
          <SpatialFilter position={[0, 3, -1.5]} length={2} />
          <SpatialFilter position={[0, 3, 1.5]} length={2} />

          {/* KDP crystal at end of chain */}
          <KDPCrystal position={[0, 3, 5.5]} />

          {/* Support structure */}
          <mesh position={[0, 0, 0]} receiveShadow>
            <boxGeometry args={[4, 0.15, 12]} />
            <meshStandardMaterial color="#1e1e28" metalness={0.2} roughness={0.8} />
          </mesh>

          {/* Optical table */}
          <mesh position={[0, 1.5, 0]}>
            <boxGeometry args={[3.5, 0.2, 11]} />
            <meshStandardMaterial color="#333340" metalness={0.6} roughness={0.4} />
          </mesh>
          {/* Table legs */}
          {[
            [-1.5, -4],
            [1.5, -4],
            [-1.5, 4],
            [1.5, 4],
          ].map(([lx, lz], li) => (
            <mesh key={li} position={[lx, 0.75, lz]}>
              <cylinderGeometry args={[0.1, 0.12, 1.5, 6]} />
              <meshStandardMaterial color="#444455" metalness={0.6} roughness={0.4} />
            </mesh>
          ))}
        </group>
      ))}
    </group>
  );
});
