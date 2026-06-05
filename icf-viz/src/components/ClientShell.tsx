"use client";

import "@/lib/patchCircularJson";
import dynamic from "next/dynamic";

const ICFVisualization = dynamic(
  () => import("@/components/ICFVisualization"),
  { ssr: false }
);

export function ClientShell() {
  return <ICFVisualization />;
}
