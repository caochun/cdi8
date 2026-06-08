import { useRef, memo, useMemo } from "react";
import * as THREE from "three";
import { useFrame } from "@react-three/fiber";
import { useExperimentStore } from "@/state/experimentStore";
import { COLORS } from "@/lib/constants";

const BANK_ROWS = 4;
const BANK_COLS = 8;
const CAP_SIZE: [number, number, number] = [1.2, 2, 0.8];
const GAP = 0.3;

function lerpColor(a: THREE.Color, b: THREE.Color, t: number): THREE.Color {
  return new THREE.Color().lerpColors(a, b, t);
}

const COLOR_EMPTY = new THREE.Color(COLORS.capacitorEmpty);
const COLOR_CHARGING = new THREE.Color("#ddaa00");
const COLOR_FULL = new THREE.Color(COLORS.capacitorFull);

export const PumpSystem = memo(function PumpSystem() {
  const groupRef = useRef<THREE.Group>(null);
  const capsRef = useRef<THREE.InstancedMesh>(null);
  const chargeLevel = useExperimentStore((s) => s.visuals.capacitorChargeLevel);

  const count = BANK_ROWS * BANK_COLS;

  const capGeometry = useMemo(
    () => new THREE.BoxGeometry(...CAP_SIZE),
    []
  );

  // Set initial positions
  useMemo(() => {
    if (!capsRef.current) return;
    const dummy = new THREE.Object3D();
    let idx = 0;
    for (let row = 0; row < BANK_ROWS; row++) {
      for (let col = 0; col < BANK_COLS; col++) {
        dummy.position.set(
          col * (CAP_SIZE[0] + GAP) - ((BANK_COLS - 1) * (CAP_SIZE[0] + GAP)) / 2,
          CAP_SIZE[1] / 2,
          row * (CAP_SIZE[2] + GAP) - ((BANK_ROWS - 1) * (CAP_SIZE[2] + GAP)) / 2
        );
        dummy.updateMatrix();
        capsRef.current.setMatrixAt(idx, dummy.matrix);
        idx++;
      }
    }
    capsRef.current.instanceMatrix.needsUpdate = true;
  }, []);

  useFrame(() => {
    if (!capsRef.current) return;
    const color =
      chargeLevel < 0.5
        ? lerpColor(COLOR_EMPTY, COLOR_CHARGING, chargeLevel * 2)
        : lerpColor(COLOR_CHARGING, COLOR_FULL, (chargeLevel - 0.5) * 2);

    for (let i = 0; i < count; i++) {
      capsRef.current.setColorAt(i, color);
    }
    if (capsRef.current.instanceColor) {
      capsRef.current.instanceColor.needsUpdate = true;
    }
  });

  return (
    <group ref={groupRef} position={[-22, 0, 20]}>
      {/* Capacitor bank label floor marking */}
      <mesh position={[0, 0.01, -3]} rotation={[-Math.PI / 2, 0, 0]}>
        <planeGeometry args={[14, 0.6]} />
        <meshBasicMaterial color="#ddaa00" transparent opacity={0.15} />
      </mesh>

      {/* Capacitor units */}
      <instancedMesh
        ref={capsRef}
        args={[capGeometry, undefined, count]}
        castShadow
      >
        <meshStandardMaterial
          color={COLORS.capacitorEmpty}
          metalness={0.6}
          roughness={0.4}
        />
      </instancedMesh>

      {/* Bus bars connecting capacitors */}
      {Array.from({ length: BANK_ROWS }).map((_, row) => (
        <mesh
          key={row}
          position={[
            0,
            CAP_SIZE[1] + 0.2,
            row * (CAP_SIZE[2] + GAP) -
              ((BANK_ROWS - 1) * (CAP_SIZE[2] + GAP)) / 2,
          ]}
        >
          <boxGeometry
            args={[(BANK_COLS - 1) * (CAP_SIZE[0] + GAP) + CAP_SIZE[0], 0.15, 0.1]}
          />
          <meshStandardMaterial color={COLORS.copper} metalness={0.85} roughness={0.2} />
        </mesh>
      ))}

      {/* Flash lamp housings (two rows flanking capacitor banks) */}
      {[-6, 6].map((offsetX, gi) =>
        Array.from({ length: 8 }).map((_, i) => (
          <mesh
            key={`lamp-${gi}-${i}`}
            position={[
              offsetX,
              1.5,
              i * 1.2 - 4.2,
            ]}
          >
            <cylinderGeometry args={[0.2, 0.2, 2.5, 8]} />
            <meshStandardMaterial
              color="#aaaaaa"
              metalness={0.5}
              roughness={0.4}
              emissive={COLORS.xenonFlash}
              emissiveIntensity={chargeLevel > 0.9 ? 0.5 : 0}
            />
          </mesh>
        ))
      )}

      {/* Platform base */}
      <mesh position={[0, -0.1, 0]} receiveShadow>
        <boxGeometry args={[16, 0.2, 8]} />
        <meshStandardMaterial color="#222230" metalness={0.2} roughness={0.8} />
      </mesh>
    </group>
  );
});
