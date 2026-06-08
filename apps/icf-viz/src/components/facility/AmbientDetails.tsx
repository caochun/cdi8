import { useRef, memo } from "react";
import * as THREE from "three";
import { useFrame } from "@react-three/fiber";
import { useExperimentStore } from "@/state/experimentStore";
import { Phase } from "@/types";

function FlickerScreen({
  position,
  size = [1.6, 0.9],
  baseColor = "#000811",
  glowColor = "#4488cc",
}: {
  position: [number, number, number];
  size?: [number, number];
  baseColor?: string;
  glowColor?: string;
}) {
  const matRef = useRef<THREE.MeshStandardMaterial>(null);
  const phase = useExperimentStore((s) => s.currentPhase);
  const active = phase !== Phase.IDLE;

  useFrame(({ clock }) => {
    if (!matRef.current || !active) return;
    const flicker = 0.3 + Math.sin(clock.elapsedTime * 3.7 + position[0]) * 0.08
      + Math.sin(clock.elapsedTime * 7.1 + position[2]) * 0.05;
    matRef.current.emissiveIntensity = flicker;
  });

  return (
    <mesh position={position}>
      <planeGeometry args={size} />
      <meshStandardMaterial
        ref={matRef}
        color={baseColor}
        emissive={active ? glowColor : "#111122"}
        emissiveIntensity={active ? 0.3 : 0.02}
      />
    </mesh>
  );
}

function PulsingIndicator({
  position,
  color,
  speed = 2,
}: {
  position: [number, number, number];
  color: string;
  speed?: number;
}) {
  const matRef = useRef<THREE.MeshStandardMaterial>(null);
  const phase = useExperimentStore((s) => s.currentPhase);
  const active = phase !== Phase.IDLE && phase !== Phase.COMPLETE;

  useFrame(({ clock }) => {
    if (!matRef.current) return;
    if (active) {
      const pulse = (Math.sin(clock.elapsedTime * speed + position[0] * 10) + 1) / 2;
      matRef.current.emissiveIntensity = 0.5 + pulse * 1.5;
    } else {
      matRef.current.emissiveIntensity = 0;
    }
  });

  return (
    <mesh position={position}>
      <sphereGeometry args={[0.06, 8, 8]} />
      <meshStandardMaterial
        ref={matRef}
        color={color}
        emissive={color}
        emissiveIntensity={0}
      />
    </mesh>
  );
}

export const AmbientDetails = memo(function AmbientDetails() {
  return (
    <group>
      {/* Extra wall-mounted screens around facility */}
      <FlickerScreen position={[-20, 4, -39.5]} />
      <FlickerScreen position={[-15, 4, -39.5]} />
      <FlickerScreen position={[20, 4, -39.5]} glowColor="#44cc88" />
      <FlickerScreen position={[25, 4, -39.5]} glowColor="#44cc88" />

      {/* Status LEDs along beam line corridor */}
      {Array.from({ length: 16 }).map((_, i) => {
        const angle = (i / 16) * Math.PI * 2;
        const r = 15;
        return (
          <PulsingIndicator
            key={`led-${i}`}
            position={[Math.cos(angle) * r, 1, Math.sin(angle) * r]}
            color={i % 3 === 0 ? "#22cc44" : "#4488ff"}
            speed={1.5 + (i % 4) * 0.5}
          />
        );
      })}

      {/* Floor guide lines (hazard) around target chamber */}
      <mesh position={[0, 0.008, 0]} rotation={[-Math.PI / 2, 0, 0]}>
        <ringGeometry args={[7, 7.15, 64]} />
        <meshBasicMaterial color="#ddaa00" transparent opacity={0.2} />
      </mesh>
      <mesh position={[0, 0.008, 0]} rotation={[-Math.PI / 2, 0, 0]}>
        <ringGeometry args={[9, 9.1, 64]} />
        <meshBasicMaterial color="#dd4444" transparent opacity={0.15} />
      </mesh>

      {/* Cable trays overhead */}
      {[-30, -15, 0, 15, 30].map((x, i) => (
        <mesh key={`tray-${i}`} position={[x, 22, 0]}>
          <boxGeometry args={[1, 0.15, 70]} />
          <meshStandardMaterial color="#333340" metalness={0.5} roughness={0.5} />
        </mesh>
      ))}

      {/* Ventilation ducts */}
      {[-25, 25].map((z, i) => (
        <mesh key={`vent-${i}`} position={[0, 23, z]}>
          <boxGeometry args={[90, 1.2, 1.5]} />
          <meshStandardMaterial color="#2a2a35" metalness={0.4} roughness={0.6} />
        </mesh>
      ))}
    </group>
  );
});
