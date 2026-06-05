import { useRef, memo, useMemo } from "react";
import * as THREE from "three";
import { useFrame } from "@react-three/fiber";
import { useExperimentStore } from "@/state/experimentStore";

const PIPE_RADIUS = 0.15;
const PIPE_COLOR = "#4477aa";

function CoolingPipe({
  points,
  radius = PIPE_RADIUS,
}: {
  points: THREE.Vector3[];
  radius?: number;
}) {
  const geometry = useMemo(() => {
    const curve = new THREE.CatmullRomCurve3(points);
    return new THREE.TubeGeometry(curve, 32, radius, 8, false);
  }, [points, radius]);

  return (
    <mesh geometry={geometry}>
      <meshStandardMaterial
        color={PIPE_COLOR}
        metalness={0.7}
        roughness={0.3}
      />
    </mesh>
  );
}

export const CoolingSystem = memo(function CoolingSystem() {
  const flowing = useExperimentStore((s) => s.visuals.coolingFlowing);

  const pipes = useMemo(() => {
    const routes: THREE.Vector3[][] = [];

    // Main ring around target chamber
    const ringPoints: THREE.Vector3[] = [];
    for (let i = 0; i <= 32; i++) {
      const angle = (i / 32) * Math.PI * 2;
      ringPoints.push(
        new THREE.Vector3(Math.cos(angle) * 10, 0.5, Math.sin(angle) * 10)
      );
    }
    routes.push(ringPoints);

    // Supply lines from chiller area to ring
    routes.push([
      new THREE.Vector3(35, 0.5, -30),
      new THREE.Vector3(25, 0.5, -20),
      new THREE.Vector3(15, 0.5, -12),
      new THREE.Vector3(10, 0.5, -5),
    ]);
    routes.push([
      new THREE.Vector3(35, 0.5, 30),
      new THREE.Vector3(25, 0.5, 20),
      new THREE.Vector3(15, 0.5, 12),
      new THREE.Vector3(10, 0.5, 5),
    ]);

    // Branch lines to amplifier chains
    for (let i = 0; i < 6; i++) {
      const angle = (i / 6) * Math.PI * 2;
      const r1 = 10;
      const r2 = 25;
      routes.push([
        new THREE.Vector3(Math.cos(angle) * r1, 0.5, Math.sin(angle) * r1),
        new THREE.Vector3(Math.cos(angle) * (r1 + 5), 1, Math.sin(angle) * (r1 + 5)),
        new THREE.Vector3(Math.cos(angle) * r2, 0.5, Math.sin(angle) * r2),
      ]);
    }

    return routes;
  }, []);

  return (
    <group>
      {pipes.map((points, i) => (
        <CoolingPipe key={i} points={points} />
      ))}

      {/* Chiller unit */}
      <group position={[38, 0, -30]}>
        <mesh position={[0, 1.5, 0]} castShadow>
          <boxGeometry args={[5, 3, 3]} />
          <meshStandardMaterial color="#336688" metalness={0.6} roughness={0.4} />
        </mesh>
        {/* Fan grille */}
        <mesh position={[2.51, 1.5, 0]}>
          <circleGeometry args={[1, 16]} />
          <meshStandardMaterial color="#222233" metalness={0.5} roughness={0.5} />
        </mesh>
      </group>

      {/* Second chiller */}
      <group position={[38, 0, 30]}>
        <mesh position={[0, 1.5, 0]} castShadow>
          <boxGeometry args={[5, 3, 3]} />
          <meshStandardMaterial color="#336688" metalness={0.6} roughness={0.4} />
        </mesh>
      </group>
    </group>
  );
});
