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

interface SingleDeviceSpec {
  systemName: GXLFSystemName;
  subsystemId: SubsystemId;
  serviceId: string;
  label: string;
  position: Vec3;
}

const SINGLE_DEVICE_SPECS: SingleDeviceSpec[] = [
  {
    systemName: "光纤种子源组件",
    subsystemId: SubsystemId.SEED_SOURCE,
    serviceId: "gxlf.seed_source.svc01",
    label: "种子源",
    position: [-44, 2.6, -24],
  },
  {
    systemName: "二倍频宽带激光注入组件",
    subsystemId: SubsystemId.BROADBAND_INJECT,
    serviceId: "gxlf.shg_injector.svc01",
    label: "宽带注入",
    position: [-36, 2.6, -24],
  },
  {
    systemName: "泵浦分系统",
    subsystemId: SubsystemId.PUMP,
    serviceId: "gxlf.pump.svc01",
    label: "泵浦",
    position: [42, 3, -23],
  },
  {
    systemName: "靶瞄准定位系统",
    subsystemId: SubsystemId.TARGET_ALIGN,
    serviceId: "gxlf.target_alignment.svc01",
    label: "靶定位",
    position: [-8, 9.2, 0],
  },
  {
    systemName: "真空靶室分系统",
    subsystemId: SubsystemId.TARGET_CHAMBER,
    serviceId: "gxlf.vacuum_target.svc01",
    label: "真空靶室",
    position: [0, 11.8, 0],
  },
  {
    systemName: "物理实验诊断分系统",
    subsystemId: SubsystemId.DIAGNOSTICS,
    serviceId: "gxlf.diagnostics.svc01",
    label: "物理诊断",
    position: [31, 4, 19],
  },
  {
    systemName: "开关驱动源组件",
    subsystemId: SubsystemId.SWITCH_DRIVER,
    serviceId: "gxlf.switch_driver.svc01",
    label: "开关驱动",
    position: [42, 3, -10],
  },
  {
    systemName: "冷却分系统",
    subsystemId: SubsystemId.COOLING,
    serviceId: "gxlf.cooling.svc01",
    label: "冷却",
    position: [-45, 3, 24],
  },
  {
    systemName: "控制环境组件",
    subsystemId: SubsystemId.CONTROL_ENV,
    serviceId: "gxlf.environment_control.svc01",
    label: "控制环境",
    position: [-43, 4.2, 14],
  },
  {
    systemName: "建安工程安防系统屏蔽门控制接口",
    subsystemId: SubsystemId.SHIELDING_DOOR,
    serviceId: "gxlf.shield_door.svc01",
    label: "屏蔽门",
    position: [0, 6, 39],
  },
  {
    systemName: "人员进出计数接口",
    subsystemId: SubsystemId.PERSONNEL_COUNTER,
    serviceId: "gxlf.personnel_counter.svc01",
    label: "人员计数",
    position: [-9, 5.4, 38],
  },
];

function ringPosition(index: number, count: number, radius: number, y: number, phase = 0): Vec3 {
  const angle = (index / count) * Math.PI * 2 + phase;
  return [Math.cos(angle) * radius, y, Math.sin(angle) * radius];
}

function linePosition(index: number, count: number, radius: number, y: number, phase = 0): Vec3 {
  const angle = (index / count) * Math.PI * 2 + phase;
  return [Math.cos(angle) * radius, y, Math.sin(angle) * radius];
}

function twoDigit(no: number): string {
  return String(no).padStart(2, "0");
}

function createBeamGroupDevices(): GXLFDeviceInstance[] {
  const preamp = Array.from({ length: 10 }, (_, i) => {
    const no = i + 1;
    return {
      systemName: "再生与双程放大组件" as GXLFSystemName,
      subsystemId: SubsystemId.PRE_AMPLIFIER,
      serviceId: `gxlf.preamp.bg${twoDigit(no)}`,
      label: `预放 BG${twoDigit(no)}`,
      instanceNo: no,
      kind: "beam_group" as const,
      position: ringPosition(i, 10, 20, 4.2, Math.PI / 10),
      groupPosition: [-18, 0, -8] as Vec3,
    };
  });

  const multipass = Array.from({ length: 10 }, (_, i) => {
    const no = i + 1;
    return {
      systemName: "多程放大组件" as GXLFSystemName,
      subsystemId: SubsystemId.MULTI_PASS_AMP,
      serviceId: `gxlf.multipass_amp.bg${twoDigit(no)}`,
      label: `多程 BG${twoDigit(no)}`,
      instanceNo: no,
      kind: "beam_group" as const,
      position: ringPosition(i, 10, 25.5, 4.8, Math.PI / 10),
      groupPosition: [0, 0, 0] as Vec3,
    };
  });

  return [...preamp, ...multipass];
}

function createBeamLineDevices(): GXLFDeviceInstance[] {
  const specs = [
    {
      systemName: "集中同步分系统" as GXLFSystemName,
      subsystemId: SubsystemId.SYNC,
      prefix: "gxlf.sync",
      label: "同步",
      radius: 30.5,
      y: 3.2,
      phase: 0,
    },
    {
      systemName: "测量取样组件" as GXLFSystemName,
      subsystemId: SubsystemId.MEASUREMENT,
      prefix: "gxlf.measurement_sample",
      label: "取样",
      radius: 33,
      y: 3.9,
      phase: Math.PI / 60,
    },
    {
      systemName: "频率转换分系统" as GXLFSystemName,
      subsystemId: SubsystemId.FREQUENCY_CONV,
      prefix: "gxlf.frequency_conversion",
      label: "倍频",
      radius: 35.5,
      y: 4.6,
      phase: Math.PI / 30,
    },
  ];

  return specs.flatMap((spec) =>
    Array.from({ length: 60 }, (_, i) => {
      const no = i + 1;
      return {
        systemName: spec.systemName,
        subsystemId: spec.subsystemId,
        serviceId: `${spec.prefix}.bl${twoDigit(no)}`,
        label: `${spec.label} BL${twoDigit(no)}`,
        instanceNo: no,
        kind: "beam_line" as const,
        position: linePosition(i, 60, spec.radius, spec.y, spec.phase),
        groupPosition: [0, 0, 0] as Vec3,
      };
    })
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
