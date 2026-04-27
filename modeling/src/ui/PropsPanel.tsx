import type { Node, Edge } from '@xyflow/react'
import { getShapeDef, SHAPE_GROUPS } from '../lib/shape-defs'

interface Props {
  selectedNodes: Node[]
  selectedEdges: Edge[]
  onNodeUpdate: (id: string, data: Record<string, unknown>) => void
  onEdgeUpdate: (id: string, data: Record<string, unknown>) => void
}

export function PropsPanel({ selectedNodes, selectedEdges, onNodeUpdate, onEdgeUpdate }: Props) {
  if (selectedNodes.length === 0 && selectedEdges.length === 0) {
    return (
      <div style={panelStyle}>
        <div style={emptyStyle}>选中节点或连线查看属性</div>
      </div>
    )
  }

  if (selectedNodes.length === 1) {
    const node = selectedNodes[0]
    const data = node.data as { defId?: string; labelOverride?: string; note?: string }
    const def = data.defId ? getShapeDef(data.defId) : undefined
    const groupName = def ? SHAPE_GROUPS.find(g => g.shapes.some(s => s.id === def.id))?.title : undefined

    return (
      <div style={panelStyle}>
        <div style={titleStyle}>节点属性</div>
        <Field label="ID" value={node.id} />
        {def && <Field label="类型" value={def.label || def.id} />}
        {groupName && <Field label="分组" value={groupName} />}
        <EditField
          label="标签"
          value={data.labelOverride || ''}
          placeholder={def?.label || ''}
          onChange={v => onNodeUpdate(node.id, { ...data, labelOverride: v })}
        />
        <EditField
          label="备注"
          value={data.note || ''}
          placeholder="添加备注..."
          onChange={v => onNodeUpdate(node.id, { ...data, note: v })}
        />
        <div style={sectionStyle}>
          <span style={fieldLabel}>位置</span>
          <span style={fieldValue}>x: {Math.round(node.position.x)}, y: {Math.round(node.position.y)}</span>
        </div>
        {def && (
          <div style={sectionStyle}>
            <span style={fieldLabel}>尺寸</span>
            <span style={fieldValue}>{def.w} × {def.h}</span>
          </div>
        )}
        {def && (
          <div style={{ ...sectionStyle, alignItems: 'center' }}>
            <span style={fieldLabel}>颜色</span>
            <div style={{ display: 'flex', gap: 4, alignItems: 'center' }}>
              <div style={{ width: 16, height: 16, backgroundColor: def.fill, border: `1.5px solid ${def.stroke}`, borderRadius: 2 }} />
              <span style={{ fontSize: 10, color: '#888' }}>{def.fill}</span>
            </div>
          </div>
        )}
      </div>
    )
  }

  if (selectedEdges.length === 1) {
    const edge = selectedEdges[0]
    const data = (edge.data || {}) as { label?: string; note?: string }

    return (
      <div style={panelStyle}>
        <div style={titleStyle}>连线属性</div>
        <Field label="ID" value={edge.id} />
        <Field label="起点" value={edge.source} />
        <Field label="终点" value={edge.target} />
        <EditField
          label="标签"
          value={edge.label as string || ''}
          placeholder="连线标签..."
          onChange={v => onEdgeUpdate(edge.id, { ...data, label: v })}
        />
        <EditField
          label="备注"
          value={data.note || ''}
          placeholder="添加备注..."
          onChange={v => onEdgeUpdate(edge.id, { ...data, note: v })}
        />
      </div>
    )
  }

  return (
    <div style={panelStyle}>
      <div style={titleStyle}>多选</div>
      <div style={sectionStyle}>
        <span style={fieldValue}>已选中 {selectedNodes.length} 个节点, {selectedEdges.length} 条连线</span>
      </div>
    </div>
  )
}

function Field({ label, value }: { label: string; value: string }) {
  return (
    <div style={sectionStyle}>
      <span style={fieldLabel}>{label}</span>
      <span style={fieldValue}>{value}</span>
    </div>
  )
}

function EditField({ label, value, placeholder, onChange }: {
  label: string; value: string; placeholder: string; onChange: (v: string) => void
}) {
  return (
    <div style={sectionStyle}>
      <span style={fieldLabel}>{label}</span>
      <input
        style={inputStyle}
        value={value}
        placeholder={placeholder}
        onChange={e => onChange(e.target.value)}
      />
    </div>
  )
}

const panelStyle: React.CSSProperties = {
  width: 220,
  height: '100%',
  background: '#fafafa',
  borderLeft: '1px solid #ddd',
  overflowY: 'auto',
  fontSize: 12,
  flexShrink: 0,
}

const emptyStyle: React.CSSProperties = {
  padding: 16,
  color: '#999',
  fontSize: 12,
  textAlign: 'center',
}

const titleStyle: React.CSSProperties = {
  padding: '10px 12px',
  fontWeight: 700,
  fontSize: 13,
  borderBottom: '1px solid #eee',
  color: '#333',
}

const sectionStyle: React.CSSProperties = {
  padding: '6px 12px',
  display: 'flex',
  flexDirection: 'column',
  gap: 2,
  borderBottom: '1px solid #f0f0f0',
}

const fieldLabel: React.CSSProperties = {
  fontSize: 10,
  color: '#888',
  fontWeight: 600,
}

const fieldValue: React.CSSProperties = {
  fontSize: 11,
  color: '#333',
  wordBreak: 'break-all',
}

const inputStyle: React.CSSProperties = {
  fontSize: 11,
  padding: '4px 6px',
  border: '1px solid #ddd',
  borderRadius: 3,
  outline: 'none',
  width: '100%',
  boxSizing: 'border-box',
}
