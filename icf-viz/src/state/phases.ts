import {
  Phase,
  SubsystemId,
  SubsystemStatus,
  InterlockId,
  type PhaseConfig,
} from "@/types";

const S = SubsystemStatus;
const I = InterlockId;
const Sub = SubsystemId;

export const PHASE_ORDER: Phase[] = [
  Phase.IDLE,
  Phase.PARAM_DISPATCH,
  Phase.PARALLEL_STARTUP,
  Phase.CLEARANCE_LOCKDOWN,
  Phase.ENERGY_ALIGNMENT,
  Phase.READINESS_CHECK,
  Phase.FINAL_PREP,
  Phase.CHARGING,
  Phase.COUNTDOWN,
  Phase.FIRING,
  Phase.TARGET_IMPLOSION,
  Phase.DATA_COLLECTION,
  Phase.POST_PROCESS,
  Phase.COMPLETE,
];

export const PHASES: Record<Phase, PhaseConfig> = {
  [Phase.IDLE]: {
    id: Phase.IDLE,
    label: "Standby",
    labelZh: "待机",
    description: "设施处于待机状态，各分系统软件在线，冷却系统运转。总控室内操作人员完成发射任务创建。",
    compressedDuration: Infinity,
    camera: "cinematic",
    subsystemChanges: {},
    interlockChanges: {},
    visuals: {
      shieldingDoorsOpen: true,
      coolingFlowing: true,
      bloomIntensity: 0.2,
    },
  },
  [Phase.PARAM_DISPATCH]: {
    id: Phase.PARAM_DISPATCH,
    label: "Parameter Dispatch",
    labelZh: "参数下发",
    description:
      "总控向全部15类分系统广播「参数下发」指令，携带发次编号。各系统逐一返回「收到」，从数据库拉取本发次配置参数。",
    compressedDuration: 3,
    camera: "controlRoom",
    subsystemChanges: {
      [Sub.SEED_SOURCE]: S.STANDBY,
      [Sub.BROADBAND_INJECT]: S.STANDBY,
      [Sub.PRE_AMPLIFIER]: S.STANDBY,
      [Sub.MULTI_PASS_AMP]: S.STANDBY,
      [Sub.PUMP]: S.STANDBY,
      [Sub.FREQUENCY_CONV]: S.STANDBY,
      [Sub.TARGET_ALIGN]: S.STANDBY,
      [Sub.TARGET_CHAMBER]: S.STANDBY,
      [Sub.DIAGNOSTICS]: S.STANDBY,
      [Sub.SYNC]: S.STANDBY,
      [Sub.SWITCH_DRIVER]: S.STANDBY,
      [Sub.MEASUREMENT]: S.STANDBY,
      [Sub.SAFETY]: S.STANDBY,
      [Sub.CONTROL_ENV]: S.STANDBY,
    },
    interlockChanges: {},
    visuals: {
      shieldingDoorsOpen: true,
      bloomIntensity: 0.2,
    },
  },
  [Phase.PARALLEL_STARTUP]: {
    id: Phase.PARALLEL_STARTUP,
    label: "Parallel Startup",
    labelZh: "多系统并行启动",
    description:
      "种子源出光（10分钟稳定）、预放大器泵浦唤醒（8分钟）、靶瞄预定位、测量切换、控制环境开始清场广播。",
    compressedDuration: 30,
    camera: "overview",
    subsystemChanges: {
      [Sub.SEED_SOURCE]: S.WARMING,
      [Sub.BROADBAND_INJECT]: S.WARMING,
      [Sub.PRE_AMPLIFIER]: S.WARMING,
      [Sub.SYNC]: S.ACTIVE,
      [Sub.TARGET_ALIGN]: S.WARMING,
      [Sub.MEASUREMENT]: S.WARMING,
      [Sub.CONTROL_ENV]: S.ACTIVE,
      [Sub.COOLING]: S.ACTIVE,
    },
    interlockChanges: {},
    visuals: {
      shieldingDoorsOpen: true,
      coolingFlowing: true,
      bloomIntensity: 0.25,
    },
  },
  [Phase.CLEARANCE_LOCKDOWN]: {
    id: Phase.CLEARANCE_LOCKDOWN,
    label: "Clearance & Lockdown",
    labelZh: "清场与锁门",
    description:
      "广播持续喊话，人员撤离，人员计数归零。屏蔽门关闭锁定，风淋门锁住。安全联锁正式生效。",
    compressedDuration: 20,
    camera: "overview",
    subsystemChanges: {
      [Sub.SAFETY]: S.ACTIVE,
      [Sub.SEED_SOURCE]: S.ACTIVE,
      [Sub.BROADBAND_INJECT]: S.ACTIVE,
      [Sub.PRE_AMPLIFIER]: S.ACTIVE,
    },
    interlockChanges: {
      [I.PERSONNEL_CLEAR]: true,
      [I.SHIELDING_DOOR]: true,
      [I.VACUUM_NOMINAL]: true,
    },
    visuals: {
      shieldingDoorsOpen: false,
      warningLightsActive: true,
      warningLightsColor: "amber",
      bloomIntensity: 0.3,
    },
  },
  [Phase.ENERGY_ALIGNMENT]: {
    id: Phase.ENERGY_ALIGNMENT,
    label: "Energy Loop & Alignment",
    labelZh: "能量闭环与光路准直",
    description:
      "预放大器完成能量粗闭环和精闭环。多程放大系统光路准直（10分钟）。靶瞄系统完成光束引导。诊断设备进入待命。",
    compressedDuration: 25,
    camera: "beamPath",
    subsystemChanges: {
      [Sub.MULTI_PASS_AMP]: S.ACTIVE,
      [Sub.TARGET_ALIGN]: S.ACTIVE,
      [Sub.DIAGNOSTICS]: S.WARMING,
      [Sub.MEASUREMENT]: S.ACTIVE,
    },
    interlockChanges: {
      [I.LASER_ALIGNED]: true,
      [I.TARGET_POSITIONED]: true,
    },
    visuals: {
      laserBeamsVisible: true,
      laserBeamsIntensity: 0.1,
      shieldingDoorsOpen: false,
      warningLightsActive: true,
      warningLightsColor: "amber",
      bloomIntensity: 0.3,
    },
  },
  [Phase.READINESS_CHECK]: {
    id: Phase.READINESS_CHECK,
    label: "Readiness Check",
    labelZh: "发射准备完成",
    description:
      "总控屏幕上9项readiness flag逐一由灰变绿：诊断准备、测量准备、靶瞄就绪、光路准直、种子出光、预放闭环、服务正常、安全联锁、泵浦准备。全部亮绿——发射准备全部完成。",
    compressedDuration: 15,
    camera: "controlRoom",
    subsystemChanges: {
      [Sub.DIAGNOSTICS]: S.READY,
      [Sub.PUMP]: S.READY,
      [Sub.FREQUENCY_CONV]: S.READY,
      [Sub.SWITCH_DRIVER]: S.READY,
    },
    interlockChanges: {
      [I.DIAGNOSTICS_ARMED]: true,
      [I.TIMING_LOCKED]: true,
    },
    visuals: {
      laserBeamsVisible: true,
      laserBeamsIntensity: 0.15,
      warningLightsActive: true,
      warningLightsColor: "amber",
      bloomIntensity: 0.3,
    },
  },
  [Phase.FINAL_PREP]: {
    id: Phase.FINAL_PREP,
    label: "Final Preparation",
    labelZh: "正式发射准备",
    description:
      "6条前置链路同时触发：诊断参数设置、频率转换晶体位姿、泵浦充电准备、预放单次切换、测量切单次、开关驱动源充电。警灯亮起，警示音乐响起。",
    compressedDuration: 15,
    camera: "overview",
    subsystemChanges: {
      [Sub.FREQUENCY_CONV]: S.ACTIVE,
      [Sub.PUMP]: S.ACTIVE,
      [Sub.SWITCH_DRIVER]: S.ACTIVE,
      [Sub.DIAGNOSTICS]: S.ACTIVE,
    },
    interlockChanges: {
      [I.MASTER_INTERLOCK]: true,
    },
    visuals: {
      laserBeamsVisible: true,
      laserBeamsIntensity: 0.15,
      warningLightsActive: true,
      warningLightsColor: "red",
      bloomIntensity: 0.4,
    },
  },
  [Phase.CHARGING]: {
    id: Phase.CHARGING,
    label: "Capacitor Charging",
    labelZh: "发射充电",
    description:
      "泵浦系统巨型电容器组开始充电——数百千焦电能在75秒内储入。这是最危险的窗口之一。充电完成后警示音乐停止，警灯继续闪烁。",
    compressedDuration: 10,
    camera: "capacitorBank",
    subsystemChanges: {
      [Sub.PUMP]: S.FIRING,
    },
    interlockChanges: {
      [I.CAPACITORS_CHARGED]: true,
    },
    visuals: {
      capacitorChargeLevel: 1.0,
      warningLightsActive: true,
      warningLightsColor: "red",
      bloomIntensity: 0.5,
    },
  },
  [Phase.COUNTDOWN]: {
    id: Phase.COUNTDOWN,
    label: "Countdown",
    labelZh: "同步触发倒计时",
    description: "总控下发「同步触发」指令。集中同步系统接管控制。5... 4... 3... 2... 1...",
    compressedDuration: 5,
    camera: "targetChamber",
    subsystemChanges: {
      [Sub.SYNC]: S.FIRING,
      [Sub.SWITCH_DRIVER]: S.FIRING,
    },
    interlockChanges: {},
    visuals: {
      capacitorChargeLevel: 1.0,
      warningLightsActive: true,
      warningLightsColor: "red",
      bloomIntensity: 0.6,
    },
  },
  [Phase.FIRING]: {
    id: Phase.FIRING,
    label: "FIRING",
    labelZh: "正式触发",
    description:
      "泵浦氙灯阵列全部闪亮，数千支氙灯同时点燃。开关驱动源同步释放高压脉冲。60束激光脉冲经多程放大、频率转换，从60个方向同时射入靶室。",
    compressedDuration: 4,
    camera: "amplifierHall",
    subsystemChanges: {
      [Sub.PUMP]: S.FIRING,
      [Sub.MULTI_PASS_AMP]: S.FIRING,
      [Sub.FREQUENCY_CONV]: S.FIRING,
      [Sub.SEED_SOURCE]: S.FIRING,
      [Sub.PRE_AMPLIFIER]: S.FIRING,
    },
    interlockChanges: {},
    visuals: {
      laserBeamsVisible: true,
      laserBeamsIntensity: 1.0,
      xenonFlashActive: true,
      warningLightsActive: true,
      warningLightsColor: "red",
      bloomIntensity: 2.5,
    },
  },
  [Phase.TARGET_IMPLOSION]: {
    id: Phase.TARGET_IMPLOSION,
    label: "Target Implosion",
    labelZh: "靶丸爆缩",
    description:
      "靶丸表面在皮秒内被加热到数千万度，向内爆缩。靶丸中心瞬间达到聚变条件，释放中子和X射线。正式发射完成。",
    compressedDuration: 3,
    camera: "targetChamber",
    subsystemChanges: {},
    interlockChanges: {},
    visuals: {
      laserBeamsVisible: true,
      laserBeamsIntensity: 0.8,
      targetGlowIntensity: 1.0,
      bloomIntensity: 3.0,
    },
  },
  [Phase.DATA_COLLECTION]: {
    id: Phase.DATA_COLLECTION,
    label: "Data Collection",
    labelZh: "数据采集",
    description:
      "物理诊断探测器记录中子产额、X射线光谱、压缩成像。种子源和预放采集激光参数。泵浦数据采集。测量系统分析存库。",
    compressedDuration: 12,
    camera: "overview",
    subsystemChanges: {
      [Sub.DIAGNOSTICS]: S.COLLECTING,
      [Sub.MEASUREMENT]: S.COLLECTING,
      [Sub.SEED_SOURCE]: S.COLLECTING,
      [Sub.PRE_AMPLIFIER]: S.COLLECTING,
      [Sub.PUMP]: S.COLLECTING,
      [Sub.MULTI_PASS_AMP]: S.ACTIVE,
      [Sub.FREQUENCY_CONV]: S.STANDBY,
      [Sub.SWITCH_DRIVER]: S.COLLECTING,
    },
    interlockChanges: {},
    visuals: {
      laserBeamsVisible: false,
      xenonFlashActive: false,
      targetGlowIntensity: 0.1,
      warningLightsActive: true,
      warningLightsColor: "amber",
      bloomIntensity: 0.3,
    },
  },
  [Phase.POST_PROCESS]: {
    id: Phase.POST_PROCESS,
    label: "Post-Processing",
    labelZh: "后处理",
    description:
      "预电离清理流程执行。各系统逐步进入待机。冷却系统片放吹扫启动。预电离完成后警灯关闭。",
    compressedDuration: 12,
    camera: "overview",
    subsystemChanges: {
      [Sub.DIAGNOSTICS]: S.STANDBY,
      [Sub.MEASUREMENT]: S.STANDBY,
      [Sub.SEED_SOURCE]: S.STANDBY,
      [Sub.PRE_AMPLIFIER]: S.STANDBY,
      [Sub.PUMP]: S.STANDBY,
      [Sub.MULTI_PASS_AMP]: S.STANDBY,
      [Sub.SWITCH_DRIVER]: S.STANDBY,
      [Sub.TARGET_ALIGN]: S.STANDBY,
      [Sub.SYNC]: S.STANDBY,
    },
    interlockChanges: {},
    visuals: {
      warningLightsActive: false,
      coolingFlowing: true,
      bloomIntensity: 0.25,
    },
  },
  [Phase.COMPLETE]: {
    id: Phase.COMPLETE,
    label: "Complete",
    labelZh: "实验结束",
    description:
      "所有后处理完成。屏蔽门开启，安全联锁解除。广播播报：「本次实验结束。」实验人员可重新进入靶场。",
    compressedDuration: Infinity,
    camera: "cinematic",
    subsystemChanges: {
      [Sub.SAFETY]: S.STANDBY,
      [Sub.CONTROL_ENV]: S.STANDBY,
      [Sub.BROADBAND_INJECT]: S.OFF,
      [Sub.FREQUENCY_CONV]: S.OFF,
      [Sub.TARGET_CHAMBER]: S.STANDBY,
    },
    interlockChanges: {
      [I.SHIELDING_DOOR]: false,
      [I.MASTER_INTERLOCK]: false,
      [I.CAPACITORS_CHARGED]: false,
    },
    visuals: {
      shieldingDoorsOpen: true,
      bloomIntensity: 0.2,
      coolingFlowing: true,
    },
  },
};

export function getNextPhase(current: Phase): Phase | null {
  const idx = PHASE_ORDER.indexOf(current);
  if (idx < 0 || idx >= PHASE_ORDER.length - 1) return null;
  return PHASE_ORDER[idx + 1];
}

export function getPrevPhase(current: Phase): Phase | null {
  const idx = PHASE_ORDER.indexOf(current);
  if (idx <= 0) return null;
  return PHASE_ORDER[idx - 1];
}

export function getPhaseIndex(phase: Phase): number {
  return PHASE_ORDER.indexOf(phase);
}

export function getTotalCompressedDuration(): number {
  let total = 0;
  for (const p of PHASE_ORDER) {
    const d = PHASES[p].compressedDuration;
    if (d !== Infinity) total += d;
  }
  return total;
}
