export interface GxlfShapeDef {
  id: string
  label: string
  sublabel?: string
  w: number
  h: number
  fill: string
  stroke: string
  shape: 'rect' | 'ellipse' | 'pill' | 'bar'
}

export interface GxlfShapeGroup {
  id: string
  title: string
  shapes: GxlfShapeDef[]
}

export const SHAPE_GROUPS: GxlfShapeGroup[] = [
  {
    id: 'diagnostics',
    title: 'GXLF 诊断设备',
    shapes: [
      { id: 'cmos-camera', label: 'CMOS相机', w: 100, h: 40, fill: '#d5e8d4', stroke: '#82b366', shape: 'rect' },
      { id: 'gated-camera', label: '门控相机', w: 100, h: 40, fill: '#d5e8d4', stroke: '#82b366', shape: 'rect' },
      { id: 'streak-camera', label: '条纹/分幅相机', w: 110, h: 40, fill: '#d5e8d4', stroke: '#82b366', shape: 'rect' },
      { id: 'oscilloscope', label: '示波器', w: 90, h: 40, fill: '#d5e8d4', stroke: '#82b366', shape: 'rect' },
      { id: 'spectrometer', label: '光谱仪', w: 90, h: 40, fill: '#d5e8d4', stroke: '#82b366', shape: 'rect' },
      { id: 'hv-power', label: '高压电源', w: 90, h: 40, fill: '#fff2cc', stroke: '#d6b656', shape: 'rect' },
      { id: 'photo-hv-power', label: '光电管高压电源', w: 120, h: 40, fill: '#fff2cc', stroke: '#d6b656', shape: 'rect' },
      { id: 'calibration', label: '标定模块', w: 90, h: 40, fill: '#e1d5e7', stroke: '#9673a6', shape: 'rect' },
      { id: 'motion-ctrl', label: '运动控制模块', w: 110, h: 40, fill: '#e1d5e7', stroke: '#9673a6', shape: 'rect' },
      { id: 'dim-platform', label: 'DIM搭载平台', w: 120, h: 50, fill: '#fff2cc', stroke: '#d6b656', shape: 'rect' },
      { id: 'synchronizer', label: '同步机', w: 80, h: 40, fill: '#e1d5e7', stroke: '#9673a6', shape: 'rect' },
      { id: 'framing-ctrl', label: '分幅电控模块', w: 110, h: 40, fill: '#e1d5e7', stroke: '#9673a6', shape: 'rect' },
    ],
  },
  {
    id: 'laser',
    title: 'GXLF 激光光路分系统',
    shapes: [
      { id: 'seed-source', label: '光纤种子源', sublabel: '(6束)', w: 140, h: 70, fill: '#d5e8d4', stroke: '#82b366', shape: 'rect' },
      { id: 'shg-injection', label: '二倍频宽带注入', sublabel: '(6束)', w: 150, h: 70, fill: '#d5e8d4', stroke: '#82b366', shape: 'rect' },
      { id: 'preamp', label: '再生与双程放大', sublabel: '(10束)', w: 150, h: 70, fill: '#d5e8d4', stroke: '#82b366', shape: 'rect' },
      { id: 'main-amp', label: '多程放大', sublabel: '(61束)', w: 150, h: 70, fill: '#fff2cc', stroke: '#d6b656', shape: 'rect' },
      { id: 'freq-conv', label: '频率转换', sublabel: '(61束)', w: 150, h: 70, fill: '#fff2cc', stroke: '#d6b656', shape: 'rect' },
      { id: 'transport-focus', label: '传输聚焦', w: 120, h: 50, fill: '#fff2cc', stroke: '#d6b656', shape: 'rect' },
      { id: 'switch-driver', label: '开关驱动源', sublabel: '(Pockels Cell)', w: 150, h: 50, fill: '#e1d5e7', stroke: '#9673a6', shape: 'rect' },
      { id: 'pump', label: '泵浦分系统', w: 120, h: 50, fill: '#e1d5e7', stroke: '#9673a6', shape: 'rect' },
      { id: 'timing-sync', label: '集中同步', sublabel: '(66套)', w: 140, h: 50, fill: '#e1d5e7', stroke: '#9673a6', shape: 'rect' },
      { id: 'measurement', label: '测量取样', sublabel: '(62套)', w: 130, h: 50, fill: '#e1d5e7', stroke: '#9673a6', shape: 'rect' },
      { id: 'cooling', label: '冷却/管路', w: 130, h: 50, fill: '#e1d5e7', stroke: '#9673a6', shape: 'rect' },
      { id: 'target-align', label: '靶瞄准定位', w: 140, h: 50, fill: '#e1d5e7', stroke: '#9673a6', shape: 'rect' },
      { id: 'vacuum-chamber-sub', label: '真空靶室分系统', w: 130, h: 50, fill: '#e1d5e7', stroke: '#9673a6', shape: 'rect' },
      { id: 'ld-target', label: 'LD靶分系统', w: 110, h: 50, fill: '#e1d5e7', stroke: '#9673a6', shape: 'rect' },
    ],
  },
  {
    id: 'infrastructure',
    title: 'GXLF 基础设施与控制',
    shapes: [
      { id: 'vacuum-chamber', label: '', w: 300, h: 250, fill: '#dae8fc', stroke: '#6c8ebf', shape: 'ellipse' },
      { id: 'target-ball', label: '靶球', sublabel: '(μm级靶丸)', w: 80, h: 60, fill: '#e1d5e7', stroke: '#9673a6', shape: 'ellipse' },
      { id: 'gate-valve', label: '闸板阀', w: 80, h: 35, fill: '#fff2cc', stroke: '#d6b656', shape: 'rect' },
      { id: 'vacuum-pump', label: '真空机组', w: 100, h: 40, fill: '#e1d5e7', stroke: '#9673a6', shape: 'rect' },
      { id: 'shield-door', label: '屏蔽门', w: 20, h: 80, fill: '#f8cecc', stroke: '#b85450', shape: 'rect' },
      { id: 'estop', label: '紧急停机', w: 60, h: 40, fill: '#f8cecc', stroke: '#b85450', shape: 'ellipse' },
      { id: 'safety-interlock', label: '安全联锁分系统', w: 130, h: 50, fill: '#f8cecc', stroke: '#b85450', shape: 'rect' },
      { id: 'central-ctrl', label: '集中管控系统', w: 180, h: 50, fill: '#dae8fc', stroke: '#6c8ebf', shape: 'rect' },
      { id: 'ops-task-ctrl', label: '运维任务管控组件', w: 140, h: 45, fill: '#dae8fc', stroke: '#6c8ebf', shape: 'rect' },
      { id: 'status-monitor', label: '状态监控组件', w: 120, h: 45, fill: '#dae8fc', stroke: '#6c8ebf', shape: 'rect' },
      { id: 'ctrl-env', label: '控制环境组件', w: 120, h: 45, fill: '#dae8fc', stroke: '#6c8ebf', shape: 'rect' },
      { id: 'diag-system', label: '物理实验诊断系统', w: 150, h: 50, fill: '#d5e8d4', stroke: '#82b366', shape: 'rect' },
      { id: 'tango-layer', label: 'Tango 中间件通信层', w: 400, h: 25, fill: '#e6d0de', stroke: '#996185', shape: 'bar' },
      { id: 'shield-zone', label: '', w: 400, h: 300, fill: '#f5f5f5', stroke: '#666666', shape: 'rect' },
      { id: 'diag-group', label: '', w: 250, h: 180, fill: '#d5e8d4', stroke: '#82b366', shape: 'rect' },
    ],
  },
]

export function getShapeDef(id: string): GxlfShapeDef | undefined {
  for (const group of SHAPE_GROUPS) {
    const found = group.shapes.find(s => s.id === id)
    if (found) return found
  }
  return undefined
}
