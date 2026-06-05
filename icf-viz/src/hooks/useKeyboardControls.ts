"use client";

import { useEffect } from "react";
import { useExperimentStore } from "@/state/experimentStore";
import { Phase } from "@/types";

export function useKeyboardControls() {
  useEffect(() => {
    function handleKeyDown(e: KeyboardEvent) {
      if (e.target instanceof HTMLInputElement) return;

      const store = useExperimentStore.getState();
      if (store.runMode === "event" && e.code !== "KeyR") {
        return;
      }

      switch (e.code) {
        case "Space": {
          e.preventDefault();
          if (store.currentPhase === Phase.IDLE) {
            store.play();
          } else {
            store.togglePlay();
          }
          break;
        }
        case "ArrowRight": {
          e.preventDefault();
          store.stepForward();
          break;
        }
        case "ArrowLeft": {
          e.preventDefault();
          store.stepBackward();
          break;
        }
        case "KeyR": {
          if (!e.metaKey && !e.ctrlKey) {
            store.reset();
          }
          break;
        }
        case "Digit1":
          store.setSpeed(0.5);
          break;
        case "Digit2":
          store.setSpeed(1);
          break;
        case "Digit3":
          store.setSpeed(2);
          break;
        case "Digit4":
          store.setSpeed(4);
          break;
      }
    }

    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, []);
}
