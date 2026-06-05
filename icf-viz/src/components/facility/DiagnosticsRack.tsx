import { memo } from "react";
import { useExperimentStore } from "@/state/experimentStore";
import { SubsystemId, SubsystemStatus } from "@/types";
import { COLORS } from "@/lib/constants";

export const DiagnosticsRack = memo(function DiagnosticsRack() {
  const diagStatus = useExperimentStore(
    (s) => s.subsystems[SubsystemId.DIAGNOSTICS]
  );
  const isActive =
    diagStatus === SubsystemStatus.ACTIVE ||
    diagStatus === SubsystemStatus.COLLECTING;

  return (
    <group position={[20, 0, -28]}>
      {/* Rack cabinets */}
      {Array.from({ length: 5 }).map((_, i) => (
        <group key={i} position={[i * 2.2 - 4.4, 0, 0]}>
          <mesh position={[0, 2, 0]} castShadow>
            <boxGeometry args={[1.8, 4, 1]} />
            <meshStandardMaterial color="#1a1a24" metalness={0.3} roughness={0.7} />
          </mesh>
          {/* Front panel indicators */}
          {Array.from({ length: 6 }).map((_, j) => (
            <mesh key={j} position={[-0.5 + (j % 3) * 0.5, 1 + Math.floor(j / 3) * 1.5, 0.51]}>
              <circleGeometry args={[0.06, 8]} />
              <meshStandardMaterial
                color={isActive ? "#22cc44" : "#333344"}
                emissive={isActive ? "#22cc44" : "#000000"}
                emissiveIntensity={isActive ? 1.5 : 0}
              />
            </mesh>
          ))}
          {/* Screen */}
          <mesh position={[0, 3, 0.51]}>
            <planeGeometry args={[1.4, 0.8]} />
            <meshStandardMaterial
              color="#000011"
              emissive={isActive ? COLORS.screenGlow : "#111122"}
              emissiveIntensity={isActive ? 0.4 : 0.05}
            />
          </mesh>
        </group>
      ))}

      {/* Platform */}
      <mesh position={[0, -0.05, 0]} receiveShadow>
        <boxGeometry args={[13, 0.1, 2]} />
        <meshStandardMaterial color="#222230" metalness={0.2} roughness={0.8} />
      </mesh>
    </group>
  );
});
