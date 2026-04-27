import { Handle, Position, type NodeProps } from '@xyflow/react'
import { getShapeDef } from '../lib/shape-defs'

export type GxlfNodeData = {
  defId: string
  labelOverride?: string
}

export function GxlfNode({ data }: NodeProps) {
  const d = data as GxlfNodeData
  const def = getShapeDef(d.defId)
  if (!def) {
    return <div style={{ padding: 8, background: '#eee', border: '1px solid #ccc', fontSize: 10 }}>?</div>
  }

  const label = d.labelOverride || def.label
  const isEllipse = def.shape === 'ellipse'
  const isBar = def.shape === 'bar'
  const isDashed = def.id === 'shield-zone' || def.id === 'diag-group'

  return (
    <>
      <Handle type="target" position={Position.Top} style={handleStyle} />
      <Handle type="target" position={Position.Left} style={handleStyle} />
      <div style={{
        width: def.w,
        height: def.h,
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        backgroundColor: def.fill,
        border: `2px solid ${def.stroke}`,
        borderStyle: isDashed ? 'dashed' : 'solid',
        borderRadius: isEllipse ? '50%' : isBar ? '2px' : '6px',
        fontSize: isBar ? 10 : 11,
        fontWeight: 600,
        color: '#333',
        overflow: 'hidden',
        textAlign: 'center',
        padding: '2px 4px',
        boxSizing: 'border-box',
      }}>
        {label && <span>{label}</span>}
        {def.sublabel && <span style={{ fontSize: 9, fontWeight: 400, color: '#666' }}>{def.sublabel}</span>}
      </div>
      <Handle type="source" position={Position.Bottom} style={handleStyle} />
      <Handle type="source" position={Position.Right} style={handleStyle} />
    </>
  )
}

const handleStyle: React.CSSProperties = {
  width: 6,
  height: 6,
  background: '#888',
  border: '1px solid #555',
}
