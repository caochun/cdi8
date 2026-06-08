import { TARGET_CHAMBER_CENTER } from "@/lib/constants";
import { SubsystemId, type GXLFSystemName, type Vec3 } from "@/types";

export type GXLFDeviceLayoutKind = "single" | "beam_group" | "beam_line";

export interface GXLFDeviceInstance {
  systemName: GXLFSystemName;
  subsystemId: SubsystemId;
  serviceId: string;
  label: string;
  instanceNo: number;
  kind: GXLFDeviceLayoutKind;
  position: Vec3;
  groupPosition: Vec3;
}

export interface BeamLineLayout {
  beamNo: number;
  groupNo: number;
  localNo: number;
  angle: number;
  outer: Vec3;
  preamp: Vec3;
  multipass: Vec3;
  sync: Vec3;
  measurement: Vec3;
  frequencyConversion: Vec3;
  chamberPort: Vec3;
  targetPoint: Vec3;
}

export interface BeamGroupLayout {
  groupNo: number;
  angle: number;
  preampPosition: Vec3;
  multipassPosition: Vec3;
  beamLines: BeamLineLayout[];
}

interface SingleDeviceSpec {
  systemName: GXLFSystemName;
  subsystemId: SubsystemId;
  serviceId: string;
  label: string;
  position: Vec3;
}

export const BEAM_GROUP_COUNT = 10;
export const BEAMS_PER_GROUP = 6;
export const GXLF_BEAM_COUNT = BEAM_GROUP_COUNT * BEAMS_PER_GROUP;
export const FACILITY_CENTER = TARGET_CHAMBER_CENTER;

const [CENTER_X, CENTER_Y, CENTER_Z] = TARGET_CHAMBER_CENTER;

function twoDigit(no: number): string {
  return String(no).padStart(2, "0");
}

function polarPoint(radius: number, angle: number, y: number): Vec3 {
  return [
    CENTER_X + Math.cos(angle) * radius,
    y,
    CENTER_Z + Math.sin(angle) * radius,
  ];
}

function radialOffset(point: Vec3, angle: number, distance: number): Vec3 {
  return [
    point[0] + Math.cos(angle) * distance,
    point[1],
    point[2] + Math.sin(angle) * distance,
  ];
}

function beamGroupAngle(index: number): number {
  return -Math.PI / 2 + (index / BEAM_GROUP_COUNT) * Math.PI * 2;
}

function beamLineAngle(groupIndex: number, localIndex: number): number {
  const groupAngle = beamGroupAngle(groupIndex);
  const spread = 0.19;
  return groupAngle + (localIndex - (BEAMS_PER_GROUP - 1) / 2) * spread;
}

export function createBeamLayouts(): BeamLineLayout[] {
  return Array.from({ length: GXLF_BEAM_COUNT }, (_, index) => {
    const beamNo = index + 1;
    const groupIndex = Math.floor(index / BEAMS_PER_GROUP);
    const localIndex = index % BEAMS_PER_GROUP;
    const groupNo = groupIndex + 1;
    const localNo = localIndex + 1;
    const angle = beamLineAngle(groupIndex, localIndex);
    const yJitter = (localIndex - 2.5) * 0.22;
    const chamberPort = polarPoint(7.2, angle, CENTER_Y + yJitter);
    const targetPoint: Vec3 = [CENTER_X, CENTER_Y + yJitter * 0.18, CENTER_Z];

    return {
      beamNo,
      groupNo,
      localNo,
      angle,
      outer: polarPoint(48, angle, 3.0 + yJitter),
      preamp: polarPoint(35, angle, 3.8 + yJitter),
      multipass: polarPoint(25.5, angle, 4.6 + yJitter),
      sync: polarPoint(20.7, angle, 5.25 + yJitter),
      measurement: polarPoint(13.5, angle, 5.7 + yJitter),
      frequencyConversion: polarPoint(10.4, angle, 6.0 + yJitter),
      chamberPort,
      targetPoint,
    };
  });
}

export const GXLF_BEAM_LAYOUTS: BeamLineLayout[] = createBeamLayouts();

export const GXLF_BEAM_GROUP_LAYOUTS: BeamGroupLayout[] = Array.from(
  { length: BEAM_GROUP_COUNT },
  (_, index) => {
    const groupNo = index + 1;
    const angle = beamGroupAngle(index);
    return {
      groupNo,
      angle,
      preampPosition: polarPoint(36.5, angle, 4.3),
      multipassPosition: polarPoint(27, angle, 5.0),
      beamLines: GXLF_BEAM_LAYOUTS.filter((beam) => beam.groupNo === groupNo),
    };
  }
);

const SINGLE_DEVICE_SPECS: SingleDeviceSpec[] = [
  {
    systemName: "真空靶室分系统",
    subsystemId: SubsystemId.TARGET_CHAMBER,
    serviceId: "gxlf.vacuum_target.svc01",
    label: "真空靶室",
    position: [CENTER_X, CENTER_Y + 5.8, CENTER_Z],
  },
  {
    systemName: "靶瞄准定位系统",
    subsystemId: SubsystemId.TARGET_ALIGN,
    serviceId: "gxlf.target_alignment.svc01",
    label: "靶瞄准定位",
    position: [CENTER_X + 9.2, CENTER_Y + 2.2, CENTER_Z + 1.8],
  },
  {
    systemName: "物理实验诊断分系统",
    subsystemId: SubsystemId.DIAGNOSTICS,
    serviceId: "gxlf.diagnostics.svc01",
    label: "物理实验诊断",
    position: [CENTER_X - 11.5, CENTER_Y + 2.1, CENTER_Z - 3.8],
  },
  {
    systemName: "光纤种子源组件",
    subsystemId: SubsystemId.SEED_SOURCE,
    serviceId: "gxlf.seed_source.svc01",
    label: "光纤种子源",
    position: [-48, 3.4, -26],
  },
  {
    systemName: "二倍频宽带激光注入组件",
    subsystemId: SubsystemId.BROADBAND_INJECT,
    serviceId: "gxlf.shg_injector.svc01",
    label: "二倍频宽带注入",
    position: [-42, 3.4, -26],
  },
  {
    systemName: "泵浦分系统",
    subsystemId: SubsystemId.PUMP,
    serviceId: "gxlf.pump.svc01",
    label: "泵浦分系统",
    position: [42, 3.6, -27],
  },
  {
    systemName: "开关驱动源组件",
    subsystemId: SubsystemId.SWITCH_DRIVER,
    serviceId: "gxlf.switch_driver.svc01",
    label: "开关驱动源",
    position: [52, 3.6, -18],
  },
  {
    systemName: "冷却分系统",
    subsystemId: SubsystemId.COOLING,
    serviceId: "gxlf.cooling.svc01",
    label: "冷却分系统",
    position: [44, 3.2, 28],
  },
  {
    systemName: "控制环境组件",
    subsystemId: SubsystemId.CONTROL_ENV,
    serviceId: "gxlf.environment_control.svc01",
    label: "控制环境",
    position: [-48, 4.2, 27],
  },
  {
    systemName: "建安工程安防系统屏蔽门控制接口",
    subsystemId: SubsystemId.SHIELDING_DOOR,
    serviceId: "gxlf.shield_door.svc01",
    label: "屏蔽门控制",
    position: [0, 5.8, 39],
  },
  {
    systemName: "人员进出计数接口",
    subsystemId: SubsystemId.PERSONNEL_COUNTER,
    serviceId: "gxlf.personnel_counter.svc01",
    label: "人员进出计数",
    position: [-11, 5.4, 38],
  },
];

function createBeamGroupDevices(): GXLFDeviceInstance[] {
  return GXLF_BEAM_GROUP_LAYOUTS.flatMap((group) => [
    {
      systemName: "再生与双程放大组件" as GXLFSystemName,
      subsystemId: SubsystemId.PRE_AMPLIFIER,
      serviceId: `gxlf.preamp.bg${twoDigit(group.groupNo)}`,
      label: `再生/双程 BG${twoDigit(group.groupNo)}`,
      instanceNo: group.groupNo,
      kind: "beam_group" as const,
      position: group.preampPosition,
      groupPosition: radialOffset(group.preampPosition, group.angle, 2.2),
    },
    {
      systemName: "多程放大组件" as GXLFSystemName,
      subsystemId: SubsystemId.MULTI_PASS_AMP,
      serviceId: `gxlf.multipass_amp.bg${twoDigit(group.groupNo)}`,
      label: `多程放大 BG${twoDigit(group.groupNo)}`,
      instanceNo: group.groupNo,
      kind: "beam_group" as const,
      position: group.multipassPosition,
      groupPosition: radialOffset(group.multipassPosition, group.angle, 1.8),
    },
  ]);
}

function beamLinePositionForSystem(
  system: "sync" | "measurement_sample" | "frequency_conversion",
  beam: BeamLineLayout
): Vec3 {
  if (system === "sync") return beam.sync;
  if (system === "measurement_sample") return beam.measurement;
  return beam.frequencyConversion;
}

function createBeamLineDevices(): GXLFDeviceInstance[] {
  const specs = [
    {
      systemName: "集中同步分系统" as GXLFSystemName,
      subsystemId: SubsystemId.SYNC,
      system: "sync" as const,
      prefix: "gxlf.sync",
      label: "同步",
    },
    {
      systemName: "测量取样组件" as GXLFSystemName,
      subsystemId: SubsystemId.MEASUREMENT,
      system: "measurement_sample" as const,
      prefix: "gxlf.measurement_sample",
      label: "测量取样",
    },
    {
      systemName: "频率转换分系统" as GXLFSystemName,
      subsystemId: SubsystemId.FREQUENCY_CONV,
      system: "frequency_conversion" as const,
      prefix: "gxlf.frequency_conversion",
      label: "频率转换",
    },
  ];

  return specs.flatMap((spec) =>
    GXLF_BEAM_LAYOUTS.map((beam) => ({
      systemName: spec.systemName,
      subsystemId: spec.subsystemId,
      serviceId: `${spec.prefix}.bl${twoDigit(beam.beamNo)}`,
      label: `${spec.label} BL${twoDigit(beam.beamNo)}`,
      instanceNo: beam.beamNo,
      kind: "beam_line" as const,
      position: beamLinePositionForSystem(spec.system, beam),
      groupPosition: polarPoint(16, beam.angle, 6.5),
    }))
  );
}

function createSingleDevices(): GXLFDeviceInstance[] {
  return SINGLE_DEVICE_SPECS.map((spec) => ({
    systemName: spec.systemName,
    subsystemId: spec.subsystemId,
    serviceId: spec.serviceId,
    label: spec.label,
    instanceNo: 1,
    kind: "single",
    position: spec.position,
    groupPosition: spec.position,
  }));
}

export const GXLF_DEVICE_INSTANCES: GXLFDeviceInstance[] = [
  ...createSingleDevices(),
  ...createBeamGroupDevices(),
  ...createBeamLineDevices(),
];

export const GXLF_DEVICE_SYSTEM_ORDER: GXLFSystemName[] = [
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
