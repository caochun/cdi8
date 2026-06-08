"use client";

import { useMemo, useState } from "react";
import { AlertTriangle, Ban, CheckCircle2, RotateCcw, ShieldAlert, ShieldCheck, WifiOff, Wifi } from "lucide-react";
import { DEFAULT_GXLF_EVENTS_URL, commandUrlForEventsUrl } from "@/hooks/useGXLFLifecycleEvents";
import { useExperimentStore } from "@/state/experimentStore";
import type { GXLFFault } from "@/types";

type FaultCommand =
  | "recipe_off"
  | "recipe_on"
  | "safety_bad"
  | "safety_normal"
  | "seed_offline"
  | "seed_running"
  | "n04_reject"
  | "n04_failed"
  | "n04_timeout"
  | "clear_faults";

const COMMANDS: Array<{
  id: FaultCommand;
  label: string;
  title: string;
  Icon: typeof AlertTriangle;
  payload: Record<string, unknown>;
}> = [
  {
    id: "recipe_off",
    label: "配方未加载",
    title: "设置 recipe_loaded=false，使 N01 等待配方 guard",
    Icon: Ban,
    payload: { command: "set_flag", key: "recipe_loaded", value: false },
  },
  {
    id: "recipe_on",
    label: "配方已加载",
    title: "设置 recipe_loaded=true，释放 N01 配方 guard",
    Icon: CheckCircle2,
    payload: { command: "set_flag", key: "recipe_loaded", value: true },
  },
  {
    id: "safety_bad",
    label: "安全异常",
    title: "设置 safety=abnormal，使安全联锁 guard 阻塞",
    Icon: ShieldAlert,
    payload: { command: "set_interlock", key: "safety", value: "abnormal" },
  },
  {
    id: "safety_normal",
    label: "安全正常",
    title: "设置 safety=normal，释放安全联锁 guard",
    Icon: ShieldCheck,
    payload: { command: "set_interlock", key: "safety", value: "normal" },
  },
  {
    id: "seed_offline",
    label: "种子源离线",
    title: "设置光纤种子源组件 health_state=offline",
    Icon: WifiOff,
    payload: { command: "set_service_health", system_name: "光纤种子源组件", health_state: "offline" },
  },
  {
    id: "seed_running",
    label: "种子源运行",
    title: "设置光纤种子源组件 health_state=running",
    Icon: Wifi,
    payload: { command: "set_service_health", system_name: "光纤种子源组件", health_state: "running" },
  },
  {
    id: "n04_reject",
    label: "N04 拒收",
    title: "注入 N04 光纤种子源命令拒收故障",
    Icon: AlertTriangle,
    payload: {
      command: "inject_fault",
      fault: { behavior: "reject", node_id: "N04", system_name: "光纤种子源组件" },
    },
  },
  {
    id: "n04_failed",
    label: "N04 回调失败",
    title: "注入 N04 光纤种子源 callback failed 故障",
    Icon: AlertTriangle,
    payload: {
      command: "inject_fault",
      fault: { behavior: "callback_failed", node_id: "N04", system_name: "光纤种子源组件" },
    },
  },
  {
    id: "n04_timeout",
    label: "N04 回调超时",
    title: "注入 N04 光纤种子源 callback timeout 故障",
    Icon: AlertTriangle,
    payload: {
      command: "inject_fault",
      fault: { behavior: "callback_timeout", node_id: "N04", system_name: "光纤种子源组件" },
    },
  },
  {
    id: "clear_faults",
    label: "清除故障",
    title: "清除已注入的设备故障",
    Icon: RotateCcw,
    payload: { command: "clear_faults" },
  },
];

function faultBehaviorLabel(behavior?: string): string {
  if (behavior === "reject") return "命令拒收";
  if (behavior === "callback_failed") return "回调失败";
  if (behavior === "callback_timeout") return "回调超时";
  return behavior ?? "未知故障";
}

function faultLabel(fault: GXLFFault): string {
  const target = [fault.node_id, fault.system_name || fault.service_id || fault.instance_code].filter(Boolean).join(" ");
  return `${target || "全局"} ${faultBehaviorLabel(fault.behavior)}`;
}

export function FaultInjectionPanel() {
  const connectionStatus = useExperimentStore((s) => s.lifecycleConnectionStatus);
  const engineStatus = useExperimentStore((s) => s.engineStatus);
  const engineMessage = useExperimentStore((s) => s.engineMessage);
  const activeFaults = useExperimentStore((s) => s.activeFaults);
  const serviceHealthOverrides = useExperimentStore((s) => s.serviceHealthOverrides);
  const guardContext = useExperimentStore((s) => s.guardContext);
  const [pending, setPending] = useState<FaultCommand | null>(null);
  const [error, setError] = useState<string | null>(null);
  const eventUrl = process.env.NEXT_PUBLIC_GXLF_EVENTS_URL ?? DEFAULT_GXLF_EVENTS_URL;
  const commandUrl = useMemo(() => commandUrlForEventsUrl(eventUrl), [eventUrl]);

  async function send(payload: Record<string, unknown>, id: FaultCommand) {
    setPending(id);
    setError(null);
    try {
      const response = await fetch(commandUrl, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      });
      const body = await response.json().catch(() => ({}));
      if (!response.ok) setError(String(body.message ?? `命令失败: ${response.status}`));
    } catch (err) {
      setError(err instanceof Error ? err.message : "命令失败");
    } finally {
      setPending(null);
    }
  }

  const disabled = connectionStatus === "error" || pending !== null;
  const guardItems = [
    guardContext?.flags?.recipe_loaded === false ? "配方未加载" : "",
    guardContext?.flags?.central_control_healthy === false ? "总控健康异常" : "",
    ...Object.entries(guardContext?.interlocks ?? {})
      .filter(([, value]) => value !== "normal")
      .map(([key, value]) => `${key}=${String(value)}`),
  ].filter(Boolean);
  const serviceItems = Object.entries(serviceHealthOverrides)
    .filter(([, value]) => value !== "running")
    .map(([systemName, value]) => `${systemName}=${value}`);
  const activeItems = [
    ...guardItems,
    ...serviceItems,
    ...activeFaults.map(faultLabel),
  ];
  const hasActiveInjection = activeItems.length > 0;

  return (
    <div
      className="absolute bottom-3 right-3 z-20 w-[19rem] pointer-events-auto"
      style={{
        background: "rgba(10, 10, 20, 0.82)",
        backdropFilter: "blur(10px)",
        border: "1px solid rgba(255, 170, 70, 0.24)",
        borderRadius: "0.5rem",
      }}
    >
      <div className="flex items-center justify-between border-b border-white/5 px-3 py-2">
        <div className="text-[11px] uppercase text-white/45">故障注入</div>
        <div
          className="rounded-full border px-2 py-0.5 font-mono text-[10px]"
          style={{
            borderColor: hasActiveInjection ? "rgba(255, 85, 85, 0.45)" : "rgba(255, 255, 255, 0.12)",
            color: hasActiveInjection ? "rgba(255, 190, 190, 0.9)" : "rgba(255, 255, 255, 0.42)",
            background: hasActiveInjection ? "rgba(255, 65, 65, 0.12)" : "rgba(255, 255, 255, 0.03)",
          }}
        >
          {hasActiveInjection ? `${activeItems.length} 项活动` : String(engineStatus)}
        </div>
      </div>
      <div
        className="border-b px-3 py-2"
        style={{
          borderColor: hasActiveInjection ? "rgba(255, 85, 85, 0.18)" : "rgba(255, 255, 255, 0.05)",
          background: hasActiveInjection ? "rgba(120, 20, 20, 0.18)" : "rgba(255, 255, 255, 0.015)",
        }}
      >
        <div className="mb-1 text-[10px] uppercase text-white/38">当前注入</div>
        {hasActiveInjection ? (
          <div className="max-h-24 space-y-1 overflow-y-auto pr-1">
            {activeItems.map((item) => (
              <div
                key={item}
                className="rounded border border-red-300/15 bg-red-500/[0.08] px-2 py-1 text-[10px] leading-snug text-red-100/86"
              >
                {item}
              </div>
            ))}
          </div>
        ) : (
          <div className="rounded border border-white/5 bg-white/[0.025] px-2 py-1.5 text-[10px] text-white/38">
            无活动注入
          </div>
        )}
      </div>
      <div className="grid grid-cols-2 gap-1.5 px-2 py-2">
        {COMMANDS.map(({ id, label, title, Icon, payload }) => (
          <button
            key={id}
            type="button"
            title={title}
            disabled={disabled}
            onClick={() => void send(payload, id)}
            className="flex h-8 min-w-0 items-center justify-center gap-1.5 border border-white/10 bg-white/[0.04] px-2 text-[10px] text-white/68 transition hover:bg-white/[0.1] disabled:cursor-not-allowed disabled:opacity-30"
            style={{ borderRadius: "0.375rem" }}
          >
            <Icon size={13} strokeWidth={1.8} />
            <span className="truncate">{pending === id ? "发送中" : label}</span>
          </button>
        ))}
      </div>
      <div className="border-t border-white/5 px-3 py-2 font-mono text-[10px] text-white/35">
        <div className="truncate">{engineMessage ?? "可在启动前或运行中注入测试故障"}</div>
        {error && <div className="mt-1 truncate text-red-300/80">{error}</div>}
      </div>
    </div>
  );
}
