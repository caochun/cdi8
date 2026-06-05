import { useFrame } from "@react-three/fiber";
import { useExperimentStore } from "@/state/experimentStore";

export function ExperimentClock() {
  const tick = useExperimentStore((s) => s.tick);
  const isPlaying = useExperimentStore((s) => s.isPlaying);
  const speed = useExperimentStore((s) => s.playbackSpeed);

  useFrame((_, delta) => {
    if (!isPlaying) return;
    const clamped = Math.min(delta, 0.1);
    tick(clamped * speed);
  });

  return null;
}
