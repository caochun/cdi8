import { memo, useMemo, useRef } from "react";
import { Html, Text } from "@react-three/drei";
import { useFrame } from "@react-three/fiber";
import * as THREE from "three";
import { BUILDING_DEPTH, BUILDING_WIDTH, FLOOR_Y, SUBSYSTEM_STATUS_COLORS, TARGET_CHAMBER_CENTER } from "@/lib/constants";
import { useExperimentStore } from "@/state/experimentStore";
import { expectedCountForGXLFSystem } from "@/state/gxlfSystemMap";
import { InterlockId, Phase, SubsystemStatus, type GXLFSystemName, type LifecycleServiceState, type Vec3 } from "@/types";

const STATUS_COLOR: Record<string, string> = {
  ...SUBSYSTEM_STATUS_COLORS,
  OFF: "#303038",
};

const BEAM_GROUP_COUNT = 10;
const BEAMS_PER_GROUP = 6;
const BEAM_COUNT = BEAM_GROUP_COUNT * BEAMS_PER_GROUP;
const [TARGET_X, TARGET_Y, TARGET_Z] = TARGET_CHAMBER_CENTER;

type ServiceStates = Record<string, LifecycleServiceState>;

interface BeamLayout {
  no: number;
  groupNo: number;
  localNo: number;
  z: number;
  y: number;
}

function colorForStatus(status: SubsystemStatus): string {
  return STATUS_COLOR[status] ?? STATUS_COLOR.OFF;
}

function statusIsLit(status: SubsystemStatus): boolean {
  return status !== SubsystemStatus.OFF && status !== SubsystemStatus.STANDBY;
}

function latestServiceForSystem(systemName: string, serviceStates: ServiceStates): LifecycleServiceState | undefined {
  return Object.values(serviceStates)
    .filter((state) => state.systemName === systemName)
    .sort((a, b) => b.seq - a.seq)[0];
}

function latestServiceForIds(serviceIds: string[], serviceStates: ServiceStates): LifecycleServiceState | undefined {
  return serviceIds
    .map((id) => serviceStates[id])
    .filter(Boolean)
    .sort((a, b) => b.seq - a.seq)[0];
}

function beamLayouts(): BeamLayout[] {
  const layouts: BeamLayout[] = [];
  for (let group = 1; group <= BEAM_GROUP_COUNT; group += 1) {
    const groupZ = -27 + (group - 1) * 6;
    for (let local = 1; local <= BEAMS_PER_GROUP; local += 1) {
      const no = (group - 1) * BEAMS_PER_GROUP + local;
      layouts.push({
        no,
        groupNo: group,
        localNo: local,
        z: groupZ + (local - 3.5) * 0.72,
        y: 3.3 + (local % 2) * 0.45,
      });
    }
  }
  return layouts;
}

function twoDigit(no: number): string {
  return String(no).padStart(2, "0");
}

function beamLineService(system: "sync" | "measurement_sample" | "frequency_conversion", no: number): string {
  return `gxlf.${system}.bl${twoDigit(no)}`;
}

function beamGroupService(system: "preamp" | "multipass_amp", no: number): string {
  return `gxlf.${system}.bg${twoDigit(no)}`;
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
    const shimmer = lit ? 0.6 + Math.sin(clock.elapsedTime * 5 + start[2]) * 0.18 : 0;
    materialRef.current.emissiveIntensity = shimmer;
    materialRef.current.opacity = lit ? opacity : 0.24;
  });

  return (
    <mesh position={center} quaternion={quaternion} castShadow>
      <cylinderGeometry args={[radius, radius, length, 8]} />
      <meshStandardMaterial
        ref={materialRef}
        color={lit ? color : "#4a4d55"}
        emissive={color}
        emissiveIntensity={0}
        roughness={0.32}
        metalness={0.5}
        transparent
        opacity={lit ? opacity : 0.24}
      />
    </mesh>
  );
}

function StatusBeacon({
  position,
  status,
  size = 0.55,
  latest,
}: {
  position: Vec3;
  status: SubsystemStatus;
  size?: number;
  latest?: LifecycleServiceState;
}) {
  const ref = useRef<THREE.Group>(null);
  const matRef = useRef<THREE.MeshStandardMaterial>(null);
  const color = colorForStatus(status);

  useFrame(({ clock }) => {
    if (!ref.current) return;
    const age = latest ? Date.now() - latest.receivedAt : 999999;
    const eventPulse = Math.max(0, 1 - age / 2000);
    const activePulse = statusIsLit(status) ? 0.08 + Math.sin(clock.elapsedTime * 4) * 0.03 : 0;
    ref.current.scale.setScalar(1 + eventPulse * 0.7 + activePulse);
    if (matRef.current) {
      matRef.current.emissiveIntensity = statusIsLit(status) ? 0.35 + eventPulse * 1.5 : 0.04;
      matRef.current.opacity = status === SubsystemStatus.OFF ? 0.32 : 0.95;
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
          opacity={status === SubsystemStatus.OFF ? 0.32 : 0.95}
        />
      </mesh>
    </group>
  );
}

function DeviceLabel({
  position,
  title,
  subtitle,
  status,
}: {
  position: Vec3;
  title: string;
  subtitle?: string;
  status: SubsystemStatus;
}) {
  const color = colorForStatus(status);

  return (
    <group position={position}>
      <Text
        fontSize={0.78}
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
          position={[0, -0.72, 0]}
          fontSize={0.5}
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

function SectionPlate({
  position,
  size,
  color,
  label,
}: {
  position: Vec3;
  size: [number, number];
  color: string;
  label: string;
}) {
  return (
    <group position={position}>
      <mesh rotation={[-Math.PI / 2, 0, 0]} receiveShadow>
        <planeGeometry args={size} />
        <meshStandardMaterial color={color} roughness={0.84} metalness={0.12} />
      </mesh>
      <Text
        position={[0, 0.05, -size[1] / 2 + 2]}
        rotation={[-Math.PI / 2, 0, 0]}
        fontSize={1.25}
        color="#96a9c8"
        anchorX="center"
        anchorY="middle"
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
      <mesh rotation={[-Math.PI / 2, 0, 0]} position={[0, FLOOR_Y - 0.02, 0]} receiveShadow>
        <planeGeometry args={[BUILDING_WIDTH * 1.25, BUILDING_DEPTH * 1.35]} />
        <meshStandardMaterial color="#181a20" roughness={0.9} />
      </mesh>
      <mesh rotation={[-Math.PI / 2, 0, 0]} position={[0, FLOOR_Y, 0]} receiveShadow>
        <planeGeometry args={[BUILDING_WIDTH, BUILDING_DEPTH]} />
        <meshStandardMaterial color="#24272e" roughness={0.82} metalness={0.08} />
      </mesh>

      <SectionPlate position={[-43, 0.015, 0]} size={[23, 66]} color="#1e2530" label="前端/预放区" />
      <SectionPlate position={[-8, 0.02, 0]} size={[48, 66]} color="#20242a" label="主放长光路大厅" />
      <SectionPlate position={[28, 0.025, 0]} size={[20, 66]} color="#1d2630" label="传输/转向区" />
      <SectionPlate position={[47, 0.03, 0]} size={[24, 66]} color="#251f2a" label="靶场区" />

      {[
        [0, 7, -hd, BUILDING_WIDTH, 14, 0.35],
        [0, 7, hd, BUILDING_WIDTH, 14, 0.35],
        [-hw, 7, 0, 0.35, 14, BUILDING_DEPTH],
        [hw, 7, 0, 0.35, 14, BUILDING_DEPTH],
      ].map(([x, y, z, sx, sy, sz], i) => (
        <mesh key={i} position={[x, y, z]} castShadow>
          <boxGeometry args={[sx, sy, sz]} />
          <meshStandardMaterial color="#343844" roughness={0.86} metalness={0.12} transparent opacity={0.18} />
        </mesh>
      ))}
    </group>
  );
}

function FrontEndArea({ serviceStates }: { serviceStates: ServiceStates }) {
  const seed = serviceStates["gxlf.seed_source.svc01"];
  const injector = serviceStates["gxlf.shg_injector.svc01"];
  const syncLatest = latestServiceForSystem("集中同步分系统", serviceStates);
  const seedStatus = seed?.status ?? SubsystemStatus.OFF;
  const injectorStatus = injector?.status ?? SubsystemStatus.OFF;
  const syncStatus = syncLatest?.status ?? SubsystemStatus.OFF;

  return (
    <group>
      <DeviceLabel position={[-52, 6.2, -21]} title="前端光源" subtitle="种子源 / 二倍频宽带注入" status={seedStatus} />
      <mesh position={[-52, 2.4, -23]} castShadow>
        <boxGeometry args={[5, 2.5, 3.2]} />
        <meshStandardMaterial color="#263241" roughness={0.45} metalness={0.35} />
      </mesh>
      <StatusBeacon position={[-52, 4.1, -23]} status={seedStatus} latest={seed} />
      <mesh position={[-45, 2.4, -23]} castShadow>
        <boxGeometry args={[5, 2.5, 3.2]} />
        <meshStandardMaterial color="#263241" roughness={0.45} metalness={0.35} />
      </mesh>
      <StatusBeacon position={[-45, 4.1, -23]} status={injectorStatus} latest={injector} />

      <DeviceLabel position={[-51, 6.2, 21]} title="集中同步机柜" subtitle="60 路时序通道" status={syncStatus} />
      {Array.from({ length: 10 }, (_, i) => (
        <mesh key={i} position={[-54 + (i % 5) * 2.1, 2.4, 18 + Math.floor(i / 5) * 3.2]} castShadow>
          <boxGeometry args={[1.2, 3.2, 1.7]} />
          <meshStandardMaterial
            color={syncStatus === SubsystemStatus.OFF ? "#30333a" : "#26384a"}
            emissive={colorForStatus(syncStatus)}
            emissiveIntensity={statusIsLit(syncStatus) ? 0.12 : 0}
            roughness={0.5}
            metalness={0.45}
          />
        </mesh>
      ))}
    </group>
  );
}

function PreAmplifierArea({ serviceStates, layouts }: { serviceStates: ServiceStates; layouts: BeamLayout[] }) {
  return (
    <group>
      <DeviceLabel position={[-39, 7, 0]} title="再生与双程放大组件" subtitle="10 个束组模块，每组 6 束" status={latestServiceForSystem("再生与双程放大组件", serviceStates)?.status ?? SubsystemStatus.OFF} />
      {Array.from({ length: BEAM_GROUP_COUNT }, (_, i) => {
        const no = i + 1;
        const groupZ = -27 + i * 6;
        const service = serviceStates[beamGroupService("preamp", no)];
        const status = service?.status ?? SubsystemStatus.OFF;
        const color = colorForStatus(status);
        return (
          <group key={no} position={[-39, 0, groupZ]}>
            <mesh position={[0, 2.6, 0]} castShadow>
              <boxGeometry args={[5.2, 3.6, 4.2]} />
              <meshStandardMaterial color="#303745" emissive={color} emissiveIntensity={statusIsLit(status) ? 0.12 : 0} roughness={0.44} metalness={0.38} />
            </mesh>
            <StatusBeacon position={[0, 5, 0]} status={status} latest={service} size={0.46} />
            <Text position={[0, 5.9, 0]} fontSize={0.48} color="#cbd8ee" anchorX="center" anchorY="middle">
              BG{twoDigit(no)}
            </Text>
            {layouts.filter((b) => b.groupNo === no).map((beam) => (
              <BeamSegment
                key={beam.no}
                start={[-50, beam.y, beam.z]}
                end={[-34, beam.y, beam.z]}
                radius={0.055}
                color={color}
                lit={statusIsLit(status)}
                opacity={0.55}
              />
            ))}
          </group>
        );
      })}
    </group>
  );
}

function MainAmplifierHall({ serviceStates, layouts }: { serviceStates: ServiceStates; layouts: BeamLayout[] }) {
  return (
    <group>
      <DeviceLabel position={[-5, 8, -34]} title="主放/多程放大长光路大厅" subtitle="60 条平行束线，10 组 × 6 束" status={latestServiceForSystem("多程放大组件", serviceStates)?.status ?? SubsystemStatus.OFF} />
      {layouts.map((beam) => {
        const groupService = serviceStates[beamGroupService("multipass_amp", beam.groupNo)];
        const sync = serviceStates[beamLineService("sync", beam.no)];
        const status = groupService?.status ?? sync?.status ?? SubsystemStatus.OFF;
        const color = colorForStatus(status);
        return (
          <group key={beam.no}>
            <BeamSegment
              start={[-34, beam.y, beam.z]}
              end={[20, beam.y, beam.z]}
              radius={0.045}
              color={color}
              lit={statusIsLit(status)}
              opacity={0.72}
            />
            {[-25, -13, -1, 11].map((x, i) => (
              <mesh key={`${beam.no}-${i}`} position={[x, beam.y, beam.z]} castShadow>
                <boxGeometry args={[1.3, 1.2, 0.5]} />
                <meshStandardMaterial color="#46505e" emissive={color} emissiveIntensity={statusIsLit(status) ? 0.08 : 0} roughness={0.38} metalness={0.45} />
              </mesh>
            ))}
          </group>
        );
      })}

      {Array.from({ length: BEAM_GROUP_COUNT }, (_, i) => {
        const no = i + 1;
        const z = -27 + i * 6;
        const service = serviceStates[beamGroupService("multipass_amp", no)];
        const status = service?.status ?? SubsystemStatus.OFF;
        return (
          <group key={no} position={[-4, 0, z]}>
            <mesh position={[0, 1.2, 0]} receiveShadow>
              <boxGeometry args={[56, 0.15, 4.9]} />
              <meshStandardMaterial color="#1b1f26" roughness={0.8} metalness={0.12} />
            </mesh>
            <StatusBeacon position={[25, 4.8, 0]} status={status} latest={service} size={0.38} />
          </group>
        );
      })}
    </group>
  );
}

function PowerCoolingArea({ serviceStates }: { serviceStates: ServiceStates }) {
  const pump = serviceStates["gxlf.pump.svc01"];
  const switchDriver = serviceStates["gxlf.switch_driver.svc01"];
  const cooling = serviceStates["gxlf.cooling.svc01"];
  const pumpStatus = pump?.status ?? SubsystemStatus.OFF;
  const switchStatus = switchDriver?.status ?? SubsystemStatus.OFF;
  const coolingStatus = cooling?.status ?? SubsystemStatus.OFF;
  const coolingLit = statusIsLit(coolingStatus);

  return (
    <group>
      <DeviceLabel position={[-5, 7.5, 33]} title="泵浦/开关/冷却辅助区" subtitle="电源柜、储能柜、冷却管路沿主放大厅布置" status={pumpStatus} />
      {Array.from({ length: 18 }, (_, i) => {
        const x = -31 + (i % 9) * 6.7;
        const z = i < 9 ? 30.5 : -34.5;
        return (
          <mesh key={i} position={[x, 2, z]} castShadow>
            <boxGeometry args={[3.5, 3.5, 2]} />
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
      <StatusBeacon position={[27, 5.2, 31]} status={pumpStatus} latest={pump} size={0.72} />

      {Array.from({ length: 8 }, (_, i) => (
        <mesh key={i} position={[24 + (i % 4) * 3.3, 2.3, -30 + Math.floor(i / 4) * 3.2]} castShadow>
          <boxGeometry args={[2.2, 4.1, 1.8]} />
          <meshStandardMaterial color="#2e3440" emissive={colorForStatus(switchStatus)} emissiveIntensity={statusIsLit(switchStatus) ? 0.2 : 0.01} roughness={0.42} metalness={0.55} />
        </mesh>
      ))}
      <DeviceLabel position={[29, 6.4, -33]} title="开关驱动源" subtitle="充电 / 触发准备 / 波形采集" status={switchStatus} />
      <StatusBeacon position={[36, 5.2, -31]} status={switchStatus} latest={switchDriver} size={0.62} />

      {[-29, 29].map((z) => (
        <BeamSegment
          key={z}
          start={[-47, 1.35, z]}
          end={[22, 1.35, z]}
          radius={0.12}
          color={colorForStatus(coolingStatus)}
          lit={coolingLit}
          opacity={0.7}
        />
      ))}
      <StatusBeacon position={[-47, 3.2, 31]} status={coolingStatus} latest={cooling} size={0.56} />
    </group>
  );
}

function TransportAndFinalOptics({ serviceStates, layouts }: { serviceStates: ServiceStates; layouts: BeamLayout[] }) {
  return (
    <group>
      <DeviceLabel position={[27, 7.8, 0]} title="传输/转向与最终光学" subtitle="平行束线折转进入靶室，频率转换与取样靠近靶室入口" status={latestServiceForSystem("频率转换分系统", serviceStates)?.status ?? SubsystemStatus.OFF} />
      {layouts.map((beam) => {
        const angle = (beam.no / BEAM_COUNT) * Math.PI * 2;
        const port: Vec3 = [
          TARGET_X - 6.2,
          TARGET_Y + Math.sin(angle * 1.7) * 4.6,
          TARGET_Z + Math.cos(angle) * 16,
        ];
        const final: Vec3 = [
          TARGET_X - 1.5,
          TARGET_Y + Math.sin(angle * 1.7) * 2.1,
          TARGET_Z + Math.cos(angle) * 6.1,
        ];
        const sync = serviceStates[beamLineService("sync", beam.no)];
        const measurement = serviceStates[beamLineService("measurement_sample", beam.no)];
        const frequency = serviceStates[beamLineService("frequency_conversion", beam.no)];
        const latest = latestServiceForIds(
          [
            beamLineService("sync", beam.no),
            beamLineService("measurement_sample", beam.no),
            beamLineService("frequency_conversion", beam.no),
          ],
          serviceStates
        );
        const status = frequency?.status ?? measurement?.status ?? sync?.status ?? SubsystemStatus.OFF;
        const color = colorForStatus(status);

        return (
          <group key={beam.no}>
            <BeamSegment
              start={[20, beam.y, beam.z]}
              end={port}
              radius={0.045}
              color={color}
              lit={statusIsLit(status)}
              opacity={0.64}
            />
            <BeamSegment
              start={port}
              end={final}
              radius={0.052}
              color={color}
              lit={statusIsLit(status)}
              opacity={0.75}
            />
            <mesh position={port} castShadow>
              <boxGeometry args={[0.9, 1.4, 0.9]} />
              <meshStandardMaterial color="#33404f" emissive={colorForStatus(frequency?.status ?? SubsystemStatus.OFF)} emissiveIntensity={frequency ? 0.3 : 0.02} roughness={0.28} metalness={0.5} />
            </mesh>
            <StatusBeacon position={[port[0], port[1] + 1.2, port[2]]} status={measurement?.status ?? SubsystemStatus.OFF} latest={measurement ?? latest} size={0.16} />
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
    const phasePulse = isFiringPhase ? 0.35 + Math.sin(clock.elapsedTime * 14) * 0.12 : 0;
    const pulse = Math.max(eventPulse, phasePulse);

    if (chamberRef.current) {
      chamberRef.current.scale.setScalar(1 + pulse * 0.14);
    }
    if (chamberMaterialRef.current) {
      chamberMaterialRef.current.color.lerp(new THREE.Color(pulse > 0 ? "#fff8df" : "#7b8495"), 0.18);
      chamberMaterialRef.current.emissive.set(pulse > 0 ? "#ffb347" : targetColor);
      chamberMaterialRef.current.emissiveIntensity = 0.04 + pulse * 3.8 + (statusIsLit(targetStatus) ? 0.12 : 0);
    }
    if (haloRef.current && haloMaterialRef.current) {
      const haloScale = 1 + pulse * (1.8 + eventAge * 0.55);
      haloRef.current.scale.setScalar(haloScale);
      haloMaterialRef.current.opacity = Math.min(0.78, pulse * 0.62);
      haloMaterialRef.current.emissiveIntensity = pulse * 4.8;
    }
    if (flashLightRef.current) {
      flashLightRef.current.intensity = pulse * 520;
    }
  });

  return (
    <group>
      <DeviceLabel position={[TARGET_X, TARGET_Y + 10.5, 0]} title="真空靶室与靶场" subtitle="球形靶室、靶定位、诊断端口、最终光学入口" status={targetStatus} />
      <mesh ref={chamberRef} position={[TARGET_X, TARGET_Y, TARGET_Z]} castShadow>
        <sphereGeometry args={[5.4, 48, 32]} />
        <meshStandardMaterial ref={chamberMaterialRef} color="#7b8495" emissive={targetColor} emissiveIntensity={statusIsLit(targetStatus) ? 0.22 : 0.03} roughness={0.24} metalness={0.76} />
      </mesh>
      <mesh ref={haloRef} position={[TARGET_X, TARGET_Y, TARGET_Z]}>
        <sphereGeometry args={[6.2, 48, 32]} />
        <meshStandardMaterial ref={haloMaterialRef} color="#fff1b8" emissive="#ffb347" emissiveIntensity={0.45} transparent opacity={statusIsLit(targetStatus) ? 0.12 : 0.035} depthWrite={false} />
      </mesh>
      <pointLight ref={flashLightRef} position={[TARGET_X, TARGET_Y, TARGET_Z]} color="#fff6d6" intensity={0} distance={75} decay={2} />
      {Array.from({ length: 24 }, (_, i) => {
        const angle = (i / 24) * Math.PI * 2;
        const y = TARGET_Y + Math.sin(angle * 1.7) * 2.2;
        const z = Math.cos(angle) * 6.1;
        return (
          <mesh key={i} position={[TARGET_X - 5.5, y, z]} rotation={[0, 0, angle]}>
            <cylinderGeometry args={[0.28, 0.36, 1.4, 12]} />
            <meshStandardMaterial color="#4e5868" roughness={0.32} metalness={0.72} />
          </mesh>
        );
      })}

      <group position={[TARGET_X + 7.5, TARGET_Y - 0.2, 0]}>
        <mesh rotation={[Math.PI / 2, 0, 0]} castShadow>
          <cylinderGeometry args={[0.22, 0.22, 9.5, 12]} />
          <meshStandardMaterial color="#bcc6d8" emissive={colorForStatus(alignStatus)} emissiveIntensity={statusIsLit(alignStatus) ? 0.28 : 0.02} roughness={0.24} metalness={0.55} />
        </mesh>
        <StatusBeacon position={[0, 2.4, 0]} status={alignStatus} latest={align} size={0.45} />
      </group>

      {Array.from({ length: 8 }, (_, i) => {
        const angle = (i / 8) * Math.PI * 2;
        return (
          <mesh key={i} position={[TARGET_X + 10 + Math.cos(angle) * 4.5, 3, Math.sin(angle) * 18]} castShadow>
            <boxGeometry args={[2.6, 4.2, 2.2]} />
            <meshStandardMaterial color="#2e3442" emissive={colorForStatus(diagStatus)} emissiveIntensity={statusIsLit(diagStatus) ? 0.18 : 0.01} roughness={0.45} metalness={0.48} />
          </mesh>
        );
      })}
      <DeviceLabel position={[52, 7.2, 22]} title="物理实验诊断" subtitle="诊断准备 / 采集设置 / 后处理" status={diagStatus} />
      <StatusBeacon position={[52, 5.7, 18]} status={diagStatus} latest={diagnostics} size={0.6} />
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
      <DeviceLabel position={[-54, 9, 0]} title="集中控制室" subtitle="流程引擎 / Tango 服务监控 / 事件总线" status={envStatus} />
      <mesh position={[-54, 2.8, 0]} castShadow>
        <boxGeometry args={[7, 4, 12]} />
        <meshStandardMaterial color="#253142" roughness={0.45} metalness={0.4} />
      </mesh>
      {Array.from({ length: 6 }, (_, i) => (
        <mesh key={i} position={[-50.4, 4.2, -4.5 + i * 1.8]}>
          <boxGeometry args={[0.12, 1.2, 1.1]} />
          <meshStandardMaterial color="#0b1220" emissive={colorForStatus(envStatus)} emissiveIntensity={statusIsLit(envStatus) ? 0.5 : 0.08} />
        </mesh>
      ))}
      <StatusBeacon position={[-54, 5.6, 0]} status={envStatus} latest={env} size={0.58} />

      <DeviceLabel position={[42, 8.5, 36]} title="靶场中子屏蔽门" subtitle={doorLocked ? "关闭 / 锁定" : "开启"} status={doorStatus} />
      <group position={[42, 3.5, 39]}>
        <mesh position={[-2.2, 0, 0]} castShadow>
          <boxGeometry args={[doorLocked ? 4.2 : 1.1, 6.5, 0.7]} />
          <meshStandardMaterial color="#59616f" emissive={colorForStatus(doorStatus)} emissiveIntensity={statusIsLit(doorStatus) ? 0.12 : 0.02} roughness={0.45} metalness={0.65} />
        </mesh>
        <mesh position={[2.2, 0, 0]} castShadow>
          <boxGeometry args={[doorLocked ? 4.2 : 1.1, 6.5, 0.7]} />
          <meshStandardMaterial color="#59616f" emissive={colorForStatus(doorStatus)} emissiveIntensity={statusIsLit(doorStatus) ? 0.12 : 0.02} roughness={0.45} metalness={0.65} />
        </mesh>
      </group>
      <StatusBeacon position={[35, 6.5, 38]} status={personnelStatus} latest={personnel} size={0.48} />
      <DeviceLabel position={[34, 8.4, 36]} title="人员计数" subtitle={personnelClear ? "归零" : "待清场"} status={personnelStatus} />

      {[-32, -10, 12, 34, 55].map((x, i) => (
        <StatusBeacon
          key={i}
          position={[x, 8.8, warning ? 37.5 : -37.5]}
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
    <Html position={[-55, 18, -35]} transform distanceFactor={72} style={{ pointerEvents: "none" }}>
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
  const systems: GXLFSystemName[] = [
    "光纤种子源组件",
    "二倍频宽带激光注入组件",
    "再生与双程放大组件",
    "多程放大组件",
    "泵浦分系统",
    "测量取样组件",
    "频率转换分系统",
    "靶瞄准定位系统",
    "真空靶室分系统",
    "物理实验诊断分系统",
    "集中同步分系统",
    "开关驱动源组件",
    "冷却分系统",
    "控制环境组件",
    "建安工程安防系统屏蔽门控制接口",
    "人员进出计数接口",
  ];
  const expected = systems.reduce((acc, name) => acc + expectedCountForGXLFSystem(name), 0);
  const active = Object.values(serviceStates).filter((s) => statusIsLit(s.status)).length;
  const errors = Object.values(serviceStates).filter((s) => s.status === SubsystemStatus.ERROR).length;

  return (
    <Html position={[55, 18, -35]} transform distanceFactor={72} style={{ pointerEvents: "none" }}>
      <div className="w-72 rounded border border-white/15 bg-black/70 p-3 text-xs text-white/75 shadow-xl">
        <div className="text-[10px] uppercase tracking-[0.18em] text-white/45">模型覆盖</div>
        <div className="mt-2 grid grid-cols-2 gap-x-3 gap-y-1 font-mono text-[11px]">
          <span className="text-white/40">systems</span><span>{systems.length}</span>
          <span className="text-white/40">instances seen</span><span>{Object.keys(serviceStates).length}/{expected}</span>
          <span className="text-white/40">active/lit</span><span>{active}</span>
          <span className="text-white/40">errors</span><span>{errors}</span>
          <span className="text-white/40">events</span><span>{eventCount}</span>
          <span className="text-white/40">system stats</span><span>{Object.keys(stats).length}</span>
        </div>
      </div>
    </Html>
  );
}

export const GXLFPhysicalFacility = memo(function GXLFPhysicalFacility() {
  const serviceStates = useExperimentStore((s) => s.lifecycleServiceStates);
  const layouts = useMemo(() => beamLayouts(), []);

  return (
    <group>
      <BuildingShell />
      <FrontEndArea serviceStates={serviceStates} />
      <PreAmplifierArea serviceStates={serviceStates} layouts={layouts} />
      <MainAmplifierHall serviceStates={serviceStates} layouts={layouts} />
      <PowerCoolingArea serviceStates={serviceStates} />
      <TransportAndFinalOptics serviceStates={serviceStates} layouts={layouts} />
      <TargetChamberComplex serviceStates={serviceStates} />
      <SafetyAndControl serviceStates={serviceStates} />
      <LatestEventCallout serviceStates={serviceStates} />
      <ModelSummary serviceStates={serviceStates} />
    </group>
  );
});
