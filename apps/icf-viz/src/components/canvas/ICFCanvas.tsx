"use client";

import { Suspense } from "react";
import { Canvas } from "@react-three/fiber";
import { Environment } from "@react-three/drei";
import { SceneLighting } from "./SceneLighting";
import { CameraSystem } from "./CameraSystem";
import { FacilityGroup } from "@/components/facility/FacilityGroup";
import { PostProcessingEffects } from "@/components/effects/PostProcessing";
import { XenonFlash } from "@/components/effects/XenonFlash";
import { TargetImplosion } from "@/components/effects/TargetImplosion";
import { BeamPropagation } from "@/components/effects/BeamPropagation";

export function ICFCanvas() {
  return (
    <Canvas
      camera={{
        position: [0, 54, 58],
        fov: 50,
        near: 0.1,
        far: 500,
      }}
      shadows
      gl={{
        antialias: true,
        powerPreference: "high-performance",
      }}
      dpr={[1, 2]}
      style={{ width: "100%", height: "100%" }}
    >
      <color attach="background" args={["#08080e"]} />
      <fog attach="fog" args={["#08080e", 120, 250]} />

      <SceneLighting />
      <CameraSystem />

      <Suspense fallback={null}>
        <Environment preset="warehouse" background={false} />
        <FacilityGroup />
        <BeamPropagation />
        <XenonFlash />
        <TargetImplosion />
        <PostProcessingEffects />
      </Suspense>
    </Canvas>
  );
}
