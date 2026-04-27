import {
  ReactFlow,
  useNodesState,
  useEdgesState,
  addEdge,
  Controls,
  Background,
  MiniMap,
  ReactFlowProvider,
  useReactFlow,
  useOnSelectionChange,
  type Connection,
  type Node,
  type Edge,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import { useCallback, useEffect, useRef, useState, type DragEvent } from 'react'
import { nodeTypes } from './nodes'
import { ShapePanel } from './ui/ShapePanel'
import { PropsPanel } from './ui/PropsPanel'
import { getShapeDef } from './lib/shape-defs'

const FILES = [
  { name: '靶场物理布局', file: '靶场物理布局.json' },
  { name: '激光光路全链路', file: '激光光路全链路.json' },
]

let nodeId = 0
const nextId = () => `gxlf_${++nodeId}`

function FlowEditor({ file }: { file: string }) {
  const [nodes, setNodes, onNodesChange] = useNodesState<Node>([])
  const [edges, setEdges, onEdgesChange] = useEdgesState<Edge>([])
  const { screenToFlowPosition } = useReactFlow()
  const timerRef = useRef<ReturnType<typeof setTimeout>>(undefined)
  const [selectedNodes, setSelectedNodes] = useState<Node[]>([])
  const [selectedEdges, setSelectedEdges] = useState<Edge[]>([])

  useOnSelectionChange({
    onChange: ({ nodes: sn, edges: se }) => {
      setSelectedNodes(sn)
      setSelectedEdges(se)
    },
  })

  useEffect(() => {
    fetch(`/api/files/${encodeURIComponent(file)}`)
      .then(r => r.ok ? r.json() : null)
      .then(data => {
        if (data?.nodes) {
          setNodes(data.nodes)
          setEdges(data.edges || [])
          const maxId = data.nodes.reduce((max: number, n: Node) => {
            const m = n.id.match(/gxlf_(\d+)/)
            return m ? Math.max(max, parseInt(m[1])) : max
          }, 0)
          nodeId = maxId
        }
      })
      .catch(() => {})
  }, [file, setNodes, setEdges])

  const save = useCallback((n: Node[], e: Edge[]) => {
    clearTimeout(timerRef.current)
    timerRef.current = setTimeout(() => {
      fetch(`/api/files/${encodeURIComponent(file)}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ nodes: n, edges: e }),
      }).catch(() => {})
    }, 1500)
  }, [file])

  const handleNodesChange: typeof onNodesChange = useCallback((changes) => {
    onNodesChange(changes)
    setNodes(cur => { save(cur, edges); return cur })
  }, [onNodesChange, setNodes, save, edges])

  const handleEdgesChange: typeof onEdgesChange = useCallback((changes) => {
    onEdgesChange(changes)
    setEdges(cur => { save(nodes, cur); return cur })
  }, [onEdgesChange, setEdges, save, nodes])

  const onConnect = useCallback((conn: Connection) => {
    setEdges(eds => {
      const next = addEdge(conn, eds)
      save(nodes, next)
      return next
    })
  }, [setEdges, save, nodes])

  const onDragOver = useCallback((e: DragEvent) => {
    e.preventDefault()
    e.dataTransfer.dropEffect = 'move'
  }, [])

  const onDrop = useCallback((e: DragEvent) => {
    e.preventDefault()
    const defId = e.dataTransfer.getData('application/gxlf-def-id')
    if (!defId) return
    const def = getShapeDef(defId)
    if (!def) return

    const position = screenToFlowPosition({ x: e.clientX, y: e.clientY })
    const newNode: Node = {
      id: nextId(),
      type: 'gxlf',
      position,
      data: { defId, labelOverride: '' },
    }
    setNodes(nds => {
      const next = [...nds, newNode]
      save(next, edges)
      return next
    })
  }, [screenToFlowPosition, setNodes, save, edges])

  const onNodeUpdate = useCallback((id: string, data: Record<string, unknown>) => {
    setNodes(nds => {
      const next = nds.map(n => n.id === id ? { ...n, data } : n)
      save(next, edges)
      return next
    })
  }, [setNodes, save, edges])

  const onEdgeUpdate = useCallback((id: string, data: Record<string, unknown>) => {
    setEdges(eds => {
      const next = eds.map(e => e.id === id ? { ...e, data, label: (data.label as string) || '' } : e)
      save(nodes, next)
      return next
    })
  }, [setEdges, save, nodes])

  return (
    <div style={{ display: 'flex', flex: 1, height: '100%' }}>
      <div style={{ flex: 1, position: 'relative' }}>
        <ReactFlow
          nodes={nodes}
          edges={edges}
          onNodesChange={handleNodesChange}
          onEdgesChange={handleEdgesChange}
          onConnect={onConnect}
          onDragOver={onDragOver}
          onDrop={onDrop}
          nodeTypes={nodeTypes}
          fitView
          deleteKeyCode={['Backspace', 'Delete']}
        >
          <Controls />
          <Background />
          <MiniMap />
        </ReactFlow>
      </div>
      <PropsPanel
        selectedNodes={selectedNodes}
        selectedEdges={selectedEdges}
        onNodeUpdate={onNodeUpdate}
        onEdgeUpdate={onEdgeUpdate}
      />
    </div>
  )
}

export default function App() {
  const [currentFile, setCurrentFile] = useState<string | null>(null)
  const [status, setStatus] = useState('选择文件开始编辑')
  const [key, setKey] = useState(0)

  const handleFileSelect = (file: string, name: string) => {
    setCurrentFile(file)
    setStatus(`编辑中: ${name}`)
    setKey(k => k + 1)
  }

  return (
    <div style={{ height: '100vh', display: 'flex', flexDirection: 'column', background: '#fff' }}>
      <header style={headerStyle}>
        <h1 style={{ fontSize: 16, fontWeight: 600, margin: 0 }}>GXLF 实验场景建模</h1>
        <div style={{ display: 'flex', gap: 8, marginLeft: 20 }}>
          {FILES.map(f => (
            <button
              key={f.file}
              style={{ ...fileBtnStyle, ...(currentFile === f.file ? fileBtnActiveStyle : {}) }}
              onClick={() => handleFileSelect(f.file, f.name)}
            >
              {f.name}
            </button>
          ))}
        </div>
        <span style={{ marginLeft: 'auto', fontSize: 12, color: '#888' }}>{status}</span>
      </header>
      <div style={{ flex: 1, display: 'flex', overflow: 'hidden' }}>
        {currentFile && <ShapePanel />}
        <div style={{ flex: 1, position: 'relative' }}>
          {currentFile ? (
            <ReactFlowProvider key={key}>
              <FlowEditor file={currentFile} />
            </ReactFlowProvider>
          ) : (
            <div style={{ height: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#999', fontSize: 14 }}>
              点击上方文件名打开编辑
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

const headerStyle: React.CSSProperties = {
  padding: '10px 20px',
  background: '#16213e',
  display: 'flex',
  alignItems: 'center',
  gap: 16,
  color: '#eee',
  zIndex: 100,
}

const fileBtnStyle: React.CSSProperties = {
  padding: '6px 14px',
  border: '1px solid #0f3460',
  borderRadius: 4,
  background: 'transparent',
  color: '#a8b2d1',
  cursor: 'pointer',
  fontSize: 13,
}

const fileBtnActiveStyle: React.CSSProperties = {
  background: '#e94560',
  borderColor: '#e94560',
  color: '#fff',
}
