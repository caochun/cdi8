"use client";

import { useMemo, useState } from "react";
import { Activity, Gauge, Pause, Play, RotateCcw, Square, StepForward } from "lucide-react";
import { DEFAULT_GXLF_EVENTS_URL, commandUrlForEventsUrl } from "@/hooks/useGXLFLifecycleEvents";
import { useExperimentStore } from "@/state/experimentStore";
import { GXLF_DEVICE_INSTANCES } from "@/state/gxlfDeviceLayout";

type EngineCommand = "start" | "pause" | "resume" | "stop" | "reset";
type SpeedOption = 0.5 | 1 | 2 | 5 | 10;

const COMMANDS: Array<{
  command: EngineCommand;
  label: string;
  Icon: typeof Play;
}> = [
  { command: "start", label: "启动", Icon: Play },
  { command: "pause", label: "暂停", Icon: Pause },
  { command: "resume", label: "继续", Icon: StepForward },
  { command: "stop", label: "停止", Icon: Square },
  { command: "reset", label: "复位", Icon: RotateCcw },
];

const SPEED_OPTIONS: SpeedOption[] = [0.5, 1, 2, 5, 10];

function commandEnabled(command: EngineCommand, status: string): boolean {
  if (command === "start") return status === "idle" || status === "completed" || status === "failed" || status === "aborted";
  if (command === "pause") return status === "running";
  if (command === "resume") return status === "paused";
  if (command === "stop") return status === "running" || status === "paused" || status === "waiting_guard";
  if (command === "reset") return status !== "running" && status !== "waiting_guard" && status !== "paused" && status !== "stopping";
  return false;
}

function statusText(status: string): string {
  if (status === "waiting_guard") return "等待条件";
  if (status === "running") return "运行中";
  if (status === "paused") return "已暂停";
  if (status === "completed") return "已完成";
  if (status === "failed") return "失败";
  if (status === "aborted") return "已中止";
  if (status === "stopping") return "停止中";
  if (status === "error") return "错误";
  return "空闲";
}

function statusColor(status: string): string {
  if (status === "running") return "#22cc88";
  if (status === "paused" || status === "waiting_guard") return "#ffaa00";
  if (status === "completed") return "#66aaff";
  if (status === "failed" || status === "aborted" || status === "error") return "#ff5555";
  return "#9aa6bd";
}

export function EngineControlPanel() {
  const engineStatus = useExperimentStore((s) => s.engineStatus);
  const engineMessage = useExperimentStore((s) => s.engineMessage);
  const engineSummary = useExperimentStore((s) => s.engineSummary);
  const connectionStatus = useExperimentStore((s) => s.lifecycleConnectionStatus);
  const eventCount = useExperimentStore((s) => s.lifecycleEvents.length);
  const seenInstances = useExperimentStore((s) => Object.keys(s.lifecycleServiceStates).length);
  const [pendingCommand, setPendingCommand] = useState<EngineCommand | null>(null);
  const [speedMultiplier, setSpeedMultiplier] = useState<SpeedOption>(1);
  const [error, setError] = useState<string | null>(null);
  const eventUrl = process.env.NEXT_PUBLIC_GXLF_EVENTS_URL ?? DEFAULT_GXLF_EVENTS_URL;
  const commandUrl = useMemo(() => commandUrlForEventsUrl(eventUrl), [eventUrl]);
  const color = statusColor(String(engineStatus));

  async function sendCommand(command: EngineCommand) {
    setPendingCommand(command);
    setError(null);
    try {
      const response = await fetch(commandUrl, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          command,
          ...(command === "start" ? { speed_multiplier: speedMultiplier } : {}),
        }),
      });
      const payload = await response.json().catch(() => ({}));
      if (!response.ok) {
        setError(String(payload.message ?? `命令失败: ${response.status}`));
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "命令失败");
    } finally {
      setPendingCommand(null);
    }
  }

  return (
    <div
      className="absolute bottom-3 left-3 z-20 w-[22rem] max-w-[calc(100vw-1.5rem)] pointer-events-auto"
      style={{
        background: "rgba(10, 10, 20, 0.84)",
        backdropFilter: "blur(10px)",
        border: "1px solid rgba(80, 130, 255, 0.24)",
        borderRadius: "0.5rem",
      }}
    >
      <div className="border-b border-white/5 px-3 py-2.5">
        <div className="flex items-center justify-between gap-3">
          <div className="min-w-0">
            <div className="text-[11px] uppercase text-white/45">流程引擎</div>
            <div className="mt-1 flex items-center gap-2">
              <span className="h-2.5 w-2.5 shrink-0 rounded-full" style={{ background: color, boxShadow: `0 0 10px ${color}` }} />
              <span className="text-sm font-medium text-white/86">{statusText(String(engineStatus))}</span>
              <span className="font-mono text-[10px] text-white/38">{String(engineStatus)}</span>
            </div>
          </div>
          <div className="shrink-0 text-right font-mono text-[10px] text-white/40">
            <div>{connectionStatus}</div>
            <div className="mt-1">{speedMultiplier}x</div>
          </div>
        </div>
      </div>

      <div className="border-b border-white/5 px-3 py-2">
        <div className="mb-1 flex items-center gap-1.5 text-[10px] uppercase text-white/38">
          <Activity size={12} strokeWidth={1.8} />
          当前信息
        </div>
        <div
          className="min-h-[3.25rem] overflow-hidden rounded-md border px-2.5 py-2 text-[11px] leading-relaxed"
          style={{
            borderColor: engineStatus === "waiting_guard" ? "rgba(255, 170, 0, 0.3)" : "rgba(255, 255, 255, 0.08)",
            background: engineStatus === "waiting_guard" ? "rgba(255, 170, 0, 0.08)" : "rgba(255, 255, 255, 0.035)",
            color: engineStatus === "waiting_guard" ? "rgba(255, 236, 190, 0.92)" : "rgba(230, 238, 255, 0.74)",
          }}
        >
          {engineMessage ?? (pendingCommand ? `发送命令: ${pendingCommand}` : "命令通道就绪")}
        </div>
      </div>

      <div className="px-3 py-2">
        <div className="mb-1.5 text-[10px] uppercase text-white/38">控制</div>
        <div className="grid grid-cols-5 gap-1.5">
          {COMMANDS.map(({ command, label, Icon }) => {
            const disabled = pendingCommand !== null || connectionStatus === "error" || !commandEnabled(command, String(engineStatus));
            return (
              <button
                key={command}
                type="button"
                title={label}
                disabled={disabled}
                onClick={() => void sendCommand(command)}
                className="flex h-9 items-center justify-center border border-white/10 bg-white/[0.04] text-white/70 transition hover:bg-white/[0.1] disabled:cursor-not-allowed disabled:opacity-30"
                style={{ borderRadius: "0.375rem" }}
              >
                <Icon size={16} strokeWidth={1.8} />
              </button>
            );
          })}
        </div>
      </div>

      <div className="flex items-center gap-1.5 border-t border-white/5 px-3 py-2">
        <div className="flex h-7 w-7 shrink-0 items-center justify-center text-white/40" title="倍速">
          <Gauge size={15} strokeWidth={1.8} />
        </div>
        <div className="grid min-w-0 flex-1 grid-cols-5 gap-1">
          {SPEED_OPTIONS.map((option) => {
            const active = option === speedMultiplier;
            const disabled =
              engineStatus === "running" ||
              engineStatus === "waiting_guard" ||
              engineStatus === "paused" ||
              pendingCommand !== null;
            return (
              <button
                key={option}
                type="button"
                title={`${option}x`}
                disabled={disabled}
                onClick={() => setSpeedMultiplier(option)}
                className="h-7 border text-[10px] transition disabled:cursor-not-allowed disabled:opacity-35"
                style={{
                  borderRadius: "0.375rem",
                  borderColor: active ? "rgba(90, 190, 255, 0.55)" : "rgba(255, 255, 255, 0.1)",
                  background: active ? "rgba(55, 155, 255, 0.18)" : "rgba(255, 255, 255, 0.04)",
                  color: active ? "rgba(225, 245, 255, 0.95)" : "rgba(255, 255, 255, 0.58)",
                }}
              >
                {option}x
              </button>
            );
          })}
        </div>
      </div>

      <div className="border-t border-white/5 px-3 py-2">
        <div className="grid grid-cols-3 gap-1.5">
          <div className="rounded-md border border-white/5 bg-white/[0.025] px-2 py-1.5">
            <div className="text-[9px] uppercase text-white/30">事件</div>
            <div className="mt-0.5 font-mono text-[12px] text-white/70">{eventCount}</div>
          </div>
          <div className="rounded-md border border-white/5 bg-white/[0.025] px-2 py-1.5">
            <div className="text-[9px] uppercase text-white/30">实例</div>
            <div className="mt-0.5 font-mono text-[12px] text-white/70">
              {seenInstances}/{GXLF_DEVICE_INSTANCES.length}
            </div>
          </div>
          <div className="rounded-md border border-white/5 bg-white/[0.025] px-2 py-1.5">
            <div className="text-[9px] uppercase text-white/30">节点</div>
            <div className="mt-0.5 font-mono text-[12px] text-white/70">{engineSummary?.executed_nodes ?? "-"}</div>
          </div>
        </div>
        {engineSummary?.flow_status && (
          <div className="mt-2 truncate font-mono text-[10px] text-white/35">
            {engineSummary.flow_status} / {engineSummary.executed_nodes ?? 0} nodes
          </div>
        )}
        <div className="mt-2 truncate font-mono text-[9px] text-white/24">{commandUrl}</div>
        {error && <div className="mt-1 truncate text-[10px] text-red-300/80">{error}</div>}
      </div>
    </div>
  );
}
