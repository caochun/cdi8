import { memo } from "react";
import { BUILDING_WIDTH, BUILDING_DEPTH, BUILDING_HEIGHT, FLOOR_Y, COLORS } from "@/lib/constants";

const WALL_THICKNESS = 0.5;
const WALL_OPACITY = 0.12;

function Wall({
  position,
  size,
}: {
  position: [number, number, number];
  size: [number, number, number];
}) {
  return (
    <mesh position={position} castShadow>
      <boxGeometry args={size} />
      <meshStandardMaterial
        color={COLORS.wall}
        metalness={0.1}
        roughness={0.9}
        transparent
        opacity={WALL_OPACITY}
      />
    </mesh>
  );
}

export const Building = memo(function Building() {
  const hw = BUILDING_WIDTH / 2;
  const hd = BUILDING_DEPTH / 2;
  const wy = FLOOR_Y + BUILDING_HEIGHT / 2;

  return (
    <group>
      {/* North wall */}
      <Wall
        position={[0, wy, -hd]}
        size={[BUILDING_WIDTH, BUILDING_HEIGHT, WALL_THICKNESS]}
      />
      {/* South wall */}
      <Wall
        position={[0, wy, hd]}
        size={[BUILDING_WIDTH, BUILDING_HEIGHT, WALL_THICKNESS]}
      />
      {/* West wall */}
      <Wall
        position={[-hw, wy, 0]}
        size={[WALL_THICKNESS, BUILDING_HEIGHT, BUILDING_DEPTH]}
      />
      {/* East wall */}
      <Wall
        position={[hw, wy, 0]}
        size={[WALL_THICKNESS, BUILDING_HEIGHT, BUILDING_DEPTH]}
      />

      {/* Roof - very transparent */}
      <mesh position={[0, FLOOR_Y + BUILDING_HEIGHT, 0]}>
        <boxGeometry args={[BUILDING_WIDTH, 0.3, BUILDING_DEPTH]} />
        <meshStandardMaterial
          color="#2a2a35"
          metalness={0.3}
          roughness={0.7}
          transparent
          opacity={0.06}
        />
      </mesh>

      {/* Structural columns */}
      {[
        [-hw + 3, -hd + 3],
        [hw - 3, -hd + 3],
        [-hw + 3, hd - 3],
        [hw - 3, hd - 3],
        [-hw + 3, 0],
        [hw - 3, 0],
        [0, -hd + 3],
        [0, hd - 3],
      ].map(([x, z], i) => (
        <mesh key={i} position={[x, wy, z]} castShadow>
          <cylinderGeometry args={[0.4, 0.4, BUILDING_HEIGHT, 8]} />
          <meshStandardMaterial color="#444450" metalness={0.7} roughness={0.35} />
        </mesh>
      ))}

      {/* Grid lines on floor for scale */}
      {Array.from({ length: 13 }).map((_, i) => {
        const x = -hw + 2 + (i / 12) * (BUILDING_WIDTH - 4);
        return (
          <mesh key={`gx-${i}`} position={[x, FLOOR_Y + 0.005, 0]} rotation={[-Math.PI / 2, 0, 0]}>
            <planeGeometry args={[0.02, BUILDING_DEPTH - 4]} />
            <meshBasicMaterial color="#333340" transparent opacity={0.3} />
          </mesh>
        );
      })}
      {Array.from({ length: 9 }).map((_, i) => {
        const z = -hd + 2 + (i / 8) * (BUILDING_DEPTH - 4);
        return (
          <mesh key={`gz-${i}`} position={[0, FLOOR_Y + 0.005, z]} rotation={[-Math.PI / 2, 0, 0]}>
            <planeGeometry args={[BUILDING_WIDTH - 4, 0.02]} />
            <meshBasicMaterial color="#333340" transparent opacity={0.3} />
          </mesh>
        );
      })}
    </group>
  );
});
