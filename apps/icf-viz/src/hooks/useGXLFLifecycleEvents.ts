"use client";

import { useEffect } from "react";
import { useExperimentStore } from "@/state/experimentStore";
import type { GXLFEngineEvent, GXLFLifecycleEvent } from "@/types";

export const DEFAULT_GXLF_EVENTS_URL = "http://127.0.0.1:8765/events";

type GXLFStreamFrame =
  | { kind: "lifecycle"; event: GXLFLifecycleEvent }
  | { kind: "engine"; eventName: string; event: GXLFEngineEvent };

export function commandUrlForEventsUrl(url: string): string {
  return url.replace(/\/events(?:\?.*)?$/, "/commands");
}

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

function parseJsonPayload(raw: string): Record<string, unknown> | null {
  try {
    const parsed = JSON.parse(raw);
    if (!parsed || typeof parsed !== "object") return null;
    return parsed as Record<string, unknown>;
  } catch {
    return null;
  }
}

function parseSseFrame(frame: string): GXLFStreamFrame | null {
  const lines = frame.split(/\r?\n/);
  const eventName =
    lines
      .find((line) => line.startsWith("event:"))
      ?.slice(6)
      .trim() ?? "message";
  const dataLines = lines
    .filter((line) => line.startsWith("data:"))
    .map((line) => line.slice(5).trimStart());
  if (!dataLines.length) return null;
  const rawData = dataLines.join("\n");
  if (
    eventName === "gxlf.engine.status" ||
    eventName === "gxlf.engine.command" ||
    eventName === "gxlf.engine.event" ||
    eventName === "gxlf.guard.update" ||
    eventName === "gxlf.fault.update" ||
    eventName === "gxlf.bridge.complete" ||
    eventName === "gxlf.bridge.error"
  ) {
    const event = parseJsonPayload(rawData);
    if (!event) return null;
    return { kind: "engine", eventName, event: event as GXLFEngineEvent };
  }
  const event = parseLifecycleEvent(rawData);
  if (!event) return null;
  return {
    kind: "lifecycle",
    event: {
      ...event,
      replayed: eventName === "gxlf.lifecycle.replay",
    },
  };
}

async function streamSseEvents(
  url: string,
  signal: AbortSignal,
  applyLifecycle: (event: GXLFLifecycleEvent) => void,
  applyEngine: (eventName: string, event: GXLFEngineEvent) => void
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
      if (!event) continue;
      if (event.kind === "lifecycle") applyLifecycle(event.event);
      else applyEngine(event.eventName, event.event);
    }
  }

  if (buffer.trim()) {
    const event = parseSseFrame(buffer);
    if (event?.kind === "lifecycle") applyLifecycle(event.event);
    if (event?.kind === "engine") applyEngine(event.eventName, event.event);
  }
}

export function useGXLFLifecycleEvents() {
  useEffect(() => {
    const url = process.env.NEXT_PUBLIC_GXLF_EVENTS_URL ?? DEFAULT_GXLF_EVENTS_URL;

    const store = useExperimentStore.getState();
    store.setLifecycleConnectionStatus("connecting");
    const applyLifecycle = store.applyLifecycleEvent;
    const applyEngine = store.applyEngineEvent;

    if (url.startsWith("ws://") || url.startsWith("wss://")) {
      const socket = new WebSocket(url);
      socket.onopen = () => useExperimentStore.getState().setLifecycleConnectionStatus("open");
      socket.onerror = () => useExperimentStore.getState().setLifecycleConnectionStatus("error");
      socket.onmessage = (message) => {
        if (typeof message.data !== "string") return;
        const event = parseLifecycleEvent(message.data);
        if (event) applyLifecycle(event);
      };
      return () => socket.close();
    }

    const controller = new AbortController();
    streamSseEvents(url, controller.signal, applyLifecycle, applyEngine).catch((error) => {
      if (controller.signal.aborted) return;
      console.error(error);
      useExperimentStore.getState().setLifecycleConnectionStatus("error");
    });
    return () => controller.abort();
  }, []);
}
