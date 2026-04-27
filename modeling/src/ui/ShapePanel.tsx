import { useState, type DragEvent } from 'react'
import { SHAPE_GROUPS, type GxlfShapeDef } from '../lib/shape-defs'

export function ShapePanel() {
  const [collapsed, setCollapsed] = useState<Record<string, boolean>>({})
  const toggle = (id: string) => setCollapsed(prev => ({ ...prev, [id]: !prev[id] }))

  const onDragStart = (e: DragEvent, def: GxlfShapeDef) => {
    e.dataTransfer.setData('application/gxlf-def-id', def.id)
    e.dataTransfer.effectAllowed = 'move'
  }

  return (
    <div style={panelStyle}>
      <div style={headerStyle}>图元库</div>
      {SHAPE_GROUPS.map(group => (
        <div key={group.id}>
          <button style={groupBtnStyle} onClick={() => toggle(group.id)}>
            <span style={{ transform: collapsed[group.id] ? 'rotate(-90deg)' : 'rotate(0)', display: 'inline-block', transition: 'transform 0.15s' }}>▾</span>
            {' '}{group.title}
          </button>
          {!collapsed[group.id] && (
            <div style={gridStyle}>
              {group.shapes.map(def => (
                <div
                  key={def.id}
                  style={itemStyle}
                  draggable
                  onDragStart={(e) => onDragStart(e, def)}
                  title={def.label || def.id}
                >
                  <div style={swatchStyle(def)} />
                  <span style={labelStyle}>{def.label || def.id}</span>
                </div>
              ))}
            </div>
          )}
        </div>
      ))}
    </div>
  )
}

const panelStyle: React.CSSProperties = {
  width: 200,
  height: '100%',
  background: '#fafafa',
  borderRight: '1px solid #ddd',
  overflowY: 'auto',
  fontSize: 12,
  userSelect: 'none',
  flexShrink: 0,
}

const headerStyle: React.CSSProperties = {
  padding: '10px 12px',
  fontWeight: 700,
  fontSize: 13,
  borderBottom: '1px solid #eee',
  color: '#333',
}

const groupBtnStyle: React.CSSProperties = {
  width: '100%',
  padding: '8px 12px',
  border: 'none',
  borderBottom: '1px solid #eee',
  background: '#f0f0f0',
  cursor: 'pointer',
  textAlign: 'left',
  fontSize: 11,
  fontWeight: 600,
  color: '#555',
}

const gridStyle: React.CSSProperties = {
  display: 'flex',
  flexWrap: 'wrap',
  gap: 4,
  padding: '6px 8px',
}

const itemStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  alignItems: 'center',
  gap: 2,
  padding: '4px 6px',
  border: '1px solid #e0e0e0',
  borderRadius: 4,
  background: '#fff',
  cursor: 'grab',
  width: 84,
  fontSize: 9,
}

const swatchStyle = (def: GxlfShapeDef): React.CSSProperties => ({
  width: 32,
  height: 20,
  backgroundColor: def.fill,
  border: `1.5px solid ${def.stroke}`,
  borderRadius: def.shape === 'ellipse' ? '50%' : 3,
})

const labelStyle: React.CSSProperties = {
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
  maxWidth: 76,
  color: '#444',
}
