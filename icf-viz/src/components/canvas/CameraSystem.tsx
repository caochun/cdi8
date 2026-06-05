import { useRef, useEffect } from "react";
import { useFrame, useThree } from "@react-three/fiber";
import { OrbitControls } from "@react-three/drei";
import * as THREE from "three";
import { useExperimentStore } from "@/state/experimentStore";
import { useCameraOverride } from "@/state/cameraStore";
import { PHASES } from "@/state/phases";
import { CAMERA_PRESETS } from "@/state/cameraPresets";
import { Phase } from "@/types";

const LERP_FACTOR = 0.04;
const tmpTarget = new THREE.Vector3();

export function CameraSystem() {
  const controlsRef = useRef<any>(null);
  const { camera } = useThree();
  const targetPosRef = useRef(new THREE.Vector3(0, 70, 100));
  const targetLookRef = useRef(new THREE.Vector3(0, 0, 0));
  const prevPhaseRef = useRef<Phase | null>(null);
  const autoRotateRef = useRef(false);

  const currentPhase = useExperimentStore((s) => s.currentPhase);
  const overridePreset = useCameraOverride((s) => s.overridePreset);

  // React to manual override
  useEffect(() => {
    if (!overridePreset) return;
    const preset = CAMERA_PRESETS[overridePreset];
    if (!preset) return;
    targetPosRef.current.set(...preset.position);
    targetLookRef.current.set(...preset.target);
    autoRotateRef.current = overridePreset === "cinematic";
  }, [overridePreset]);

  // React to phase change (only when no override active)
  useEffect(() => {
    if (overridePreset) return;
    if (prevPhaseRef.current === currentPhase) return;
    prevPhaseRef.current = currentPhase;

    const config = PHASES[currentPhase];
    const preset = CAMERA_PRESETS[config.camera];
    if (!preset) return;

    targetPosRef.current.set(...preset.position);
    targetLookRef.current.set(...preset.target);
    autoRotateRef.current = config.camera === "cinematic";
  }, [currentPhase, overridePreset]);

  // Clear override on phase change so auto-follow resumes
  useEffect(() => {
    useCameraOverride.getState().clearOverride();
    prevPhaseRef.current = null;
  }, [currentPhase]);

  useFrame(() => {
    if (!controlsRef.current) return;

    camera.position.lerp(targetPosRef.current, LERP_FACTOR);
    tmpTarget.copy(controlsRef.current.target);
    tmpTarget.lerp(targetLookRef.current, LERP_FACTOR);
    controlsRef.current.target.copy(tmpTarget);

    controlsRef.current.autoRotate = autoRotateRef.current;
    controlsRef.current.autoRotateSpeed = 0.5;
    controlsRef.current.update();
  });

  return (
    <OrbitControls
      ref={controlsRef}
      enableDamping
      dampingFactor={0.08}
      minDistance={5}
      maxDistance={200}
      maxPolarAngle={Math.PI * 0.48}
    />
  );
}
