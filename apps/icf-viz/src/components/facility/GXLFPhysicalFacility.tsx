import { memo, useRef } from "react";
import { Html, Text } from "@react-three/drei";
import { useFrame } from "@react-three/fiber";
import * as THREE from "three";
import {
  BUILDING_DEPTH,
  BUILDING_WIDTH,
  FLOOR_Y,
  SUBSYSTEM_STATUS_COLORS,
  TARGET_CHAMBER_CENTER,
} from "@/lib/constants";
import {
  BEAM_GROUP_COUNT,
  BEAMS_PER_GROUP,
  GXLF_BEAM_GROUP_LAYOUTS,
  GXLF_BEAM_LAYOUTS,
  GXLF_DEVICE_SYSTEM_ORDER,
  type BeamLineLayout,
} from "@/state/gxlfDeviceLayout";
import { useExperimentStore } from "@/state/experimentStore";
import { expectedCountForGXLFSystem } from "@/state/gxlfSystemMap";
import {
  InterlockId,
  Phase,
  SubsystemStatus,
  type GXLFSystemName,
  type LifecycleServiceState,
  type Vec3,
} from "@/types";

const STATUS_COLOR: Record<string, string> = {
  ...SUBSYSTEM_STATUS_COLORS,
  OFF: "#303038",
};

type ServiceStates = Record<string, LifecycleServiceState>;

const [TARGET_X, TARGET_Y, TARGET_Z] = TARGET_CHAMBER_CENTER;
const CONTROL_EVENT_SOURCE: Vec3 = [-48, 7.5, 27];
const SYSTEM_EVENT_POSITIONS: Partial<Record<GXLFSystemName, Vec3>> = {
  光纤种子源组件: [-48, 5.5, -26],
  二倍频宽带激光注入组件: [-42, 5.5, -26],
  泵浦分系统: [42, 6.4, -27],
  开关驱动源组件: [52, 6.4, -18],
  冷却分系统: [44, 6.4, 28],
  靶瞄准定位系统: [TARGET_X + 8.8, TARGET_Y + 3.8, TARGET_Z + 1.8],
  真空靶室分系统: TARGET_CHAMBER_CENTER,
  物理实验诊断分系统: [TARGET_X - 11.5, TARGET_Y + 3.4, TARGET_Z - 3.8],
  控制环境组件: CONTROL_EVENT_SOURCE,
  建安工程安防系统屏蔽门控制接口: [0, 7.2, 39.4],
  人员进出计数接口: [-11, 7.1, 38],
};

function twoDigit(no: number): string {
  return String(no).padStart(2, "0");
}

function colorForStatus(status: SubsystemStatus): string {
  return STATUS_COLOR[status] ?? STATUS_COLOR.OFF;
}

function statusIsLit(status: SubsystemStatus): boolean {
  return status !== SubsystemStatus.OFF && status !== SubsystemStatus.STANDBY;
}

function colorForTaskState(latest: LifecycleServiceState | undefined, fallback: SubsystemStatus): string {
  const taskState = latest?.taskState;
  const resultStatus = latest?.resultStatus;
  if (taskState === "failed" || taskState === "timeout" || resultStatus === "failed") return "#ff4f5f";
  if (taskState === "executing") return "#ffb84d";
  if (taskState === "accepted") return "#66c7ff";
  if (taskState === "succeeded") return "#39e58c";
  return colorForStatus(fallback);
}

function labelForTaskState(latest: LifecycleServiceState | undefined, status: SubsystemStatus): string {
  const taskState = latest?.taskState;
  if (taskState === "accepted") return "ACCEPTED";
  if (taskState === "executing") return "EXEC";
  if (taskState === "succeeded") return "OK";
  if (taskState === "failed") return "FAIL";
  if (taskState === "timeout") return "TIMEOUT";
  if (status === SubsystemStatus.ERROR) return "ERROR";
  if (status === SubsystemStatus.ACTIVE || status === SubsystemStatus.FIRING) return "ACTIVE";
  if (status === SubsystemStatus.READY) return "READY";
  return "";
}

function latestServiceForSystem(
  systemName: string,
  serviceStates: ServiceStates
): LifecycleServiceState | undefined {
  return Object.values(serviceStates)
    .filter((state) => state.systemName === systemName)
    .sort((a, b) => b.seq - a.seq)[0];
}

function latestServiceForIds(
  serviceIds: string[],
  serviceStates: ServiceStates
): LifecycleServiceState | undefined {
  return serviceIds
    .map((id) => serviceStates[id])
    .filter(Boolean)
    .sort((a, b) => b.seq - a.seq)[0];
}

function beamLineService(
  system: "sync" | "measurement_sample" | "frequency_conversion",
  no: number
): string {
  return `gxlf.${system}.bl${twoDigit(no)}`;
}

function beamGroupService(system: "preamp" | "multipass_amp", no: number): string {
  return `gxlf.${system}.bg${twoDigit(no)}`;
}

function eventDestination(event: LifecycleServiceState | undefined): Vec3 {
  if (!event) return TARGET_CHAMBER_CENTER;
  if (event.beamLineNo) {
    const beam = GXLF_BEAM_LAYOUTS[event.beamLineNo - 1];
    if (beam) {
      if (event.systemName === "集中同步分系统") return beam.sync;
      if (event.systemName === "测量取样组件") return beam.measurement;
      if (event.systemName === "频率转换分系统") return beam.frequencyConversion;
      return beam.chamberPort;
    }
  }
  if (event.beamGroupNo) {
    const group = GXLF_BEAM_GROUP_LAYOUTS[event.beamGroupNo - 1];
    if (group) {
      if (event.systemName === "再生与双程放大组件") return group.preampPosition;
      if (event.systemName === "多程放大组件") return group.multipassPosition;
    }
  }
  return SYSTEM_EVENT_POSITIONS[event.systemName as GXLFSystemName] ?? TARGET_CHAMBER_CENTER;
}

function midpoint(a: Vec3, b: Vec3): Vec3 {
  return [(a[0] + b[0]) / 2, (a[1] + b[1]) / 2, (a[2] + b[2]) / 2];
}

function lengthBetween(a: Vec3, b: Vec3): number {
  return new THREE.Vector3(...a).distanceTo(new THREE.Vector3(...b));
}

function quaternionBetween(a: Vec3, b: Vec3): THREE.Quaternion {
  const dir = new THREE.Vector3(b[0] - a[0], b[1] - a[1], b[2] - a[2]).normalize();
  return new THREE.Quaternion().setFromUnitVectors(new THREE.Vector3(0, 1, 0), dir);
}

function BeamSegment({
  start,
  end,
  radius,
  color,
  lit,
  opacity = 0.62,
}: {
  start: Vec3;
  end: Vec3;
  radius: number;
  color: string;
  lit: boolean;
  opacity?: number;
}) {
  const materialRef = useRef<THREE.MeshStandardMaterial>(null);
  const center = midpoint(start, end);
  const length = lengthBetween(start, end);
  const quaternion = quaternionBetween(start, end);

  useFrame(({ clock }) => {
    if (!materialRef.current) return;
    const shimmer = lit ? 0.42 + Math.sin(clock.elapsedTime * 5 + start[0]) * 0.14 : 0;
    materialRef.current.emissiveIntensity = shimmer;
    materialRef.current.opacity = lit ? opacity : 0.18;
  });

  return (
    <mesh position={center} quaternion={quaternion} castShadow>
      <cylinderGeometry args={[radius, radius, length, 8]} />
      <meshStandardMaterial
        ref={materialRef}
        color={lit ? color : "#444851"}
        emissive={color}
        emissiveIntensity={0}
        roughness={0.32}
        metalness={0.54}
        transparent
        opacity={lit ? opacity : 0.18}
      />
    </mesh>
  );
}

function EventTrail({
  start,
  end,
  latest,
}: {
  start: Vec3;
  end: Vec3;
  latest?: LifecycleServiceState;
}) {
  const matRef = useRef<THREE.MeshStandardMaterial>(null);
  const pulseRef = useRef<THREE.Mesh>(null);
  const center = midpoint(start, end);
  const length = lengthBetween(start, end);
  const quaternion = quaternionBetween(start, end);
  const color = colorForTaskState(latest, latest?.status ?? SubsystemStatus.ACTIVE);

  useFrame(({ clock }) => {
    const age = latest ? Date.now() - latest.receivedAt : 999999;
    const pulse = Math.max(0, 1 - age / 1800);
    if (matRef.current) {
      matRef.current.color.set(color);
      matRef.current.emissive.set(color);
      matRef.current.opacity = pulse * 0.62;
      matRef.current.emissiveIntensity = pulse * 2.4;
    }
    if (pulseRef.current) {
      const progress = ((clock.elapsedTime * 0.9) % 1) * 2 - 0.5;
      pulseRef.current.position.set(
        start[0] + (end[0] - start[0]) * Math.min(1, Math.max(0, progress)),
        start[1] + (end[1] - start[1]) * Math.min(1, Math.max(0, progress)),
        start[2] + (end[2] - start[2]) * Math.min(1, Math.max(0, progress))
      );
      pulseRef.current.scale.setScalar(0.4 + pulse * 0.9);
    }
  });

  if (!latest || length < 0.1) return null;

  return (
    <group>
      <mesh position={center} quaternion={quaternion}>
        <cylinderGeometry args={[0.08, 0.08, length, 8]} />
        <meshStandardMaterial ref={matRef} color={color} emissive={color} transparent opacity={0} depthWrite={false} />
      </mesh>
      <mesh ref={pulseRef}>
        <sphereGeometry args={[0.42, 16, 10]} />
        <meshStandardMaterial color={color} emissive={color} emissiveIntensity={2.8} transparent opacity={0.72} depthWrite={false} />
      </mesh>
    </group>
  );
}

function EventPulseMarker({
  position,
  latest,
}: {
  position: Vec3;
  latest?: LifecycleServiceState;
}) {
  const ringRef = useRef<THREE.Mesh>(null);
  const matRef = useRef<THREE.MeshStandardMaterial>(null);
  const color = colorForTaskState(latest, latest?.status ?? SubsystemStatus.ACTIVE);

  useFrame(({ clock }) => {
    const age = latest ? Date.now() - latest.receivedAt : 999999;
    const pulse = Math.max(0, 1 - age / 2100);
    if (ringRef.current) {
      ringRef.current.rotation.z = clock.elapsedTime * 1.8;
      ringRef.current.scale.setScalar(1 + pulse * 2.8);
    }
    if (matRef.current) {
      matRef.current.color.set(color);
      matRef.current.emissive.set(color);
      matRef.current.opacity = pulse * 0.58;
      matRef.current.emissiveIntensity = pulse * 3.2;
    }
  });

  if (!latest) return null;

  return (
    <mesh ref={ringRef} position={position} rotation={[Math.PI / 2, 0, 0]}>
      <torusGeometry args={[1.1, 0.08, 8, 56]} />
      <meshStandardMaterial ref={matRef} color={color} emissive={color} transparent opacity={0} depthWrite={false} />
    </mesh>
  );
}

function RecentEventEffects({ serviceStates }: { serviceStates: ServiceStates }) {
  const event = useExperimentStore((s) => s.lastLifecycleEvent);
  if (!event) return null;
  const latest =
    (event.service_id ? serviceStates[event.service_id] : undefined) ??
    latestServiceForSystem(event.system_name, serviceStates);
  const destination = eventDestination(latest);

  return (
    <group>
      <EventTrail start={CONTROL_EVENT_SOURCE} end={destination} latest={latest} />
      <EventPulseMarker position={destination} latest={latest} />
    </group>
  );
}

function StatusBeacon({
  position,
  status,
  size = 0.55,
  latest,
  showTaskLabel = false,
}: {
  position: Vec3;
  status: SubsystemStatus;
  size?: number;
  latest?: LifecycleServiceState;
  showTaskLabel?: boolean;
}) {
  const ref = useRef<THREE.Group>(null);
  const matRef = useRef<THREE.MeshStandardMaterial>(null);
  const haloRef = useRef<THREE.MeshStandardMaterial>(null);
  const taskRingRef = useRef<THREE.Mesh>(null);
  const taskRingMatRef = useRef<THREE.MeshStandardMaterial>(null);
  const color = colorForStatus(status);
  const taskColor = colorForTaskState(latest, status);
  const taskLabel = labelForTaskState(latest, status);

  useFrame(({ clock }) => {
    if (!ref.current) return;
    const age = latest ? Date.now() - latest.receivedAt : 999999;
    const eventPulse = Math.max(0, 1 - age / 2100);
    const terminalPulse = latest?.taskState === "succeeded" ? Math.max(0, 1 - age / 1400) : 0;
    const executing = latest?.taskState === "executing";
    const activePulse = statusIsLit(status) ? 0.07 + Math.sin(clock.elapsedTime * 4) * 0.025 : 0;
    ref.current.scale.setScalar(1 + eventPulse * 0.65 + activePulse);
    if (matRef.current) {
      matRef.current.emissiveIntensity = statusIsLit(status) ? 0.3 + eventPulse * 1.35 : 0.03;
      matRef.current.opacity = status === SubsystemStatus.OFF ? 0.28 : 0.94;
    }
    if (haloRef.current) {
      haloRef.current.opacity = Math.min(0.42, eventPulse * 0.38);
      haloRef.current.emissiveIntensity = eventPulse * 2.2;
    }
    if (taskRingRef.current) {
      taskRingRef.current.rotation.z = executing ? clock.elapsedTime * 3.8 : clock.elapsedTime * 0.5;
      taskRingRef.current.scale.setScalar(1 + eventPulse * 0.36 + terminalPulse * 0.42);
    }
    if (taskRingMatRef.current) {
      taskRingMatRef.current.emissive.set(taskColor);
      taskRingMatRef.current.color.set(taskColor);
      taskRingMatRef.current.opacity = executing ? 0.82 : Math.max(eventPulse * 0.64, terminalPulse * 0.72);
      taskRingMatRef.current.emissiveIntensity = executing ? 1.8 : eventPulse * 2.6 + terminalPulse * 2.2;
    }
  });

  return (
    <group ref={ref} position={position}>
      <mesh castShadow>
        <sphereGeometry args={[size, 16, 10]} />
        <meshStandardMaterial
          ref={matRef}
          color={color}
          emissive={color}
          roughness={0.28}
          metalness={0.35}
          transparent
          opacity={status === SubsystemStatus.OFF ? 0.28 : 0.94}
        />
      </mesh>
      <mesh>
        <sphereGeometry args={[size * 1.85, 16, 10]} />
        <meshStandardMaterial
          ref={haloRef}
          color={color}
          emissive={color}
          transparent
          opacity={0}
          depthWrite={false}
        />
      </mesh>
      <mesh ref={taskRingRef} rotation={[Math.PI / 2, 0, 0]}>
        <torusGeometry args={[size * 1.52, size * 0.08, 8, 36]} />
        <meshStandardMaterial
          ref={taskRingMatRef}
          color={taskColor}
          emissive={taskColor}
          transparent
          opacity={0}
          depthWrite={false}
        />
      </mesh>
      {showTaskLabel && taskLabel && (
        <Text
          position={[0, size * 2.45, 0]}
          fontSize={Math.max(0.18, size * 0.58)}
          color={taskColor}
          anchorX="center"
          anchorY="middle"
          outlineWidth={0.012}
          outlineColor="#05060a"
        >
          {taskLabel}
        </Text>
      )}
    </group>
  );
}

function DeviceLabel({
  position,
  title,
  subtitle,
  status,
  fontSize = 0.74,
}: {
  position: Vec3;
  title: string;
  subtitle?: string;
  status: SubsystemStatus;
  fontSize?: number;
}) {
  const color = colorForStatus(status);

  return (
    <group position={position}>
      <Text
        fontSize={fontSize}
        color="#dfeaff"
        anchorX="center"
        anchorY="middle"
        outlineWidth={0.018}
        outlineColor="#05060a"
      >
        {title}
      </Text>
      {subtitle && (
        <Text
          position={[0, -fontSize * 0.92, 0]}
          fontSize={fontSize * 0.62}
          color={color}
          anchorX="center"
          anchorY="middle"
          outlineWidth={0.014}
          outlineColor="#05060a"
        >
          {subtitle}
        </Text>
      )}
    </group>
  );
}

function TaskStrip({
  position,
  latest,
  status,
  width = 3,
}: {
  position: Vec3;
  latest?: LifecycleServiceState;
  status: SubsystemStatus;
  width?: number;
}) {
  const ref = useRef<THREE.Mesh>(null);
  const matRef = useRef<THREE.MeshStandardMaterial>(null);
  const color = colorForTaskState(latest, status);

  useFrame(({ clock }) => {
    const age = latest ? Date.now() - latest.receivedAt : 999999;
    const pulse = Math.max(0, 1 - age / 2200);
    const executing = latest?.taskState === "executing";
    if (ref.current) {
      const sweep = executing ? 0.68 + Math.sin(clock.elapsedTime * 5.4) * 0.24 : 1;
      ref.current.scale.x = Math.max(0.12, sweep);
    }
    if (matRef.current) {
      matRef.current.color.set(color);
      matRef.current.emissive.set(color);
      matRef.current.opacity = latest ? 0.38 + pulse * 0.5 : 0.12;
      matRef.current.emissiveIntensity = executing ? 1.7 : 0.35 + pulse * 1.6;
    }
  });

  return (
    <mesh ref={ref} position={position}>
      <boxGeometry args={[width, 0.12, 0.16]} />
      <meshStandardMaterial ref={matRef} color={color} emissive={color} transparent opacity={0.18} depthWrite={false} />
    </mesh>
  );
}

function RadialMarker({
  radius,
  label,
  color,
  y,
}: {
  radius: number;
  label: string;
  color: string;
  y: number;
}) {
  return (
    <group>
      <mesh position={[TARGET_X, y, TARGET_Z]} rotation={[-Math.PI / 2, 0, 0]}>
        <ringGeometry args={[radius - 0.07, radius + 0.07, 160]} />
        <meshBasicMaterial color={color} transparent opacity={0.14} side={THREE.DoubleSide} />
      </mesh>
      <Text
        position={[TARGET_X + radius, y + 0.55, TARGET_Z]}
        fontSize={0.65}
        color={color}
        anchorX="center"
        anchorY="middle"
        outlineWidth={0.015}
        outlineColor="#05060a"
      >
        {label}
      </Text>
    </group>
  );
}

function BuildingShell() {
  const hw = BUILDING_WIDTH / 2;
  const hd = BUILDING_DEPTH / 2;

  return (
    <group>
      <mesh rotation={[-Math.PI / 2, 0, 0]} position={[0, FLOOR_Y - 0.04, 0]} receiveShadow>
        <planeGeometry args={[BUILDING_WIDTH * 1.18, BUILDING_DEPTH * 1.22]} />
        <meshStandardMaterial color="#181a20" roughness={0.9} />
      </mesh>
      <mesh rotation={[-Math.PI / 2, 0, 0]} position={[0, FLOOR_Y, 0]} receiveShadow>
        <planeGeometry args={[BUILDING_WIDTH, BUILDING_DEPTH]} />
        <meshStandardMaterial color="#24272e" roughness={0.84} metalness={0.08} />
      </mesh>

      <mesh position={[TARGET_X, FLOOR_Y + 0.02, TARGET_Z]} rotation={[-Math.PI / 2, 0, 0]} receiveShadow>
        <circleGeometry args={[38, 160]} />
        <meshStandardMaterial color="#20242b" roughness={0.86} metalness={0.08} />
      </mesh>
      <mesh position={[TARGET_X, FLOOR_Y + 0.04, TARGET_Z]} rotation={[-Math.PI / 2, 0, 0]} receiveShadow>
        <ringGeometry args={[7, 48, 160]} />
        <meshStandardMaterial color="#262b33" roughness={0.8} metalness={0.16} />
      </mesh>

      <RadialMarker radius={10.4} y={0.08} label="频率转换 60" color={colorForStatus(SubsystemStatus.READY)} />
      <RadialMarker radius={13.5} y={0.1} label="测量取样 60" color={colorForStatus(SubsystemStatus.COLLECTING)} />
      <RadialMarker radius={20.7} y={0.12} label="集中同步 60" color={colorForStatus(SubsystemStatus.ACTIVE)} />
      <RadialMarker radius={27} y={0.14} label="多程放大 10 组" color="#8fb8ff" />
      <RadialMarker radius={36.5} y={0.16} label="再生/双程放大 10 组" color="#d3b76f" />

      {[
        [0, 7, -hd, BUILDING_WIDTH, 14, 0.35],
        [0, 7, hd, BUILDING_WIDTH, 14, 0.35],
        [-hw, 7, 0, 0.35, 14, BUILDING_DEPTH],
        [hw, 7, 0, 0.35, 14, BUILDING_DEPTH],
      ].map(([x, y, z, sx, sy, sz], i) => (
        <mesh key={i} position={[x, y, z]} castShadow>
          <boxGeometry args={[sx, sy, sz]} />
          <meshStandardMaterial
            color="#343844"
            roughness={0.86}
            metalness={0.12}
            transparent
            opacity={0.15}
          />
        </mesh>
      ))}
    </group>
  );
}

function beamStatus(beam: BeamLineLayout, serviceStates: ServiceStates): {
  status: SubsystemStatus;
  color: string;
  lit: boolean;
} {
  const multipass = serviceStates[beamGroupService("multipass_amp", beam.groupNo)];
  const sync = serviceStates[beamLineService("sync", beam.beamNo)];
  const measurement = serviceStates[beamLineService("measurement_sample", beam.beamNo)];
  const frequency = serviceStates[beamLineService("frequency_conversion", beam.beamNo)];
  const status = frequency?.status ?? measurement?.status ?? sync?.status ?? multipass?.status ?? SubsystemStatus.OFF;
  return {
    status,
    color: colorForStatus(status),
    lit: statusIsLit(status),
  };
}

function BeamLineNetwork({ serviceStates }: { serviceStates: ServiceStates }) {
  return (
    <group>
      {GXLF_BEAM_GROUP_LAYOUTS.map((group) => {
        const preamp = serviceStates[beamGroupService("preamp", group.groupNo)];
        const multipass = serviceStates[beamGroupService("multipass_amp", group.groupNo)];
        const preampStatus = preamp?.status ?? SubsystemStatus.OFF;
        const multipassStatus = multipass?.status ?? SubsystemStatus.OFF;
        const preampColor = colorForStatus(preampStatus);
        const multipassColor = colorForStatus(multipassStatus);

        return (
          <group key={group.groupNo}>
            <group position={group.preampPosition}>
              <mesh castShadow>
                <boxGeometry args={[3.8, 2.6, 3.8]} />
                <meshStandardMaterial
                  color="#34303f"
                  emissive={preampColor}
                  emissiveIntensity={statusIsLit(preampStatus) ? 0.16 : 0.02}
                  roughness={0.42}
                  metalness={0.44}
                />
              </mesh>
              <StatusBeacon position={[0, 2.4, 0]} status={preampStatus} latest={preamp} size={0.36} showTaskLabel />
              <TaskStrip position={[0, 1.45, 1.96]} status={preampStatus} latest={preamp} width={2.8} />
            </group>

            <group position={group.multipassPosition}>
              <mesh castShadow>
                <boxGeometry args={[4.6, 3, 4.6]} />
                <meshStandardMaterial
                  color="#303846"
                  emissive={multipassColor}
                  emissiveIntensity={statusIsLit(multipassStatus) ? 0.18 : 0.02}
                  roughness={0.4}
                  metalness={0.48}
                />
              </mesh>
              <StatusBeacon position={[0, 2.7, 0]} status={multipassStatus} latest={multipass} size={0.4} showTaskLabel />
              <TaskStrip position={[0, 1.65, 2.36]} status={multipassStatus} latest={multipass} width={3.3} />
            </group>

            <DeviceLabel
              position={[
                Math.cos(group.angle) * 41.5,
                7.7,
                Math.sin(group.angle) * 41.5,
              ]}
              title={`BG${twoDigit(group.groupNo)}`}
              subtitle={`${BEAMS_PER_GROUP} 束线`}
              status={multipassStatus !== SubsystemStatus.OFF ? multipassStatus : preampStatus}
              fontSize={0.62}
            />
          </group>
        );
      })}

      {GXLF_BEAM_LAYOUTS.map((beam) => {
        const latest = latestServiceForIds(
          [
            beamGroupService("preamp", beam.groupNo),
            beamGroupService("multipass_amp", beam.groupNo),
            beamLineService("sync", beam.beamNo),
            beamLineService("measurement_sample", beam.beamNo),
            beamLineService("frequency_conversion", beam.beamNo),
          ],
          serviceStates
        );
        const { status, color, lit } = beamStatus(beam, serviceStates);
        const sync = serviceStates[beamLineService("sync", beam.beamNo)];
        const measurement = serviceStates[beamLineService("measurement_sample", beam.beamNo)];
        const frequency = serviceStates[beamLineService("frequency_conversion", beam.beamNo)];

        return (
          <group key={beam.beamNo}>
            <BeamSegment start={beam.outer} end={beam.preamp} radius={0.045} color={color} lit={lit} opacity={0.44} />
            <BeamSegment start={beam.preamp} end={beam.multipass} radius={0.052} color={color} lit={lit} opacity={0.6} />
            <BeamSegment start={beam.multipass} end={beam.frequencyConversion} radius={0.048} color={color} lit={lit} opacity={0.62} />
            <BeamSegment start={beam.frequencyConversion} end={beam.chamberPort} radius={0.052} color={color} lit={lit} opacity={0.75} />

            <StatusBeacon position={beam.sync} status={sync?.status ?? SubsystemStatus.OFF} latest={sync ?? latest} size={0.14} />
            <StatusBeacon position={beam.measurement} status={measurement?.status ?? SubsystemStatus.OFF} latest={measurement ?? latest} size={0.16} />

            <mesh position={beam.frequencyConversion} castShadow>
              <boxGeometry args={[0.58, 0.8, 0.58]} />
              <meshStandardMaterial
                color="#33404f"
                emissive={colorForStatus(frequency?.status ?? SubsystemStatus.OFF)}
                emissiveIntensity={frequency && statusIsLit(frequency.status) ? 0.26 : 0.02}
                roughness={0.28}
                metalness={0.52}
              />
            </mesh>

            {beam.localNo === 1 && (
              <Text
                position={[
                  Math.cos(beam.angle) * 49.5,
                  4.6,
                  Math.sin(beam.angle) * 49.5,
                ]}
                fontSize={0.45}
                color="#90a3bd"
                anchorX="center"
                anchorY="middle"
                outlineWidth={0.012}
                outlineColor="#05060a"
              >
                {`BL${twoDigit(beam.beamNo)}-${twoDigit(beam.beamNo + 5)}`}
              </Text>
            )}

            {status === SubsystemStatus.FIRING && (
              <BeamSegment start={beam.chamberPort} end={beam.targetPoint} radius={0.07} color="#b78cff" lit opacity={0.92} />
            )}
          </group>
        );
      })}
    </group>
  );
}

function TargetChamberComplex({ serviceStates }: { serviceStates: ServiceStates }) {
  const chamberRef = useRef<THREE.Mesh>(null);
  const chamberMaterialRef = useRef<THREE.MeshStandardMaterial>(null);
  const haloRef = useRef<THREE.Mesh>(null);
  const haloMaterialRef = useRef<THREE.MeshStandardMaterial>(null);
  const flashLightRef = useRef<THREE.PointLight>(null);
  const target = serviceStates["gxlf.vacuum_target.svc01"];
  const align = serviceStates["gxlf.target_alignment.svc01"];
  const diagnostics = serviceStates["gxlf.diagnostics.svc01"];
  const targetImpactStartedAt = useExperimentStore((s) => s.targetImpactStartedAt);
  const phase = useExperimentStore((s) => s.currentPhase);
  const targetStatus = target?.status ?? SubsystemStatus.OFF;
  const alignStatus = align?.status ?? SubsystemStatus.OFF;
  const diagStatus = diagnostics?.status ?? SubsystemStatus.OFF;
  const targetColor = colorForStatus(targetStatus);
  const isFiringPhase = phase === Phase.FIRING || phase === Phase.TARGET_IMPLOSION;

  useFrame(({ clock }) => {
    const eventAge = targetImpactStartedAt ? (Date.now() - targetImpactStartedAt) / 1000 : 999;
    const eventPulse = Math.max(0, 1 - eventAge / 3.2);
    const phasePulse = isFiringPhase ? 0.34 + Math.sin(clock.elapsedTime * 14) * 0.12 : 0;
    const pulse = Math.max(eventPulse, phasePulse);

    if (chamberRef.current) chamberRef.current.scale.setScalar(1 + pulse * 0.14);
    if (chamberMaterialRef.current) {
      chamberMaterialRef.current.color.lerp(new THREE.Color(pulse > 0 ? "#fff8df" : "#7b8495"), 0.18);
      chamberMaterialRef.current.emissive.set(pulse > 0 ? "#ffb347" : targetColor);
      chamberMaterialRef.current.emissiveIntensity = 0.04 + pulse * 3.6 + (statusIsLit(targetStatus) ? 0.12 : 0);
    }
    if (haloRef.current && haloMaterialRef.current) {
      haloRef.current.scale.setScalar(1 + pulse * (1.7 + eventAge * 0.5));
      haloMaterialRef.current.opacity = Math.min(0.72, pulse * 0.58);
      haloMaterialRef.current.emissiveIntensity = pulse * 4.6;
    }
    if (flashLightRef.current) flashLightRef.current.intensity = pulse * 520;
  });

  return (
    <group>
      <DeviceLabel
        position={[TARGET_X, TARGET_Y + 11.6, TARGET_Z]}
        title="真空靶室分系统"
        subtitle="中心靶室 / 靶定位 / 诊断端口"
        status={targetStatus}
        fontSize={0.82}
      />
      <mesh ref={chamberRef} position={TARGET_CHAMBER_CENTER} castShadow>
        <sphereGeometry args={[5.4, 56, 36]} />
        <meshStandardMaterial
          ref={chamberMaterialRef}
          color="#7b8495"
          emissive={targetColor}
          emissiveIntensity={statusIsLit(targetStatus) ? 0.22 : 0.03}
          roughness={0.24}
          metalness={0.78}
        />
      </mesh>
      <TaskStrip position={[TARGET_X, TARGET_Y + 5.8, TARGET_Z + 4.7]} status={targetStatus} latest={target} width={6.2} />
      <mesh ref={haloRef} position={TARGET_CHAMBER_CENTER}>
        <sphereGeometry args={[6.3, 48, 32]} />
        <meshStandardMaterial
          ref={haloMaterialRef}
          color="#fff1b8"
          emissive="#ffb347"
          emissiveIntensity={0.45}
          transparent
          opacity={statusIsLit(targetStatus) ? 0.11 : 0.032}
          depthWrite={false}
        />
      </mesh>
      <pointLight ref={flashLightRef} position={TARGET_CHAMBER_CENTER} color="#fff6d6" intensity={0} distance={75} decay={2} />

      {GXLF_BEAM_LAYOUTS.map((beam) => (
        <mesh key={beam.beamNo} position={beam.chamberPort} quaternion={quaternionBetween(beam.chamberPort, TARGET_CHAMBER_CENTER)}>
          <cylinderGeometry args={[0.18, 0.32, 1.15, 12]} />
          <meshStandardMaterial color="#4e5868" roughness={0.32} metalness={0.72} />
        </mesh>
      ))}

      <group position={[TARGET_X + 8.8, TARGET_Y + 0.2, TARGET_Z + 1.8]}>
        <mesh rotation={[Math.PI / 2, 0, 0]} castShadow>
          <cylinderGeometry args={[0.22, 0.22, 8.5, 12]} />
          <meshStandardMaterial
            color="#bcc6d8"
            emissive={colorForStatus(alignStatus)}
            emissiveIntensity={statusIsLit(alignStatus) ? 0.28 : 0.02}
            roughness={0.24}
            metalness={0.55}
          />
        </mesh>
        <StatusBeacon position={[0, 2.2, 0]} status={alignStatus} latest={align} size={0.45} showTaskLabel />
        <TaskStrip position={[0, 1.15, 0.42]} status={alignStatus} latest={align} width={2.4} />
      </group>
      <DeviceLabel
        position={[TARGET_X + 12.5, TARGET_Y + 6.4, TARGET_Z + 2.2]}
        title="靶瞄准定位系统"
        subtitle="靶定位 / 引导 / 撤离"
        status={alignStatus}
        fontSize={0.62}
      />

      {Array.from({ length: 12 }, (_, i) => {
        const angle = (i / 12) * Math.PI * 2 + Math.PI / 12;
        return (
          <mesh key={i} position={[Math.cos(angle) * 12.8, TARGET_Y - 3.8, Math.sin(angle) * 12.8]} castShadow>
            <boxGeometry args={[1.9, 3.4, 1.8]} />
            <meshStandardMaterial
              color="#2e3442"
              emissive={colorForStatus(diagStatus)}
              emissiveIntensity={statusIsLit(diagStatus) ? 0.16 : 0.01}
              roughness={0.45}
              metalness={0.48}
            />
          </mesh>
        );
      })}
      <DeviceLabel
        position={[TARGET_X - 14.5, TARGET_Y + 6.3, TARGET_Z - 5.2]}
        title="物理实验诊断分系统"
        subtitle="诊断准备 / 采集设置 / 后处理"
        status={diagStatus}
        fontSize={0.62}
      />
      <StatusBeacon position={[TARGET_X - 11.5, TARGET_Y + 2.5, TARGET_Z - 3.8]} status={diagStatus} latest={diagnostics} size={0.58} showTaskLabel />
      <TaskStrip position={[TARGET_X - 11.5, TARGET_Y + 1.25, TARGET_Z - 1.9]} status={diagStatus} latest={diagnostics} width={4.8} />
    </group>
  );
}

function FrontEndAndSupport({ serviceStates }: { serviceStates: ServiceStates }) {
  const seed = serviceStates["gxlf.seed_source.svc01"];
  const injector = serviceStates["gxlf.shg_injector.svc01"];
  const pump = serviceStates["gxlf.pump.svc01"];
  const switchDriver = serviceStates["gxlf.switch_driver.svc01"];
  const cooling = serviceStates["gxlf.cooling.svc01"];
  const seedStatus = seed?.status ?? SubsystemStatus.OFF;
  const injectorStatus = injector?.status ?? SubsystemStatus.OFF;
  const pumpStatus = pump?.status ?? SubsystemStatus.OFF;
  const switchStatus = switchDriver?.status ?? SubsystemStatus.OFF;
  const coolingStatus = cooling?.status ?? SubsystemStatus.STANDBY;

  return (
    <group>
      <DeviceLabel
        position={[-45, 7.5, -30]}
        title="光源注入区"
        subtitle="光纤种子源 / 二倍频宽带注入"
        status={seedStatus}
      />
      {[
        { x: -48, status: seedStatus, latest: seed, label: "种子源" },
        { x: -42, status: injectorStatus, latest: injector, label: "宽带注入" },
      ].map((item) => (
        <group key={item.label} position={[item.x, 3.1, -26]}>
          <mesh castShadow>
            <boxGeometry args={[4.4, 2.6, 3.4]} />
            <meshStandardMaterial
              color="#263241"
              emissive={colorForStatus(item.status)}
              emissiveIntensity={statusIsLit(item.status) ? 0.14 : 0.02}
              roughness={0.45}
              metalness={0.35}
            />
          </mesh>
          <StatusBeacon position={[0, 2.2, 0]} status={item.status} latest={item.latest} size={0.44} showTaskLabel />
          <TaskStrip position={[0, 1.42, 1.78]} status={item.status} latest={item.latest} width={3} />
        </group>
      ))}

      <DeviceLabel position={[47, 7.3, -28]} title="泵浦 / 开关驱动" subtitle="泵浦分系统 / 开关驱动源组件" status={pumpStatus} />
      {Array.from({ length: 14 }, (_, i) => {
        const x = 35 + (i % 7) * 4.1;
        const z = -31 + Math.floor(i / 7) * 4.4;
        return (
          <mesh key={i} position={[x, 2.2, z]} castShadow>
            <boxGeometry args={[2.6, 3.5, 2.0]} />
            <meshStandardMaterial
              color="#3b3338"
              emissive={colorForStatus(pumpStatus)}
              emissiveIntensity={statusIsLit(pumpStatus) ? 0.18 : 0.02}
              roughness={0.44}
              metalness={0.52}
            />
          </mesh>
        );
      })}
      <StatusBeacon position={[42, 5.4, -27]} status={pumpStatus} latest={pump} size={0.64} showTaskLabel />
      <StatusBeacon position={[52, 5.4, -18]} status={switchStatus} latest={switchDriver} size={0.58} showTaskLabel />
      <TaskStrip position={[42, 3.25, -24.8]} status={pumpStatus} latest={pump} width={6.8} />
      <TaskStrip position={[52, 3.25, -15.8]} status={switchStatus} latest={switchDriver} width={4.6} />

      <DeviceLabel position={[44, 7.0, 30]} title="冷却分系统" subtitle="冷却站 / 环形供回管路" status={coolingStatus} />
      <mesh position={[44, 2.4, 28]} castShadow>
        <boxGeometry args={[8, 3.8, 5.2]} />
        <meshStandardMaterial
          color="#26394a"
          emissive={colorForStatus(coolingStatus)}
          emissiveIntensity={statusIsLit(coolingStatus) ? 0.14 : 0.02}
          roughness={0.5}
          metalness={0.38}
        />
      </mesh>
      <StatusBeacon position={[44, 5.3, 28]} status={coolingStatus} latest={cooling} size={0.58} showTaskLabel />
      <TaskStrip position={[44, 4.48, 30.72]} status={coolingStatus} latest={cooling} width={6.3} />
      {[18, 31].map((radius) => (
        <mesh key={radius} position={[TARGET_X, 1.1, TARGET_Z]} rotation={[-Math.PI / 2, 0, 0]}>
          <ringGeometry args={[radius - 0.12, radius + 0.12, 128]} />
          <meshStandardMaterial
            color={colorForStatus(coolingStatus)}
            emissive={colorForStatus(coolingStatus)}
            emissiveIntensity={statusIsLit(coolingStatus) ? 0.18 : 0.02}
            transparent
            opacity={statusIsLit(coolingStatus) ? 0.46 : 0.16}
            side={THREE.DoubleSide}
          />
        </mesh>
      ))}
    </group>
  );
}

function SyncCabinets({ serviceStates }: { serviceStates: ServiceStates }) {
  const syncLatest = latestServiceForSystem("集中同步分系统", serviceStates);
  const syncStatus = syncLatest?.status ?? SubsystemStatus.OFF;
  const color = colorForStatus(syncStatus);

  return (
    <group>
      <DeviceLabel position={[-45, 7.5, 15]} title="集中同步分系统" subtitle="60 路时序通道，靠近束线环分发" status={syncStatus} />
      {Array.from({ length: BEAM_GROUP_COUNT }, (_, i) => (
        <group key={i} position={[-53 + (i % 5) * 4, 2.8, 12 + Math.floor(i / 5) * 4]}>
          <mesh castShadow>
            <boxGeometry args={[2.5, 4.2, 2.2]} />
            <meshStandardMaterial
              color={syncStatus === SubsystemStatus.OFF ? "#30333a" : "#26384a"}
              emissive={color}
              emissiveIntensity={statusIsLit(syncStatus) ? 0.16 : 0.02}
              roughness={0.5}
              metalness={0.45}
            />
          </mesh>
          {i === 0 && (
            <>
              <StatusBeacon position={[0, 3.1, 0]} status={syncStatus} latest={syncLatest} size={0.42} showTaskLabel />
              <TaskStrip position={[0, 2.0, 1.2]} status={syncStatus} latest={syncLatest} width={2.0} />
            </>
          )}
        </group>
      ))}
    </group>
  );
}

function SafetyAndControl({ serviceStates }: { serviceStates: ServiceStates }) {
  const env = serviceStates["gxlf.environment_control.svc01"];
  const door = serviceStates["gxlf.shield_door.svc01"];
  const personnel = serviceStates["gxlf.personnel_counter.svc01"];
  const doorLocked = useExperimentStore((s) => s.interlocks[InterlockId.SHIELDING_DOOR]);
  const personnelClear = useExperimentStore((s) => s.interlocks[InterlockId.PERSONNEL_CLEAR]);
  const warning = useExperimentStore((s) => s.visuals.warningLightsActive);
  const envStatus = env?.status ?? SubsystemStatus.OFF;
  const doorStatus = door?.status ?? (doorLocked ? SubsystemStatus.READY : SubsystemStatus.OFF);
  const personnelStatus = personnel?.status ?? (personnelClear ? SubsystemStatus.READY : SubsystemStatus.OFF);

  return (
    <group>
      <DeviceLabel position={[-48, 9.5, 30]} title="控制环境组件" subtitle="集中控制 / 运行环境 / 事件监控" status={envStatus} />
      <mesh position={[-48, 3.4, 27]} castShadow>
        <boxGeometry args={[8, 4.5, 10]} />
        <meshStandardMaterial color="#253142" roughness={0.45} metalness={0.4} />
      </mesh>
      {Array.from({ length: 6 }, (_, i) => (
        <mesh key={i} position={[-43.9, 4.4, 23.8 + i * 1.3]}>
          <boxGeometry args={[0.12, 1.1, 0.85]} />
          <meshStandardMaterial color="#0b1220" emissive={colorForStatus(envStatus)} emissiveIntensity={statusIsLit(envStatus) ? 0.48 : 0.08} />
        </mesh>
      ))}
      <StatusBeacon position={[-48, 6.1, 27]} status={envStatus} latest={env} size={0.58} showTaskLabel />
      <TaskStrip position={[-48, 5.85, 32.12]} status={envStatus} latest={env} width={5.8} />

      <DeviceLabel position={[0, 8.8, 40]} title="屏蔽门控制接口" subtitle={doorLocked ? "关闭 / 锁定" : "开启"} status={doorStatus} />
      <group position={[0, 3.6, 39.4]}>
        <mesh position={[-2.4, 0, 0]} castShadow>
          <boxGeometry args={[doorLocked ? 4.6 : 1.2, 6.7, 0.8]} />
          <meshStandardMaterial
            color="#59616f"
            emissive={colorForStatus(doorStatus)}
            emissiveIntensity={statusIsLit(doorStatus) ? 0.12 : 0.02}
            roughness={0.45}
            metalness={0.65}
          />
        </mesh>
        <mesh position={[2.4, 0, 0]} castShadow>
          <boxGeometry args={[doorLocked ? 4.6 : 1.2, 6.7, 0.8]} />
          <meshStandardMaterial
            color="#59616f"
            emissive={colorForStatus(doorStatus)}
            emissiveIntensity={statusIsLit(doorStatus) ? 0.12 : 0.02}
            roughness={0.45}
            metalness={0.65}
          />
        </mesh>
      </group>
      <StatusBeacon position={[0, 7.3, 39.4]} status={doorStatus} latest={door} size={0.48} showTaskLabel />
      <TaskStrip position={[0, 6.9, 39.9]} status={doorStatus} latest={door} width={5.2} />

      <StatusBeacon position={[-11, 6.4, 38]} status={personnelStatus} latest={personnel} size={0.48} showTaskLabel />
      <DeviceLabel position={[-12, 8.7, 40]} title="人员进出计数接口" subtitle={personnelClear ? "归零" : "待清场"} status={personnelStatus} fontSize={0.62} />

      {[-48, -24, 0, 24, 48].map((x, i) => (
        <StatusBeacon
          key={i}
          position={[x, 9.4, warning ? 37.5 : -37.5]}
          status={warning ? SubsystemStatus.FIRING : SubsystemStatus.OFF}
          size={0.42}
          latest={env}
        />
      ))}
    </group>
  );
}

function LatestEventCallout({ serviceStates }: { serviceStates: ServiceStates }) {
  const event = useExperimentStore((s) => s.lastLifecycleEvent);
  if (!event) return null;
  const service = event.service_id ? serviceStates[event.service_id] : undefined;
  const color = colorForStatus(service?.status ?? SubsystemStatus.ACTIVE);

  return (
    <Html position={[-70, 22, -48]} transform distanceFactor={82} style={{ pointerEvents: "none" }}>
      <div className="w-72 rounded border border-cyan-300/20 bg-black/75 p-3 text-xs text-white/75 shadow-xl">
        <div className="text-[10px] uppercase tracking-[0.18em] text-cyan-200/55">GXLF 生命周期事件</div>
        <div className="mt-2 font-mono text-[11px] text-white/45">#{event.seq} {event.node_id}</div>
        <div className="mt-1 truncate text-white/90">{event.system_name}</div>
        <div className="mt-1 truncate text-white/60">{event.command ?? event.event_type}</div>
        <div className="mt-1 font-mono" style={{ color }}>{service?.status ?? event.task_state_after ?? event.event_type}</div>
      </div>
    </Html>
  );
}

function ModelSummary({ serviceStates }: { serviceStates: ServiceStates }) {
  const eventCount = useExperimentStore((s) => s.lifecycleEvents.length);
  const stats = useExperimentStore((s) => s.lifecycleSystemStats);
  const expected = GXLF_DEVICE_SYSTEM_ORDER.reduce((acc, name) => acc + expectedCountForGXLFSystem(name), 0);
  const active = Object.values(serviceStates).filter((s) => statusIsLit(s.status)).length;
  const errors = Object.values(serviceStates).filter((s) => s.status === SubsystemStatus.ERROR).length;

  return (
    <Html position={[70, 22, 48]} transform distanceFactor={82} style={{ pointerEvents: "none" }}>
      <div className="w-72 rounded border border-white/15 bg-black/70 p-3 text-xs text-white/75 shadow-xl">
        <div className="text-[10px] uppercase tracking-[0.18em] text-white/45">模型覆盖</div>
        <div className="mt-2 grid grid-cols-2 gap-x-3 gap-y-1 font-mono text-[11px]">
          <span className="text-white/40">systems</span><span>{GXLF_DEVICE_SYSTEM_ORDER.length}</span>
          <span className="text-white/40">instances seen</span><span>{Object.keys(serviceStates).length}/{expected}</span>
          <span className="text-white/40">beam groups</span><span>{BEAM_GROUP_COUNT} x {BEAMS_PER_GROUP}</span>
          <span className="text-white/40">active/lit</span><span>{active}</span>
          <span className="text-white/40">errors</span><span>{errors}</span>
          <span className="text-white/40">events</span><span>{eventCount}</span>
          <span className="text-white/40">system stats</span><span>{Object.keys(stats).length}</span>
        </div>
      </div>
    </Html>
  );
}

function SystemCoverageLabels({ serviceStates }: { serviceStates: ServiceStates }) {
  const labels: Array<{ title: GXLFSystemName; position: Vec3 }> = [
    { title: "再生与双程放大组件", position: [-33, 8.1, -34] },
    { title: "多程放大组件", position: [30, 8.1, 24] },
    { title: "测量取样组件", position: [-17, 8.4, 14] },
    { title: "频率转换分系统", position: [13, 8.4, -13] },
  ];

  return (
    <group>
      {labels.map((item) => {
        const latest = latestServiceForSystem(item.title, serviceStates);
        const status = latest?.status ?? SubsystemStatus.OFF;
        return (
          <DeviceLabel
            key={item.title}
            position={item.position}
            title={item.title}
            subtitle={`${expectedCountForGXLFSystem(item.title)} 实例`}
            status={status}
            fontSize={0.66}
          />
        );
      })}
    </group>
  );
}

export const GXLFPhysicalFacility = memo(function GXLFPhysicalFacility() {
  const serviceStates = useExperimentStore((s) => s.lifecycleServiceStates);

  return (
    <group>
      <BuildingShell />
      <BeamLineNetwork serviceStates={serviceStates} />
      <TargetChamberComplex serviceStates={serviceStates} />
      <FrontEndAndSupport serviceStates={serviceStates} />
      <SyncCabinets serviceStates={serviceStates} />
      <SafetyAndControl serviceStates={serviceStates} />
      <SystemCoverageLabels serviceStates={serviceStates} />
      <RecentEventEffects serviceStates={serviceStates} />
      <LatestEventCallout serviceStates={serviceStates} />
      <ModelSummary serviceStates={serviceStates} />
    </group>
  );
});
