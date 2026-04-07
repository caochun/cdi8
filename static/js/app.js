/**
 * app.js — JZGK Web 控制台主控制器
 *
 * 职责：
 * - 建立 SSE 连接，接收实时事件
 * - 维护本地状态（子系统、队列、日志、历史）
 * - 驱动 UI 更新（DOM 操作）
 * - 处理用户操作（启动发次、注入故障等）
 */

// ── 子系统元数据 ─────────────────────────────────────────────

const SUBSYS_META = {
    ZZY:  { label: "种子源",    desc: "光纤种子源" },
    EPJ:  { label: "注入",      desc: "二倍频宽带注入" },
    YF:   { label: "预放",      desc: "再生与双程放大" },
    DCF:  { label: "主放",      desc: "多程放大" },
    BB:   { label: "泵浦",      desc: "泵浦分系统" },
    KG:   { label: "开关",      desc: "开关驱动源" },
    JZT:  { label: "同步",      desc: "集中同步" },
    ZK:   { label: "真空",      desc: "真空靶室" },
    BM:   { label: "靶瞄",      desc: "靶瞄准定位" },
    PLZ:  { label: "频转",      desc: "频率转换" },
    CLY:  { label: "测量",      desc: "测量取样" },
    WLZD: { label: "诊断",      desc: "物理实验诊断" },
    LDB:  { label: "靶",        desc: "液氘靶分系统" },
    LK:   { label: "吹扫",      desc: "冷空系统" },
    MX:   { label: "模型",      desc: "模型校准" },
    AQ:   { label: "安全",      desc: "安全联锁" },
};

const SUBSYS_ORDER = ["ZZY","EPJ","YF","DCF","BB","KG","JZT","ZK","BM","PLZ","CLY","WLZD","LDB","LK","MX","AQ"];

const STATE_CLASS = {
    OFF:      "state-off",
    STANDBY:  "state-standby",
    RUNNING:  "state-running",
    READY:    "state-ready",
    FAULT:    "state-fault",
    CHARGING: "state-running",
    CHARGED:  "state-ready",
    ARMED:    "state-ready",
    FIRING:   "state-running",
    LOCKED:   "state-ready",
    CLOSING:  "state-running",
    OPEN:     "state-standby",
    COOLED:   "state-ready",
    COOLING:  "state-running",
    PUMPING:  "state-running",
    EVACUATED:"state-ready",
    ALIGNING: "state-running",
    ALIGNED:  "state-ready",
};

const JZGK_STATE_CLASS = {
    IDLE: "jstate-idle",
    PREPARING: "jstate-preparing",
    FIRING: "jstate-firing",
    POST: "jstate-post",
    EMERGENCY_STOP: "jstate-emergency",
};

// ── 应用状态 ─────────────────────────────────────────────────

const State = {
    jzgkState: "IDLE",
    subsystems: {},           // name → {state, status, metrics}
    subsysHistory: {},        // name → [{ts, state, status}]
    subsysLogs: {},           // name → [{level, msg, ts}]
    queue: { pending: [], current: null },
    history: [],
    logs: [],
    activePhase: "a",         // 当前 DAG tab
    currentPhase: null,       // 正在执行的阶段
};

// ── DAG 渲染器 ────────────────────────────────────────────────

let dagRenderer = null;

// ── 初始化 ───────────────────────────────────────────────────

async function init() {
    buildSubsystemGrid();
    buildFsmDiagram();
    dagRenderer = new DAGRenderer("dag-svg");

    // 加载初始快照
    const snap = await fetch("/api/state").then(r => r.json());
    applySnapshot(snap);

    // 默认加载 A 阶段 DAG
    await switchPhaseTab("a");

    // 建立 SSE 连接
    connectSSE();

    // 绑定 UI 事件
    bindUI();
}

function applySnapshot(snap) {
    State.jzgkState = snap.jzgk_state;
    updateJzgkStateUI(snap.jzgk_state);

    for (const s of snap.subsystems) {
        State.subsystems[s.name] = { state: s.state, status: s.status, metrics: s.metrics };
        updateSubsysTile(s.name, s.state, s.status);
    }

    State.queue = snap.queue;
    renderQueue(snap.queue);

    State.history = snap.history;
    renderHistory(snap.history);
}

// ── SSE 连接 ─────────────────────────────────────────────────

function connectSSE() {
    const es = new EventSource("/api/events");

    es.onmessage = (evt) => {
        let msg;
        try { msg = JSON.parse(evt.data); } catch { return; }
        handleEvent(msg.type, msg.data);
    };

    es.onerror = () => {
        setTimeout(connectSSE, 3000);  // 断线重连
        es.close();
    };
}

function handleEvent(type, data) {
    switch (type) {
        case "snapshot":        applySnapshot(data); break;
        case "jzgk_state":      onJzgkState(data); break;
        case "step_start":      onStepStart(data); break;
        case "step_done":       onStepDone(data); break;
        case "subsystem_state": onSubsysState(data); break;
        case "safety":          onSafety(data); break;
        case "log":             onLog(data); break;
        case "queue_update":    onQueueUpdate(data); break;
        case "shot_complete":   onShotComplete(data); break;
    }
}

// ── 事件处理 ─────────────────────────────────────────────────

function onJzgkState(data) {
    State.jzgkState = data.new;
    updateJzgkStateUI(data.new);

    // 根据新状态切换 DAG tab 并重置
    const phaseMap = { PREPARING: "a", FIRING: "b", POST: "c" };
    const phase = phaseMap[data.new];
    if (phase) {
        State.currentPhase = phase;
        switchPhaseTab(phase).then(() => dagRenderer.resetAll());
    } else if (data.new === "IDLE") {
        State.currentPhase = null;
    }
}

async function onStepStart(data) {
    if (data.phase !== State.activePhase) {
        await switchPhaseTab(data.phase);
    }
    dagRenderer.setStepState(data.name, "running");
}

function onStepDone(data) {
    let state = data.success ? "success" : (data.soft ? "soft_failed" : "failed");
    dagRenderer.setStepState(data.name, state);
}

function onSubsysState(data) {
    State.subsystems[data.name] = State.subsystems[data.name] || {};
    State.subsystems[data.name].state = data.state;
    State.subsystems[data.name].status = data.status;
    updateSubsysTile(data.name, data.state, data.status);

    // 积累历史
    if (!State.subsysHistory[data.name]) State.subsysHistory[data.name] = [];
    State.subsysHistory[data.name].unshift({ ts: Date.now(), state: data.state, status: data.status });
    if (State.subsysHistory[data.name].length > 50) State.subsysHistory[data.name].pop();

    // 更新已打开的详情面板
    const panel = document.getElementById("detail-panel");
    if (panel && panel.dataset.subsys === data.name) {
        refreshDetailPanel(data.name);
    }
}

function onSafety(data) {
    const banner = document.getElementById("safety-banner");
    banner.textContent = `⚠ ${data.signal} — ${data.source}: ${data.msg}`;
    banner.className = data.signal === "S3" ? "banner-warn" : "banner-error";
    banner.style.display = "block";
    if (data.signal === "S3") {
        setTimeout(() => { banner.style.display = "none"; }, 8000);
    }
    appendLog({ level: "CRITICAL", logger: `safety.${data.source}`, msg: `[${data.signal}] ${data.msg}`, ts: data.ts });
}

function onLog(data) {
    appendLog(data);
    // 按子系统分流
    const logger = data.logger || "";
    for (const name of SUBSYS_ORDER) {
        if (logger.toLowerCase().includes(name.toLowerCase())) {
            if (!State.subsysLogs[name]) State.subsysLogs[name] = [];
            State.subsysLogs[name].unshift(data);
            if (State.subsysLogs[name].length > 200) State.subsysLogs[name].pop();
            break;
        }
    }
}

function onQueueUpdate(data) {
    State.queue = data;
    renderQueue(data);
}

function onShotComplete(data) {
    State.history.unshift(data);
    renderHistory(State.history);
}

// ── UI 构建 ──────────────────────────────────────────────────

function buildSubsystemGrid() {
    const grid = document.getElementById("subsys-grid");
    for (const name of SUBSYS_ORDER) {
        const meta = SUBSYS_META[name] || { label: name, desc: "" };
        const tile = document.createElement("div");
        tile.className = "subsys-tile";
        tile.id = `tile-${name}`;
        tile.dataset.name = name;
        tile.innerHTML = `
            <div class="tile-name">${name}</div>
            <div class="tile-label">${meta.label}</div>
            <div class="tile-state state-off" id="tile-state-${name}">OFF</div>
        `;
        tile.addEventListener("click", () => openDetailPanel(name));
        grid.appendChild(tile);
    }
}

function buildFsmDiagram() {
    const states = ["IDLE", "PREPARING", "FIRING", "POST"];
    const fsm = document.getElementById("fsm-diagram");
    fsm.innerHTML = states.map((s, i) => `
        <div class="fsm-state" id="fsm-state-${s}">
            <span>${s}</span>
        </div>
        ${i < states.length - 1 ? '<div class="fsm-arrow">→</div>' : ''}
    `).join("") + `
        <div class="fsm-emg" id="fsm-state-EMERGENCY_STOP" style="display:none">EMERGENCY_STOP</div>
    `;
}

// ── UI 更新 ──────────────────────────────────────────────────

function updateJzgkStateUI(state) {
    // 更新 header badge
    const badge = document.getElementById("jzgk-badge");
    badge.textContent = state;
    badge.className = "jzgk-badge " + (JZGK_STATE_CLASS[state] || "");

    // 更新 FSM 高亮
    for (const s of ["IDLE","PREPARING","FIRING","POST","EMERGENCY_STOP"]) {
        const el = document.getElementById(`fsm-state-${s}`);
        if (!el) continue;
        el.style.display = s === "EMERGENCY_STOP" ? (state === "EMERGENCY_STOP" ? "flex" : "none") : "flex";
        el.classList.toggle("fsm-active", s === state);
    }

    // 按钮状态
    document.getElementById("btn-enqueue").disabled = false;
    document.getElementById("btn-estop").disabled = state === "IDLE";
}

function updateSubsysTile(name, state, status) {
    const stateEl = document.getElementById(`tile-state-${name}`);
    if (!stateEl) return;
    const cls = STATE_CLASS[state] || "state-off";
    stateEl.className = `tile-state ${cls}`;
    stateEl.textContent = state;
    const tile = document.getElementById(`tile-${name}`);
    if (tile) tile.title = status || "";
}

// ── 队列渲染 ─────────────────────────────────────────────────

function renderQueue(q) {
    const el = document.getElementById("queue-list");
    let html = "";
    if (q.current) {
        html += `<div class="queue-item queue-running">
            <span class="qi-bullet">▶</span>
            <span class="qi-id">${q.current.recipe_id}</span>
            <span class="qi-tag">运行中</span>
        </div>`;
    }
    for (const r of (q.pending || [])) {
        if (r._cancelled) continue;
        html += `<div class="queue-item queue-pending">
            <span class="qi-bullet">○</span>
            <span class="qi-id">${r.recipe_id}</span>
            <button class="btn-cancel-qi" onclick="cancelShot('${r.recipe_id}')">×</button>
        </div>`;
    }
    if (!q.current && !q.pending?.length) {
        html = '<div class="queue-empty">队列为空</div>';
    }
    el.innerHTML = html;
}

// ── 历史渲染 ─────────────────────────────────────────────────

function renderHistory(history) {
    const tbody = document.getElementById("history-tbody");
    tbody.innerHTML = "";
    for (const r of history) {
        const tr = document.createElement("tr");
        tr.className = r.success ? "hist-success" : "hist-abort";
        tr.innerHTML = `
            <td>${r.shot_id}</td>
            <td>${r.recipe_id}</td>
            <td>${r.success ? "✓ 成功" : "✗ " + (r.abort_reason || "中止")}</td>
            <td>${fmtJ(r.uv_energy_j)} J</td>
            <td>${r.neutron_count ?? "-"}</td>
            <td>${fmtS(r.phase_a_elapsed)}/${fmtS(r.phase_b_elapsed)}/${fmtS(r.phase_c_elapsed)}</td>
        `;
        tr.addEventListener("click", () => toggleTimings(tr, r));
        tbody.appendChild(tr);
    }
}

function toggleTimings(tr, record) {
    const existing = tr.nextElementSibling;
    if (existing && existing.classList.contains("timing-row")) {
        existing.remove();
        return;
    }
    const timings = record.step_timings || {};
    const phases = { a: [], b: [], c: [] };
    for (const [k, v] of Object.entries(timings)) {
        const [p, name] = k.split(".");
        if (phases[p]) phases[p].push(`${name}: ${v.toFixed(3)}s`);
    }
    const detail = document.createElement("tr");
    detail.className = "timing-row";
    detail.innerHTML = `<td colspan="6">
        <div class="timing-detail">
            ${Object.entries(phases).filter(([,v])=>v.length).map(([p,v])=>
                `<div><strong>[${p.toUpperCase()}]</strong> ${v.join("  ")}</div>`
            ).join("")}
            ${record.abort_detail ? `<div class="abort-detail">中止原因: ${record.abort_detail}</div>` : ""}
        </div>
    </td>`;
    tr.after(detail);
}

// ── 日志渲染 ─────────────────────────────────────────────────

function appendLog(data) {
    State.logs.unshift(data);
    if (State.logs.length > 1000) State.logs.pop();

    const activeTab = document.querySelector(".bottom-tab.active")?.dataset?.tab;
    if (activeTab !== "logs") return;

    const container = document.getElementById("log-container");
    const div = document.createElement("div");
    div.className = `log-entry log-${data.level?.toLowerCase() || "info"}`;
    const time = new Date(data.ts * 1000).toLocaleTimeString("zh", { hour12: false });
    div.innerHTML = `<span class="log-time">${time}</span> <span class="log-level">[${data.level}]</span> <span class="log-msg">${escHtml(data.msg)}</span>`;
    container.insertBefore(div, container.firstChild);
    // 限制 DOM 节点数
    while (container.children.length > 200) container.removeChild(container.lastChild);
}

function renderLogs() {
    const container = document.getElementById("log-container");
    container.innerHTML = "";
    for (const data of State.logs.slice(0, 200)) {
        const div = document.createElement("div");
        div.className = `log-entry log-${data.level?.toLowerCase() || "info"}`;
        const time = new Date(data.ts * 1000).toLocaleTimeString("zh", { hour12: false });
        div.innerHTML = `<span class="log-time">${time}</span> <span class="log-level">[${data.level}]</span> <span class="log-msg">${escHtml(data.msg)}</span>`;
        container.appendChild(div);
    }
}

// ── DAG tab 切换 ─────────────────────────────────────────────

async function switchPhaseTab(phase) {
    State.activePhase = phase;
    document.querySelectorAll(".phase-tab").forEach(t => {
        t.classList.toggle("active", t.dataset.phase === phase);
    });
    await dagRenderer.loadPhase(phase);
}

// ── 子系统详情面板 ───────────────────────────────────────────

function openDetailPanel(name) {
    const panel = document.getElementById("detail-panel");
    panel.dataset.subsys = name;
    panel.style.display = "flex";
    refreshDetailPanel(name);
}

function refreshDetailPanel(name) {
    const sim = State.subsystems[name] || {};
    const meta = SUBSYS_META[name] || { label: name, desc: "" };
    const history = State.subsysHistory[name] || [];
    const logs = State.subsysLogs[name] || [];

    const cls = STATE_CLASS[sim.state] || "state-off";
    document.getElementById("panel-title").textContent = `${name} — ${meta.desc}`;
    document.getElementById("panel-state").innerHTML = `<span class="tile-state ${cls}">${sim.state || "-"}</span> <span class="panel-status">${escHtml(sim.status || "")}</span>`;

    // 关键指标
    const metrics = sim.metrics || {};
    const mEl = document.getElementById("panel-metrics");
    if (Object.keys(metrics).length) {
        mEl.innerHTML = Object.entries(metrics)
            .filter(([,v]) => v !== null && v !== undefined)
            .map(([k, v]) => {
                const val = typeof v === "number" ? v.toPrecision(4) : String(v);
                return `<div class="metric-row"><span class="metric-key">${k}</span><span class="metric-val">${escHtml(val)}</span></div>`;
            }).join("");
    } else {
        mEl.textContent = "无附加指标";
    }

    // 状态时间线
    const timelineEl = document.getElementById("panel-timeline");
    timelineEl.innerHTML = history.slice(0, 10).map(h => {
        const t = new Date(h.ts).toLocaleTimeString("zh", { hour12: false });
        const sc = STATE_CLASS[h.state] || "state-off";
        return `<div class="tl-row"><span class="tl-time">${t}</span><span class="tile-state ${sc}" style="font-size:10px;padding:1px 4px">${h.state}</span><span class="tl-msg">${escHtml(h.status || "")}</span></div>`;
    }).join("") || "<div class='tl-row'>无历史</div>";

    // 日志
    const logEl = document.getElementById("panel-logs");
    logEl.innerHTML = logs.slice(0, 50).map(l => {
        const t = new Date(l.ts * 1000).toLocaleTimeString("zh", { hour12: false });
        return `<div class="log-entry log-${l.level?.toLowerCase() || "info"}"><span class="log-time">${t}</span> <span class="log-level">[${l.level}]</span> <span class="log-msg">${escHtml(l.msg)}</span></div>`;
    }).join("") || "<div>暂无日志</div>";
}

function closeDetailPanel() {
    document.getElementById("detail-panel").style.display = "none";
}

// ── 控制操作 ─────────────────────────────────────────────────

function openEnqueueDialog() {
    document.getElementById("enqueue-dialog").style.display = "flex";
    // 自动生成 recipe_id
    const ts = Date.now().toString(36).toUpperCase();
    document.getElementById("f-recipe-id").value = `SHOT_${ts}`;
}

function closeEnqueueDialog() {
    document.getElementById("enqueue-dialog").style.display = "none";
}

async function submitEnqueue() {
    const body = {
        recipe_id:       document.getElementById("f-recipe-id").value || "SHOT_001",
        pump_energy_j:   parseFloat(document.getElementById("f-pump-energy").value),
        kg_voltage_kv:   parseFloat(document.getElementById("f-kg-voltage").value),
        plz_pitch_mrad:  parseFloat(document.getElementById("f-plz-pitch").value),
        plz_yaw_mrad:    parseFloat(document.getElementById("f-plz-yaw").value),
        target_id:       document.getElementById("f-target-id").value,
    };
    const res = await fetch("/api/shot/enqueue", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
    });
    if (res.ok) {
        closeEnqueueDialog();
    } else {
        alert("入队失败: " + (await res.text()));
    }
}

async function cancelShot(recipeId) {
    await fetch(`/api/shot/cancel/${encodeURIComponent(recipeId)}`, { method: "POST" });
}

async function emergencyStop() {
    if (!confirm("确认紧急停止？")) return;
    await fetch("/api/emergency-stop", { method: "POST" });
}

function openInjectMenu() {
    document.getElementById("inject-menu").style.display = "block";
}

function closeInjectMenu() {
    document.getElementById("inject-menu").style.display = "none";
}

async function injectS1() {
    closeInjectMenu();
    await fetch("/api/inject-s1", { method: "POST" });
}

function openInjectFaultDialog() {
    closeInjectMenu();
    document.getElementById("inject-fault-dialog").style.display = "flex";
}

function closeInjectFaultDialog() {
    document.getElementById("inject-fault-dialog").style.display = "none";
}

async function submitInjectFault() {
    const subsystem = document.getElementById("f-fault-subsys").value;
    const reason = document.getElementById("f-fault-reason").value || "手动注入故障";
    const res = await fetch("/api/inject-fault", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ subsystem, reason }),
    });
    if (res.ok) {
        closeInjectFaultDialog();
    } else {
        alert("注入失败: " + (await res.text()));
    }
}

// ── UI 绑定 ──────────────────────────────────────────────────

function bindUI() {
    // 底部 tab
    document.querySelectorAll(".bottom-tab").forEach(tab => {
        tab.addEventListener("click", () => {
            document.querySelectorAll(".bottom-tab").forEach(t => t.classList.remove("active"));
            document.querySelectorAll(".bottom-panel").forEach(p => p.style.display = "none");
            tab.classList.add("active");
            const panel = document.getElementById("panel-" + tab.dataset.tab);
            if (panel) panel.style.display = "block";
            if (tab.dataset.tab === "logs") renderLogs();
        });
    });

    // phase tabs
    document.querySelectorAll(".phase-tab").forEach(tab => {
        tab.addEventListener("click", () => switchPhaseTab(tab.dataset.phase));
    });

    // 点击 overlay 关闭菜单
    document.addEventListener("click", (e) => {
        const menu = document.getElementById("inject-menu");
        if (!e.target.closest("#btn-inject") && !e.target.closest("#inject-menu")) {
            menu.style.display = "none";
        }
    });
}

// ── 工具函数 ─────────────────────────────────────────────────

function fmtS(v) { return v != null ? v.toFixed(1) + "s" : "-"; }
function fmtJ(v) { return v != null ? Math.round(v) : "-"; }
function escHtml(s) {
    return String(s)
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;");
}

// ── 启动 ─────────────────────────────────────────────────────

window.addEventListener("DOMContentLoaded", init);
