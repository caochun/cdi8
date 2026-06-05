"use client";

import { useEffect } from "react";
import { useExperimentStore } from "@/state/experimentStore";
import type { GXLFLifecycleEvent } from "@/types";

function parseLifecycleEvent(raw: string): GXLFLifecycleEvent | null {
  try {
    const event = JSON.parse(raw) as GXLFLifecycleEvent;
    if (!event || typeof event !== "object" || !event.system_name) {
      return null;
    }
    return event;
  } catch {
    return null;
  }
}

function parseSseFrame(frame: string): GXLFLifecycleEvent | null {
  const dataLines = frame
    .split(/\r?\n/)
    .filter((line) => line.startsWith("data:"))
    .map((line) => line.slice(5).trimStart());
  if (!dataLines.length) return null;
  return parseLifecycleEvent(dataLines.join("\n"));
}

async function streamSseEvents(
  url: string,
  signal: AbortSignal,
  apply: (event: GXLFLifecycleEvent) => void
) {
  const response = await fetch(url, {
    headers: { Accept: "text/event-stream" },
    cache: "no-store",
    signal,
  });
  if (!response.ok || !response.body) {
    throw new Error(`event stream failed: ${response.status}`);
  }

  useExperimentStore.getState().setLifecycleConnectionStatus("open");
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const frames = buffer.split(/\r?\n\r?\n/);
    buffer = frames.pop() ?? "";
    for (const frame of frames) {
      const event = parseSseFrame(frame);
      if (event) apply(event);
    }
  }

  if (buffer.trim()) {
    const event = parseSseFrame(buffer);
    if (event) apply(event);
  }
}

export function useGXLFLifecycleEvents() {
  useEffect(() => {
    const url = process.env.NEXT_PUBLIC_GXLF_EVENTS_URL;
    if (!url) return;

    const store = useExperimentStore.getState();
    store.setRunMode("event");
    store.setLifecycleConnectionStatus("connecting");
    const apply = store.applyLifecycleEvent;

    if (url.startsWith("ws://") || url.startsWith("wss://")) {
      const socket = new WebSocket(url);
      socket.onopen = () => useExperimentStore.getState().setLifecycleConnectionStatus("open");
      socket.onerror = () => useExperimentStore.getState().setLifecycleConnectionStatus("error");
      socket.onmessage = (message) => {
        if (typeof message.data !== "string") return;
        const event = parseLifecycleEvent(message.data);
        if (event) apply(event);
      };
      return () => socket.close();
    }

    const controller = new AbortController();
    streamSseEvents(url, controller.signal, apply).catch((error) => {
      if (controller.signal.aborted) return;
      console.error(error);
      useExperimentStore.getState().setLifecycleConnectionStatus("error");
    });
    return () => controller.abort();
  }, []);
}
