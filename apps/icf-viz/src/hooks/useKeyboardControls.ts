"use client";

import { useEffect } from "react";
import { useExperimentStore } from "@/state/experimentStore";

export function useKeyboardControls() {
  useEffect(() => {
    function handleKeyDown(e: KeyboardEvent) {
      if (e.target instanceof HTMLInputElement) return;

      if (e.code === "KeyR" && !e.metaKey && !e.ctrlKey) {
        useExperimentStore.getState().reset();
      }
    }

    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, []);
}
