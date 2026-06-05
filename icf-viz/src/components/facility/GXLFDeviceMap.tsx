import { memo, useMemo, useRef } from "react";
import { Html, Text } from "@react-three/drei";
import { useFrame } from "@react-three/fiber";
import * as THREE from "three";
import { SUBSYSTEM_STATUS_COLORS } from "@/lib/constants";
import { GXLF_DEVICE_INSTANCES, GXLF_DEVICE_SYSTEM_ORDER, type GXLFDeviceInstance } from "@/state/gxlfDeviceLayout";
import { useExperimentStore } from "@/state/experimentStore";
import { expectedCountForGXLFSystem } from "@/state/gxlfSystemMap";
import { SubsystemStatus, type LifecycleServiceState, type LifecycleSystemStats, type Vec3 } from "@/types";

const STATUS_COLOR: Record<string, string> = {
  ...SUBSYSTEM_STATUS_COLORS,
  OFF: "#303039",
};

function colorForStatus(status: SubsystemStatus): string {
  return STATUS_COLOR[status] ?? STATUS_COLOR.OFF;
}

function isBusyStatus(status: SubsystemStatus): boolean {
  return (
    status === SubsystemStatus.ACTIVE ||
    status === SubsystemStatus.WARMING ||
    status === SubsystemStatus.FIRING ||
    status === SubsystemStatus.COLLECTING
  );
}

function serviceStateForDevice(
  device: GXLFDeviceInstance,
  serviceStates: Record<string, LifecycleServiceState>
): LifecycleServiceState | undefined {
  return (
    serviceStates[device.serviceId] ??
    Object.values(serviceStates).find(
      (state) =>
        state.systemName === device.systemName &&
        ((device.kind === "beam_line" && state.beamLineNo === device.instanceNo) ||
          (device.kind === "beam_group" && state.beamGroupNo === device.instanceNo) ||
          device.kind === "single")
    )
  );
}

function statusForDevice(
  device: GXLFDeviceInstance,
  serviceState: LifecycleServiceState | undefined,
  aggregateStatus: SubsystemStatus | undefined
): SubsystemStatus {
  if (serviceState) return serviceState.status;
  if (device.kind === "single" && aggregateStatus) return aggregateStatus;
  return SubsystemStatus.OFF;
}

function DeviceMarker({
  device,
  serviceState,
  status,
  isLatest,
}: {
  device: GXLFDeviceInstance;
  serviceState?: LifecycleServiceState;
  status: SubsystemStatus;
  isLatest: boolean;
}) {
  const markerRef = useRef<THREE.Group>(null);
  const materialRef = useRef<THREE.MeshStandardMaterial>(null);
  const haloRef = useRef<THREE.MeshStandardMaterial>(null);
  const color = colorForStatus(status);
  const hasEvent = Boolean(serviceState);
  const size = device.kind === "beam_line" ? 0.32 : device.kind === "beam_group" ? 0.55 : 0.8;

  useFrame(({ clock }) => {
    if (!markerRef.current) return;
    const ageMs = serviceState ? Date.now() - serviceState.receivedAt : 99999;
    const pulse = Math.max(0, 1 - ageMs / 2200);
    const busyPulse = isBusyStatus(status)
      ? 0.08 * (1 + Math.sin(clock.elapsedTime * 5 + device.instanceNo)) * 0.5
      : 0;
    const scale = 1 + pulse * 0.9 + busyPulse;
    markerRef.current.scale.setScalar(scale);

    if (materialRef.current) {
      materialRef.current.emissiveIntensity = hasEvent ? 0.15 + pulse * 1.3 : 0.02;
      materialRef.current.opacity = hasEvent ? 0.94 : 0.36;
    }
    if (haloRef.current) {
      haloRef.current.opacity = Math.min(0.55, pulse * 0.5 + (isLatest ? 0.18 : 0));
      haloRef.current.emissiveIntensity = pulse * 2.5 + (isLatest ? 0.7 : 0);
    }
  });

  return (
    <group position={device.position}>
      <group ref={markerRef}>
        <mesh castShadow>
          <sphereGeometry args={[size, 14, 10]} />
          <meshStandardMaterial
            ref={materialRef}
            color={color}
            emissive={color}
            emissiveIntensity={0.05}
            roughness={0.3}
            metalness={0.35}
            transparent
            opacity={hasEvent ? 0.94 : 0.36}
          />
        </mesh>
        <mesh>
          <sphereGeometry args={[size * 1.7, 14, 10]} />
          <meshStandardMaterial
            ref={haloRef}
            color={color}
            emissive={color}
            emissiveIntensity={0}
            transparent
            opacity={0}
            depthWrite={false}
          />
        </mesh>
      </group>

      {isLatest && serviceState && (
        <Html
          position={[0, size * 2.2, 0]}
          center
          distanceFactor={46}
          style={{ pointerEvents: "none" }}
        >
          <div className="min-w-36 max-w-52 rounded border border-white/15 bg-black/75 px-2 py-1 text-[10px] leading-tight text-white/80 shadow-lg">
            <div className="font-mono text-white/45">#{serviceState.seq} {serviceState.nodeId}</div>
            <div className="truncate text-white/90">{device.label}</div>
            <div className="truncate text-white/60">{serviceState.command ?? serviceState.eventType}</div>
            <div className="font-mono" style={{ color }}>{status}</div>
          </div>
        </Html>
      )}
    </group>
  );
}

function SystemLabel({
  position,
  title,
  stats,
}: {
  position: Vec3;
  title: string;
  stats?: LifecycleSystemStats;
}) {
  const status = stats?.status ?? SubsystemStatus.OFF;
  const color = colorForStatus(status);
  const seen = stats?.seenCount ?? 0;
  const expected = stats?.expectedCount ?? expectedCountForGXLFSystem(title);

  return (
    <group position={position}>
      <Text
        position={[0, 1.1, 0]}
        fontSize={0.9}
        color="#dce8ff"
        anchorX="center"
        anchorY="middle"
        outlineWidth={0.02}
        outlineColor="#05060a"
      >
        {title}
      </Text>
      <Text
        position={[0, 0.15, 0]}
        fontSize={0.55}
        color={color}
        anchorX="center"
        anchorY="middle"
        outlineWidth={0.015}
        outlineColor="#05060a"
      >
        {`${seen}/${expected} ${status}`}
      </Text>
      <mesh position={[0, -0.45, 0]} rotation={[-Math.PI / 2, 0, 0]}>
        <ringGeometry args={[0.75, 0.95, 36]} />
        <meshBasicMaterial color={color} transparent opacity={0.36} side={THREE.DoubleSide} />
      </mesh>
    </group>
  );
}

function BeamLineLegend() {
  const labels = [
    { text: "集中同步 60", radius: 30.5, y: 3.2, color: colorForStatus(SubsystemStatus.ACTIVE) },
    { text: "测量取样 60", radius: 33, y: 3.9, color: colorForStatus(SubsystemStatus.COLLECTING) },
    { text: "频率转换 60", radius: 35.5, y: 4.6, color: colorForStatus(SubsystemStatus.READY) },
  ];

  return (
    <group>
      {labels.map((item) => (
        <group key={item.text}>
          <mesh position={[0, item.y - 0.05, 0]} rotation={[-Math.PI / 2, 0, 0]}>
            <ringGeometry args={[item.radius - 0.08, item.radius + 0.08, 120]} />
            <meshBasicMaterial color={item.color} transparent opacity={0.12} side={THREE.DoubleSide} />
          </mesh>
          <Text
            position={[item.radius, item.y + 0.9, 0]}
            fontSize={0.75}
            color={item.color}
            anchorX="center"
            anchorY="middle"
            outlineWidth={0.018}
            outlineColor="#05060a"
          >
            {item.text}
          </Text>
        </group>
      ))}
    </group>
  );
}

function SingleSystemLabels({
  stats,
}: {
  stats: Record<string, LifecycleSystemStats>;
}) {
  const labelPositions = useMemo(
    () => [
      { title: "光纤种子源组件", systemName: "光纤种子源组件", position: [-44, 6, -24] as Vec3 },
      { title: "二倍频宽带激光注入组件", systemName: "二倍频宽带激光注入组件", position: [-36, 6, -24] as Vec3 },
      { title: "泵浦分系统", systemName: "泵浦分系统", position: [42, 6.8, -23] as Vec3 },
      { title: "靶瞄准定位系统", systemName: "靶瞄准定位系统", position: [-8, 12.7, 0] as Vec3 },
      { title: "真空靶室分系统", systemName: "真空靶室分系统", position: [0, 15.5, 0] as Vec3 },
      { title: "物理实验诊断分系统", systemName: "物理实验诊断分系统", position: [31, 7.8, 19] as Vec3 },
      { title: "开关驱动源组件", systemName: "开关驱动源组件", position: [42, 6.8, -10] as Vec3 },
      { title: "冷却分系统", systemName: "冷却分系统", position: [-45, 6.8, 24] as Vec3 },
      { title: "控制环境组件", systemName: "控制环境组件", position: [-43, 8, 14] as Vec3 },
      { title: "屏蔽门控制接口", systemName: "建安工程安防系统屏蔽门控制接口", position: [0, 9.7, 39] as Vec3 },
      { title: "人员进出计数接口", systemName: "人员进出计数接口", position: [-9, 9.2, 38] as Vec3 },
    ],
    []
  );

  return (
    <group>
      {labelPositions.map((item) => (
        <SystemLabel
          key={item.title}
          position={item.position}
          title={item.title}
          stats={stats[item.systemName]}
        />
      ))}
    </group>
  );
}

function GXLFModelSummary() {
  const stats = useExperimentStore((s) => s.lifecycleSystemStats);
  const eventCount = useExperimentStore((s) => s.lifecycleEvents.length);
  const totals = GXLF_DEVICE_SYSTEM_ORDER.reduce(
    (acc, systemName) => {
      const item = stats[systemName];
      acc.expected += expectedCountForGXLFSystem(systemName);
      acc.seen += item?.seenCount ?? 0;
      acc.active += item?.activeCount ?? 0;
      acc.errors += item?.errorCount ?? 0;
      return acc;
    },
    { expected: 0, seen: 0, active: 0, errors: 0 }
  );

  return (
    <Html position={[-55, 18, -34]} transform distanceFactor={60} style={{ pointerEvents: "none" }}>
      <div className="w-64 rounded border border-cyan-300/20 bg-black/70 p-3 text-xs text-white/75 shadow-xl">
        <div className="text-[10px] uppercase tracking-[0.18em] text-cyan-200/55">GXLF 模型实例地图</div>
        <div className="mt-2 grid grid-cols-2 gap-x-3 gap-y-1 font-mono text-[11px]">
          <span className="text-white/40">systems</span><span>{GXLF_DEVICE_SYSTEM_ORDER.length}</span>
          <span className="text-white/40">instances</span><span>{totals.seen}/{totals.expected}</span>
          <span className="text-white/40">active</span><span>{totals.active}</span>
          <span className="text-white/40">errors</span><span>{totals.errors}</span>
          <span className="text-white/40">events</span><span>{eventCount}</span>
        </div>
      </div>
    </Html>
  );
}

export const GXLFDeviceMap = memo(function GXLFDeviceMap() {
  const serviceStates = useExperimentStore((s) => s.lifecycleServiceStates);
  const stats = useExperimentStore((s) => s.lifecycleSystemStats);
  const subsystems = useExperimentStore((s) => s.subsystems);
  const lastEvent = useExperimentStore((s) => s.lastLifecycleEvent);

  const latestServiceId = lastEvent?.service_id;

  return (
    <group>
      <BeamLineLegend />
      <SingleSystemLabels stats={stats} />
      <SystemLabel position={[-18, 7.6, -8]} title="再生与双程放大组件" stats={stats["再生与双程放大组件"]} />
      <SystemLabel position={[0, 8.2, 0]} title="多程放大组件" stats={stats["多程放大组件"]} />
      <SystemLabel position={[30.5, 7, 0]} title="集中同步分系统" stats={stats["集中同步分系统"]} />
      <SystemLabel position={[33, 7.7, 0]} title="测量取样组件" stats={stats["测量取样组件"]} />
      <SystemLabel position={[35.5, 8.4, 0]} title="频率转换分系统" stats={stats["频率转换分系统"]} />

      {GXLF_DEVICE_INSTANCES.map((device) => {
        const serviceState = serviceStateForDevice(device, serviceStates);
        const status = statusForDevice(device, serviceState, subsystems[device.subsystemId]);
        return (
          <DeviceMarker
            key={device.serviceId}
            device={device}
            serviceState={serviceState}
            status={status}
            isLatest={Boolean(
              serviceState &&
                (serviceState.serviceId === latestServiceId ||
                  (!latestServiceId && serviceState.seq === lastEvent?.seq))
            )}
          />
        );
      })}

      <GXLFModelSummary />
    </group>
  );
});
