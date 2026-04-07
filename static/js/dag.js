/**
 * dag.js — DAG 渲染器
 *
 * 使用 dagre.js（CDN）计算布局，使用原生 SVG API 渲染节点和边。
 * 支持实时更新步骤状态（颜色/图标），无需重新 layout。
 */

const STEP_COLORS = {
    pending:     { fill: "#141414", stroke: "#333", text: "#666" },
    running:     { fill: "#1a0e00", stroke: "#ff9500", text: "#ff9500" },
    success:     { fill: "#001500", stroke: "#39ff14", text: "#39ff14" },
    failed:      { fill: "#2a0000", stroke: "#ff2d2d", text: "#ff2d2d" },
    soft_failed: { fill: "#1a0a00", stroke: "#f97316", text: "#f97316" },
};

const NODE_W = 130;
const NODE_H = 44;
const ARROW_SIZE = 7;

class DAGRenderer {
    constructor(svgId) {
        this.svg = document.getElementById(svgId);
        this._graph = null;
        this._nodeStates = {};  // name → state string
        this._steps = [];
    }

    /** 从后端加载 DAG 结构并完整渲染。*/
    async loadPhase(phase) {
        const res = await fetch(`/api/workflow/${phase}`);
        const data = await res.json();
        this._steps = data.steps;
        this._nodeStates = {};
        for (const s of this._steps) {
            this._nodeStates[s.name] = "pending";
        }
        this._layout();
        this._render();
    }

    /** 更新单个步骤的状态并重绘该节点。*/
    setStepState(name, state) {
        if (!(name in this._nodeStates)) return;
        this._nodeStates[name] = state;
        this._updateNode(name);
    }

    /** 将所有步骤重置为 pending（新发次开始时调用）。*/
    resetAll() {
        for (const name of Object.keys(this._nodeStates)) {
            this._nodeStates[name] = "pending";
            this._updateNode(name);
        }
    }

    // ── 私有 ──────────────────────────────────────────────────

    _layout() {
        const g = new dagre.graphlib.Graph();
        g.setGraph({ rankdir: "TB", ranksep: 36, nodesep: 20, marginx: 24, marginy: 24 });
        g.setDefaultEdgeLabel(() => ({}));

        for (const s of this._steps) {
            g.setNode(s.name, { width: NODE_W, height: NODE_H, label: s.label || s.name, soft: s.soft });
        }
        for (const s of this._steps) {
            for (const dep of s.deps) {
                g.setEdge(dep, s.name);
            }
        }
        dagre.layout(g);
        this._graph = g;
    }

    _render() {
        // 清空 SVG
        while (this.svg.firstChild) this.svg.removeChild(this.svg.firstChild);

        const g = this._graph;

        // 定义箭头 marker
        const defs = this._el("defs");
        const marker = this._el("marker", {
            id: "arrow", markerWidth: ARROW_SIZE, markerHeight: ARROW_SIZE,
            refX: ARROW_SIZE - 1, refY: ARROW_SIZE / 2,
            orient: "auto", markerUnits: "userSpaceOnUse",
        });
        const path = this._el("path", {
            d: `M0,0 L0,${ARROW_SIZE} L${ARROW_SIZE},${ARROW_SIZE / 2} Z`,
            fill: "#444",
        });
        marker.appendChild(path);
        defs.appendChild(marker);
        this.svg.appendChild(defs);

        const graphW = g.graph().width || 400;
        const graphH = g.graph().height || 300;
        this.svg.setAttribute("viewBox", `0 0 ${graphW} ${graphH}`);
        this.svg.style.width = "100%";
        this.svg.style.height = graphH + "px";

        // 画边
        const edgeGroup = this._el("g", { class: "edges" });
        for (const e of g.edges()) {
            const pts = g.edge(e).points;
            const d = pts.map((p, i) => `${i === 0 ? "M" : "L"}${p.x},${p.y}`).join(" ");
            const edgePath = this._el("path", {
                d,
                stroke: "#475569",
                "stroke-width": "1.5",
                fill: "none",
                "marker-end": "url(#arrow)",
            });
            edgeGroup.appendChild(edgePath);
        }
        this.svg.appendChild(edgeGroup);

        // 画节点
        const nodeGroup = this._el("g", { class: "nodes" });
        for (const n of g.nodes()) {
            const node = g.node(n);
            const grp = this._el("g", {
                class: "dag-node",
                id: `dag-node-${n}`,
                transform: `translate(${node.x - NODE_W / 2},${node.y - NODE_H / 2})`,
            });
            const state = this._nodeStates[n] || "pending";
            const colors = STEP_COLORS[state] || STEP_COLORS.pending;

            const rect = this._el("rect", {
                width: NODE_W, height: NODE_H, rx: 3, ry: 3,
                fill: colors.fill,
                stroke: colors.stroke,
                "stroke-width": state === "running" ? "2" : "1",
            });
            if (state === "running") {
                rect.style.animation = "pulse-border 1s ease-in-out infinite";
            }

            const icon = state === "success" ? "✓" : state === "failed" ? "✗" : state === "soft_failed" ? "!" : "";
            const label = node.label || n;
            const displayLabel = icon ? `${icon} ${label}` : label;

            const text = this._el("text", {
                x: NODE_W / 2, y: NODE_H / 2 + 1,
                "text-anchor": "middle",
                "dominant-baseline": "middle",
                fill: colors.text,
                "font-size": "13",
                "font-family": "monospace",
                "font-weight": "700",
            });
            text.textContent = displayLabel;

            grp.appendChild(rect);
            grp.appendChild(text);

            // 软故障标记（橙色角标）
            if (node.soft) {
                const dot = this._el("circle", { cx: NODE_W - 6, cy: 6, r: 4, fill: "#f97316" });
                grp.appendChild(dot);
            }

            nodeGroup.appendChild(grp);
        }
        this.svg.appendChild(nodeGroup);
    }

    _updateNode(name) {
        if (!this._graph) return;
        const g = this._graph;
        if (!g.hasNode(name)) return;
        const node = g.node(name);
        const grp = document.getElementById(`dag-node-${name}`);
        if (!grp) return;

        const state = this._nodeStates[name] || "pending";
        const colors = STEP_COLORS[state] || STEP_COLORS.pending;

        const rect = grp.querySelector("rect");
        rect.setAttribute("fill", colors.fill);
        rect.setAttribute("stroke", colors.stroke);
        rect.setAttribute("stroke-width", state === "running" ? "2" : "1");
        rect.style.animation = state === "running" ? "pulse-border 1s ease-in-out infinite" : "";

        const icon = state === "success" ? "✓" : state === "failed" ? "✗" : state === "soft_failed" ? "!" : "";
        const label = node.label || name;
        const text = grp.querySelector("text");
        text.setAttribute("fill", colors.text);
        text.setAttribute("font-size", "13");
        text.setAttribute("font-weight", "700");
        text.textContent = icon ? `${icon} ${label}` : label;
    }

    _el(tag, attrs = {}) {
        const el = document.createElementNS("http://www.w3.org/2000/svg", tag);
        for (const [k, v] of Object.entries(attrs)) el.setAttribute(k, v);
        return el;
    }
}
