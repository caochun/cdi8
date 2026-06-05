import { useRef, memo } from "react";
import * as THREE from "three";
import { useFrame } from "@react-three/fiber";
import { useExperimentStore } from "@/state/experimentStore";
import { COLORS, BUILDING_DEPTH } from "@/lib/constants";

const DOOR_WIDTH = 8;
const DOOR_HEIGHT = 6;
const DOOR_THICKNESS = 1.5;

function ShieldingDoor({ side }: { side: "left" | "right" }) {
  const meshRef = useRef<THREE.Mesh>(null);
  const doorsOpen = useExperimentStore((s) => s.visuals.shieldingDoorsOpen);
  const openAmountRef = useRef(1);
  const sign = side === "left" ? -1 : 1;

  useFrame(() => {
    const target = doorsOpen ? 1 : 0;
    openAmountRef.current = THREE.MathUtils.lerp(openAmountRef.current, target, 0.03);
    if (meshRef.current) {
      meshRef.current.position.x = sign * (DOOR_WIDTH / 2 + openAmountRef.current * DOOR_WIDTH * 0.6);
    }
  });

  return (
    <mesh ref={meshRef} position={[sign * DOOR_WIDTH / 2, DOOR_HEIGHT / 2, 0]} castShadow>
      <boxGeometry args={[DOOR_WIDTH, DOOR_HEIGHT, DOOR_THICKNESS]} />
      <meshStandardMaterial
        color={COLORS.concrete}
        metalness={0.15}
        roughness={0.9}
      />
    </mesh>
  );
}

function WarningBeacon({ position }: { position: [number, number, number] }) {
  const lightRef = useRef<THREE.PointLight>(null);
  const meshRef = useRef<THREE.Mesh>(null);
  const active = useExperimentStore((s) => s.visuals.warningLightsActive);
  const color = useExperimentStore((s) => s.visuals.warningLightsColor);

  const lightColor = color === "red" ? COLORS.warningRed : COLORS.warningAmber;

  useFrame(({ clock }) => {
    if (!active) {
      if (lightRef.current) lightRef.current.intensity = 0;
      return;
    }
    const pulse = (Math.sin(clock.elapsedTime * 6) + 1) / 2;
    if (lightRef.current) {
      lightRef.current.intensity = pulse * 8;
      lightRef.current.color.set(lightColor);
    }
    if (meshRef.current) {
      const mat = meshRef.current.material as THREE.MeshStandardMaterial;
      mat.emissiveIntensity = pulse * 2;
      mat.emissive.set(lightColor);
    }
  });

  return (
    <group position={position}>
      {/* Beacon housing */}
      <mesh position={[0, -0.3, 0]}>
        <cylinderGeometry args={[0.15, 0.15, 0.6, 8]} />
        <meshStandardMaterial color="#444444" metalness={0.6} roughness={0.4} />
      </mesh>
      {/* Beacon light dome */}
      <mesh ref={meshRef}>
        <sphereGeometry args={[0.25, 12, 12]} />
        <meshStandardMaterial
          color={lightColor}
          emissive={lightColor}
          emissiveIntensity={0}
          transparent
          opacity={0.9}
        />
      </mesh>
      <pointLight
        ref={lightRef}
        color={lightColor}
        intensity={0}
        distance={15}
        decay={2}
      />
    </group>
  );
}

export const SafetyInterlocks = memo(function SafetyInterlocks() {
  const hd = BUILDING_DEPTH / 2;

  return (
    <group>
      {/* Main shielding door (south entrance) */}
      <group position={[0, 0, hd - 2]}>
        <ShieldingDoor side="left" />
        <ShieldingDoor side="right" />
        {/* Door frame */}
        <mesh position={[0, DOOR_HEIGHT + 0.3, 0]}>
          <boxGeometry args={[DOOR_WIDTH * 2.5, 0.6, DOOR_THICKNESS + 0.5]} />
          <meshStandardMaterial color="#555560" metalness={0.3} roughness={0.7} />
        </mesh>
        {/* Hazard stripes */}
        <mesh position={[0, DOOR_HEIGHT + 0.8, DOOR_THICKNESS / 2 + 0.01]} rotation={[0, 0, 0]}>
          <planeGeometry args={[DOOR_WIDTH * 2.5, 0.3]} />
          <meshBasicMaterial color="#ddaa00" transparent opacity={0.5} />
        </mesh>
      </group>

      {/* Warning beacons at key locations */}
      <WarningBeacon position={[-12, 7, hd - 2]} />
      <WarningBeacon position={[12, 7, hd - 2]} />
      <WarningBeacon position={[-45, 5.5, -19]} />
      <WarningBeacon position={[30, 7, 0]} />
      <WarningBeacon position={[-22, 5, 25]} />
      <WarningBeacon position={[0, 12, 0]} />
    </group>
  );
});
