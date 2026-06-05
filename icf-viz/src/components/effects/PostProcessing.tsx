import { useRef } from "react";
import { useFrame } from "@react-three/fiber";
import {
  EffectComposer,
  Bloom,
  Vignette,
  ToneMapping,
} from "@react-three/postprocessing";
import { ToneMappingMode } from "postprocessing";
import * as THREE from "three";
import { useExperimentStore } from "@/state/experimentStore";

export function PostProcessingEffects() {
  const bloomRef = useRef<any>(null);
  const bloomIntensity = useExperimentStore((s) => s.visuals.bloomIntensity);
  const currentRef = useRef(0.2);

  useFrame(() => {
    currentRef.current = THREE.MathUtils.lerp(
      currentRef.current,
      bloomIntensity,
      0.04
    );
    if (bloomRef.current) {
      bloomRef.current.intensity = currentRef.current;
    }
  });

  return (
    <EffectComposer>
      <Bloom
        ref={bloomRef}
        intensity={0.2}
        luminanceThreshold={0.7}
        luminanceSmoothing={0.9}
        mipmapBlur
      />
      <Vignette offset={0.3} darkness={0.55} />
      <ToneMapping mode={ToneMappingMode.ACES_FILMIC} />
    </EffectComposer>
  );
}
