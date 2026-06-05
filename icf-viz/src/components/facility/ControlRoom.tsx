import { memo } from "react";
import { useExperimentStore } from "@/state/experimentStore";
import { Phase } from "@/types";
import { COLORS } from "@/lib/constants";

export const ControlRoom = memo(function ControlRoom() {
  const phase = useExperimentStore((s) => s.currentPhase);
  const isActive = phase !== Phase.IDLE;
  const screenGlow = isActive ? 0.6 : 0.1;

  return (
    <group position={[-45, 0, -25]}>
      {/* Room enclosure */}
      <mesh position={[0, 2.5, 0]}>
        <boxGeometry args={[18, 5, 12]} />
        <meshStandardMaterial
          color="#222230"
          metalness={0.2}
          roughness={0.8}
          transparent
          opacity={0.25}
        />
      </mesh>

      {/* Floor */}
      <mesh position={[0, 0.02, 0]} rotation={[-Math.PI / 2, 0, 0]} receiveShadow>
        <planeGeometry args={[18, 12]} />
        <meshStandardMaterial color="#1e1e28" metalness={0.15} roughness={0.85} />
      </mesh>

      {/* Console desks - 3 rows */}
      {[-3, 0, 3].map((z, row) => (
        <group key={row} position={[0, 0, z]}>
          {/* Desk surface */}
          <mesh position={[0, 0.75, 0]} castShadow>
            <boxGeometry args={[14, 0.08, 1.2]} />
            <meshStandardMaterial color="#2a2a3a" metalness={0.4} roughness={0.6} />
          </mesh>
          {/* Monitor screens - 5 per desk */}
          {[-5.5, -2.75, 0, 2.75, 5.5].map((x, i) => (
            <group key={i}>
              <mesh position={[x, 1.4, -0.3]}>
                <boxGeometry args={[2.2, 1.3, 0.05]} />
                <meshStandardMaterial
                  color="#000008"
                  emissive={COLORS.screenGlow}
                  emissiveIntensity={screenGlow}
                />
              </mesh>
              {/* Monitor stand */}
              <mesh position={[x, 1.0, -0.3]}>
                <cylinderGeometry args={[0.05, 0.08, 0.5, 6]} />
                <meshStandardMaterial color="#333340" metalness={0.6} roughness={0.4} />
              </mesh>
            </group>
          ))}
          {/* Chairs */}
          {[-4, 0, 4].map((x, i) => (
            <group key={`chair-${i}`} position={[x, 0, 1]}>
              <mesh position={[0, 0.45, 0]}>
                <boxGeometry args={[0.5, 0.06, 0.5]} />
                <meshStandardMaterial color="#333344" metalness={0.3} roughness={0.6} />
              </mesh>
              <mesh position={[0, 0.25, 0]}>
                <cylinderGeometry args={[0.04, 0.04, 0.45, 6]} />
                <meshStandardMaterial color="#444455" metalness={0.7} roughness={0.3} />
              </mesh>
            </group>
          ))}
        </group>
      ))}

      {/* Large display wall */}
      <mesh position={[0, 3, -5.8]}>
        <boxGeometry args={[16, 3, 0.1]} />
        <meshStandardMaterial
          color="#000011"
          emissive={isActive ? "#2244aa" : "#111122"}
          emissiveIntensity={isActive ? 0.4 : 0.05}
        />
      </mesh>

      {/* Room lighting */}
      <pointLight
        position={[0, 4.5, 0]}
        intensity={isActive ? 3 : 0.5}
        color="#aabbdd"
        distance={20}
        decay={2}
      />
    </group>
  );
});
