import { useRef, useMemo, memo } from "react";
import * as THREE from "three";
import { useFrame } from "@react-three/fiber";
import { useExperimentStore } from "@/state/experimentStore";
import { BEAM_COUNT, BEAM_LENGTH, TARGET_CHAMBER_RADIUS, COLORS } from "@/lib/constants";
import { sphericalBeamPositions } from "@/lib/geometry";

const TUBE_RADIUS = 0.06;
const TUBE_SEGMENTS = 8;
const GLOW_RADIUS = 0.15;

export const BeamLineArray = memo(function BeamLineArray() {
  const meshRef = useRef<THREE.InstancedMesh>(null);
  const glowMeshRef = useRef<THREE.InstancedMesh>(null);
  const materialRef = useRef<THREE.MeshStandardMaterial>(null);
  const glowMaterialRef = useRef<THREE.MeshStandardMaterial>(null);

  const beamsVisible = useExperimentStore((s) => s.visuals.laserBeamsVisible);
  const beamsIntensity = useExperimentStore((s) => s.visuals.laserBeamsIntensity);

  const beamData = useMemo(() => {
    return sphericalBeamPositions(BEAM_COUNT, BEAM_LENGTH);
  }, []);

  const tubeGeometry = useMemo(() => {
    return new THREE.CylinderGeometry(TUBE_RADIUS, TUBE_RADIUS, 1, TUBE_SEGMENTS);
  }, []);

  const glowGeometry = useMemo(() => {
    return new THREE.CylinderGeometry(GLOW_RADIUS, GLOW_RADIUS, 1, TUBE_SEGMENTS);
  }, []);

  useMemo(() => {
    const dummy = new THREE.Object3D();
    const matrices: THREE.Matrix4[] = [];

    for (let i = 0; i < BEAM_COUNT; i++) {
      const { position } = beamData[i];
      const innerPos = position
        .clone()
        .normalize()
        .multiplyScalar(TARGET_CHAMBER_RADIUS + 0.5);
      const midpoint = new THREE.Vector3()
        .addVectors(position, innerPos)
        .multiplyScalar(0.5);
      const length = position.distanceTo(innerPos);
      const dir = new THREE.Vector3().subVectors(innerPos, position).normalize();

      dummy.position.copy(midpoint);
      dummy.scale.set(1, length, 1);
      dummy.quaternion.setFromUnitVectors(new THREE.Vector3(0, 1, 0), dir);
      dummy.updateMatrix();
      matrices.push(dummy.matrix.clone());
    }
    return matrices;
  }, [beamData]);

  useMemo(() => {
    if (!meshRef.current || !glowMeshRef.current) return;
    const dummy = new THREE.Object3D();

    for (let i = 0; i < BEAM_COUNT; i++) {
      const { position } = beamData[i];
      const innerPos = position
        .clone()
        .normalize()
        .multiplyScalar(TARGET_CHAMBER_RADIUS + 0.5);
      const midpoint = new THREE.Vector3()
        .addVectors(position, innerPos)
        .multiplyScalar(0.5);
      const length = position.distanceTo(innerPos);
      const dir = new THREE.Vector3().subVectors(innerPos, position).normalize();

      dummy.position.copy(midpoint);
      dummy.scale.set(1, length, 1);
      dummy.quaternion.setFromUnitVectors(new THREE.Vector3(0, 1, 0), dir);
      dummy.updateMatrix();

      meshRef.current.setMatrixAt(i, dummy.matrix);
      glowMeshRef.current.setMatrixAt(i, dummy.matrix);
    }
    meshRef.current.instanceMatrix.needsUpdate = true;
    glowMeshRef.current.instanceMatrix.needsUpdate = true;
  }, [beamData]);

  useFrame(() => {
    if (materialRef.current) {
      const targetOpacity = beamsVisible ? 0.15 + beamsIntensity * 0.6 : 0.08;
      materialRef.current.opacity = THREE.MathUtils.lerp(
        materialRef.current.opacity,
        targetOpacity,
        0.05
      );
    }
    if (glowMaterialRef.current) {
      const targetIntensity = beamsVisible ? beamsIntensity * 4 : 0;
      glowMaterialRef.current.emissiveIntensity = THREE.MathUtils.lerp(
        glowMaterialRef.current.emissiveIntensity,
        targetIntensity,
        0.05
      );
      glowMaterialRef.current.opacity = beamsVisible
        ? Math.min(0.6, beamsIntensity * 0.8)
        : 0;
    }
  });

  return (
    <group>
      {/* Structural beam tubes */}
      <instancedMesh
        ref={meshRef}
        args={[tubeGeometry, undefined, BEAM_COUNT]}
        frustumCulled={false}
      >
        <meshStandardMaterial
          ref={materialRef}
          color="#444455"
          metalness={0.6}
          roughness={0.4}
          transparent
          opacity={0.08}
        />
      </instancedMesh>

      {/* Laser glow tubes */}
      <instancedMesh
        ref={glowMeshRef}
        args={[glowGeometry, undefined, BEAM_COUNT]}
        frustumCulled={false}
      >
        <meshStandardMaterial
          ref={glowMaterialRef}
          color={COLORS.laserUV}
          emissive={COLORS.laserUV}
          emissiveIntensity={0}
          transparent
          opacity={0}
          depthWrite={false}
        />
      </instancedMesh>

      {/* Beam line housings at outer ends */}
      {beamData.map(({ position }, i) => {
        const dir = position.clone().normalize();
        const quat = new THREE.Quaternion().setFromUnitVectors(
          new THREE.Vector3(0, 1, 0),
          dir
        );
        return (
          <mesh key={i} position={position} quaternion={quat}>
            <cylinderGeometry args={[0.35, 0.45, 1.5, 8]} />
            <meshStandardMaterial color="#333344" metalness={0.7} roughness={0.35} />
          </mesh>
        );
      })}
    </group>
  );
});
