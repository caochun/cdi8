from __future__ import annotations

import json
import re
from collections import Counter
from html import escape
from pathlib import Path
from typing import Any


_NODE_ID_RE = re.compile(r"^N(\d+)$")


def load_lifecycle_events(path: str | Path) -> list[dict[str, Any]]:
    events: list[dict[str, Any]] = []
    with Path(path).open("r", encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            events.append(json.loads(line))
    return events


def _node_sort_key(label: str) -> tuple[int, str]:
    node_id = label.split(" ", 1)[0]
    match = _NODE_ID_RE.match(node_id)
    if match:
        return int(match.group(1)), label
    return 9999, label


def render_timeline_html(events: list[dict[str, Any]], title: str = "GXLF Device Timeline") -> str:
    systems = sorted({event.get("system_name", "") for event in events if event.get("system_name")})
    services = sorted({event.get("service_id", "") for event in events if event.get("service_id")})
    nodes = sorted(
        {f"{event.get('node_id', '')} {event.get('node_name', '')}".strip() for event in events if event.get("node_id")},
        key=_node_sort_key,
    )
    stages = list(dict.fromkeys(event.get("stage_id", "") for event in events if event.get("stage_id")))
    event_counts = Counter(event.get("event_type", "unknown") for event in events)
    seq_min = min((int(event.get("seq", 0)) for event in events), default=0)
    seq_max = max((int(event.get("seq", 0)) for event in events), default=0)
    payload = {
        "events": events,
        "systems": systems,
        "services": services,
        "nodes": nodes,
        "stages": stages,
        "eventCounts": dict(event_counts),
        "seqMin": seq_min,
        "seqMax": seq_max,
    }
    payload_json = json.dumps(payload, ensure_ascii=False).replace("</", "<\\/")

    html = """<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>__TITLE__</title>
  <style>
    :root {
      --bg: #f6f5f0;
      --panel: #ffffff;
      --ink: #172326;
      --muted: #657277;
      --line: #d9d8d0;
      --soft: #eeece4;
      --blue: #2563a9;
      --green: #2f7d55;
      --amber: #b46a16;
      --red: #be3434;
      --teal: #167277;
      --charcoal: #2e3b40;
    }
    * { box-sizing: border-box; }
    body {
      margin: 0;
      background: var(--bg);
      color: var(--ink);
      font-family: Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
    }
    header {
      position: sticky;
      top: 0;
      z-index: 5;
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 16px;
      padding: 12px 18px;
      background: var(--panel);
      border-bottom: 1px solid var(--line);
    }
    h1 {
      margin: 0;
      font-size: 18px;
      line-height: 1.25;
    }
    .summary {
      display: flex;
      flex-wrap: wrap;
      justify-content: flex-end;
      gap: 10px;
      color: var(--muted);
      font-size: 12px;
    }
    .shell {
      display: grid;
      grid-template-columns: 286px minmax(0, 1fr) 360px;
      min-height: calc(100vh - 50px);
    }
    aside {
      background: var(--panel);
      border-right: 1px solid var(--line);
      padding: 14px;
      overflow: auto;
    }
    main {
      padding: 14px;
      overflow: auto;
    }
    .inspector {
      background: var(--panel);
      border-left: 1px solid var(--line);
      padding: 14px;
      overflow: auto;
    }
    label {
      display: block;
      margin: 12px 0 6px;
      color: var(--muted);
      font-size: 12px;
      font-weight: 700;
    }
    select, input {
      width: 100%;
      min-height: 34px;
      border: 1px solid var(--line);
      border-radius: 6px;
      background: #fff;
      color: var(--ink);
      padding: 6px 8px;
      font-size: 13px;
    }
    .toggle-row {
      display: flex;
      align-items: center;
      gap: 8px;
      margin-top: 10px;
      font-size: 13px;
    }
    .toggle-row input {
      width: auto;
      min-height: auto;
    }
    .section {
      background: var(--panel);
      border: 1px solid var(--line);
      border-radius: 8px;
      overflow: hidden;
      margin-bottom: 14px;
    }
    .section-head {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      padding: 10px 12px;
      border-bottom: 1px solid var(--line);
      color: var(--muted);
      font-size: 12px;
    }
    .section-head strong {
      color: var(--ink);
      font-size: 14px;
    }
    svg {
      display: block;
      width: 100%;
      background: #fff;
    }
    #flow-svg { min-height: 260px; }
    #lane-svg { min-height: 620px; }
    .legend {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 8px;
      margin-top: 14px;
      font-size: 12px;
      color: var(--muted);
    }
    .legend span {
      display: flex;
      align-items: center;
      gap: 6px;
      min-width: 0;
    }
    .chip {
      display: inline-block;
      width: 12px;
      height: 12px;
      border-radius: 3px;
      flex: 0 0 auto;
    }
    .hint {
      margin-top: 12px;
      color: var(--muted);
      font-size: 12px;
      line-height: 1.45;
    }
    .metric-grid {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 8px;
      margin-top: 12px;
    }
    .metric {
      border: 1px solid var(--line);
      border-radius: 6px;
      padding: 8px;
      background: #fbfaf7;
    }
    .metric .num {
      color: var(--ink);
      font-size: 18px;
      font-weight: 800;
    }
    .metric .label {
      color: var(--muted);
      font-size: 11px;
      margin-top: 2px;
    }
    .detail h2 {
      margin: 0 0 10px;
      font-size: 15px;
    }
    .kv {
      display: grid;
      grid-template-columns: 118px minmax(0, 1fr);
      gap: 7px 10px;
      font-size: 12px;
      line-height: 1.35;
    }
    .k {
      color: var(--muted);
    }
    .v {
      overflow-wrap: anywhere;
      font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
    }
    .event-list {
      margin-top: 14px;
      border-top: 1px solid var(--line);
      padding-top: 10px;
    }
    .event-item {
      padding: 7px 0;
      border-bottom: 1px solid #ecebe4;
      font-size: 12px;
      line-height: 1.35;
    }
    .event-item b {
      color: var(--ink);
    }
    .event-item code {
      color: var(--muted);
      font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
    }
    @media (max-width: 1180px) {
      .shell { grid-template-columns: 260px minmax(0, 1fr); }
      .inspector { grid-column: 1 / -1; border-left: 0; border-top: 1px solid var(--line); }
    }
    @media (max-width: 760px) {
      header { align-items: flex-start; flex-direction: column; }
      .summary { justify-content: flex-start; }
      .shell { grid-template-columns: 1fr; }
      aside { border-right: 0; border-bottom: 1px solid var(--line); }
    }
  </style>
</head>
<body>
  <header>
    <h1>__TITLE__</h1>
    <div class="summary">
      <span id="summary-events"></span>
      <span id="summary-nodes"></span>
      <span id="summary-devices"></span>
      <span id="summary-seq"></span>
    </div>
  </header>
  <div class="shell">
    <aside>
      <label for="system-filter">系统</label>
      <select id="system-filter"><option value="">全部系统</option></select>
      <label for="service-filter">Tango 设备实例</label>
      <select id="service-filter"><option value="">全部实例</option></select>
      <label for="node-filter">流程节点</label>
      <select id="node-filter"><option value="">全部节点</option></select>
      <label for="text-filter">文本过滤</label>
      <input id="text-filter" placeholder="command / state / service id">
      <label class="toggle-row">
        <input id="failed-only" type="checkbox">
        <span>只看异常 / rejected / failed</span>
      </label>
      <label class="toggle-row">
        <input id="expanded-mode" type="checkbox">
        <span>默认展开实例泳道</span>
      </label>
      <div class="metric-grid">
        <div class="metric"><div class="num" id="metric-bars">0</div><div class="label">可见命令条</div></div>
        <div class="metric"><div class="num" id="metric-fanout">0</div><div class="label">最大 fanout</div></div>
        <div class="metric"><div class="num" id="metric-systems">0</div><div class="label">可见系统</div></div>
        <div class="metric"><div class="num" id="metric-errors">0</div><div class="label">异常命令</div></div>
      </div>
      <div class="legend">
        <span><i class="chip" style="background:var(--green)"></i>成功</span>
        <span><i class="chip" style="background:var(--red)"></i>异常</span>
        <span><i class="chip" style="background:var(--blue)"></i>执行中状态变化</span>
        <span><i class="chip" style="background:var(--amber)"></i>展开提示</span>
      </div>
      <div class="hint">
        上方是流程节点总览，下方默认按系统聚合 fanout。点击系统聚合条可以展开到具体 Tango service；点击任意条会在右侧显示命令生命周期和事件明细。
      </div>
    </aside>
    <main>
      <section class="section">
        <div class="section-head">
          <strong>流程总览</strong>
          <span id="flow-count"></span>
        </div>
        <svg id="flow-svg" role="img" aria-label="Flow overview timeline"></svg>
      </section>
      <section class="section">
        <div class="section-head">
          <strong>系统 / 设备泳道</strong>
          <span id="lane-count"></span>
        </div>
        <svg id="lane-svg" role="img" aria-label="System and device swimlane timeline"></svg>
      </section>
    </main>
    <aside class="inspector">
      <div class="detail" id="detail">
        <h2>详情</h2>
        <div class="kv">
          <div class="k">提示</div>
          <div class="v">点击流程条、系统条或设备条查看具体事件、命令、状态变化和 fanout 情况。</div>
        </div>
      </div>
    </aside>
  </div>
  <script id="timeline-data" type="application/json">__PAYLOAD__</script>
  <script>
    const data = JSON.parse(document.getElementById('timeline-data').textContent);
    const filters = {
      system: '',
      service: '',
      node: '',
      text: '',
      failedOnly: false,
      expandAll: false,
      expandedSystems: new Set()
    };
    const color = {
      ok: getCss('--green'),
      bad: getCss('--red'),
      busy: getCss('--blue'),
      amber: getCss('--amber'),
      line: getCss('--line'),
      soft: getCss('--soft'),
      ink: getCss('--ink'),
      muted: getCss('--muted'),
      panel: getCss('--panel'),
      teal: getCss('--teal'),
      charcoal: getCss('--charcoal')
    };

    const els = {
      system: document.getElementById('system-filter'),
      service: document.getElementById('service-filter'),
      node: document.getElementById('node-filter'),
      text: document.getElementById('text-filter'),
      failedOnly: document.getElementById('failed-only'),
      expandAll: document.getElementById('expanded-mode'),
      flowSvg: document.getElementById('flow-svg'),
      laneSvg: document.getElementById('lane-svg'),
      detail: document.getElementById('detail')
    };

    function getCss(name) {
      return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
    }
    function nodeNumber(nodeId) {
      const m = String(nodeId || '').match(/^N(\\d+)$/);
      return m ? Number(m[1]) : 9999;
    }
    function addOption(select, value, label) {
      const opt = document.createElement('option');
      opt.value = value;
      opt.textContent = label;
      select.appendChild(opt);
    }
    function svgEl(name, attrs = {}, text = '') {
      const el = document.createElementNS('http://www.w3.org/2000/svg', name);
      Object.entries(attrs).forEach(([key, value]) => el.setAttribute(key, value));
      if (text) el.textContent = text;
      return el;
    }
    function truncate(text, maxChars) {
      text = String(text || '');
      if (maxChars < 4) return '';
      if (text.length <= maxChars) return text;
      return text.slice(0, maxChars - 1) + '…';
    }
    function joinState(from, to) {
      if (!from && !to) return '';
      if (from === to) return String(to || from);
      return `${from || '?'}→${to || '?'}`;
    }
    function eventHaystack(event) {
      return [
        event.system_name, event.service_id, event.tango_fqdn, event.instance_code,
        event.node_id, event.node_name, event.command, event.event_type,
        event.business_state_before, event.business_state_after,
        event.task_state_before, event.task_state_after, event.result_status, event.message
      ].filter(Boolean).join(' ').toLowerCase();
    }
    function isBadGroup(group) {
      return group.result_status && group.result_status !== 'success'
        || group.task_to && !['succeeded', 'accepted', 'executing'].includes(group.task_to)
        || group.event_types.has('command_rejected');
    }
    function barFill(group) {
      if (isBadGroup(group)) return color.bad;
      if (group.event_types.has('state_transition')) return color.busy;
      return color.ok;
    }
    function formatSystemSummary(group) {
      const status = isBadGroup(group) ? '异常' : '成功';
      const state = joinState(group.business_from, group.business_to);
      return `${group.node_id} ${group.node_name} / ${group.command} / ${group.done}/${group.total} ${status}${state ? ' / ' + state : ''}`;
    }
    function formatDeviceSummary(group) {
      const state = joinState(group.business_from, group.business_to);
      const task = group.task_to ? `[${group.task_to}]` : '';
      return `${group.node_id} ${group.command}${state ? ' / ' + state : ''} ${task}`.trim();
    }

    data.systems.forEach(v => addOption(els.system, v, v));
    data.services.forEach(v => addOption(els.service, v, v));
    data.nodes.forEach(v => addOption(els.node, v, v));
    document.getElementById('summary-events').textContent = `${data.events.length} events`;
    document.getElementById('summary-nodes').textContent = `${data.nodes.length} nodes`;
    document.getElementById('summary-devices').textContent = `${data.services.length} devices`;
    document.getElementById('summary-seq').textContent = `seq ${data.seqMin}..${data.seqMax}`;

    els.system.addEventListener('change', () => { filters.system = els.system.value; render(); });
    els.service.addEventListener('change', () => { filters.service = els.service.value; render(); });
    els.node.addEventListener('change', () => { filters.node = els.node.value; render(); });
    els.text.addEventListener('input', () => { filters.text = els.text.value.trim().toLowerCase(); render(); });
    els.failedOnly.addEventListener('change', () => { filters.failedOnly = els.failedOnly.checked; render(); });
    els.expandAll.addEventListener('change', () => { filters.expandAll = els.expandAll.checked; render(); });

    function commandRuns() {
      const groups = new Map();
      for (const event of data.events) {
        if (filters.system && event.system_name !== filters.system) continue;
        if (filters.service && event.service_id !== filters.service) continue;
        if (filters.node && `${event.node_id || ''} ${event.node_name || ''}`.trim() !== filters.node) continue;
        if (filters.text && !eventHaystack(event).includes(filters.text)) continue;
        const key = event.task_id || `${event.service_id}:${event.node_id}:${event.command}:${event.seq}`;
        if (!groups.has(key)) {
          groups.set(key, {
            key,
            seq_start: Number(event.seq),
            seq_end: Number(event.seq),
            stage_id: event.stage_id || '',
            node_id: event.node_id || '',
            node_name: event.node_name || '',
            command: event.command || '',
            system_name: event.system_name || '',
            service_type: event.service_type || '',
            service_id: event.service_id || '',
            tango_fqdn: event.tango_fqdn || '',
            instance_code: event.instance_code || '',
            beam_line_no: event.beam_line_no,
            beam_group_no: event.beam_group_no,
            task_id: event.task_id || '',
            event_types: new Set(),
            events: [],
            business_from: undefined,
            business_to: undefined,
            health_from: undefined,
            health_to: undefined,
            task_from: undefined,
            task_to: undefined,
            result_status: undefined
          });
        }
        const group = groups.get(key);
        group.seq_start = Math.min(group.seq_start, Number(event.seq));
        group.seq_end = Math.max(group.seq_end, Number(event.seq));
        group.events.push(event);
        group.event_types.add(event.event_type || 'unknown');
        if (event.business_state_before !== undefined && group.business_from === undefined) group.business_from = event.business_state_before;
        if (event.business_state_after !== undefined) group.business_to = event.business_state_after;
        if (event.health_state_before !== undefined && group.health_from === undefined) group.health_from = event.health_state_before;
        if (event.health_state_after !== undefined) group.health_to = event.health_state_after;
        if (event.task_state_before !== undefined && group.task_from === undefined) group.task_from = event.task_state_before;
        if (event.task_state_after !== undefined) group.task_to = event.task_state_after;
        if (event.result_status !== undefined) group.result_status = event.result_status;
      }
      let result = [...groups.values()].sort((a, b) => a.seq_start - b.seq_start || nodeNumber(a.node_id) - nodeNumber(b.node_id));
      if (filters.failedOnly) result = result.filter(isBadGroup);
      return result;
    }

    function aggregateGroups(groups, keyFn) {
      const map = new Map();
      for (const item of groups) {
        const key = keyFn(item);
        if (!map.has(key)) {
          map.set(key, {
            key,
            seq_start: item.seq_start,
            seq_end: item.seq_end,
            stage_id: item.stage_id,
            node_id: item.node_id,
            node_name: item.node_name,
            command: item.command,
            system_name: item.system_name,
            service_id: '',
            service_type: item.service_type,
            total: 0,
            done: 0,
            bad: 0,
            services: new Set(),
            systems: new Set(),
            event_types: new Set(),
            events: [],
            children: [],
            business_from: undefined,
            business_to: undefined,
            task_to: undefined,
            result_status: undefined
          });
        }
        const group = map.get(key);
        group.seq_start = Math.min(group.seq_start, item.seq_start);
        group.seq_end = Math.max(group.seq_end, item.seq_end);
        group.total += 1;
        if (item.task_to === 'succeeded') group.done += 1;
        if (isBadGroup(item)) group.bad += 1;
        group.services.add(item.service_id);
        group.systems.add(item.system_name);
        item.event_types.forEach(v => group.event_types.add(v));
        group.events.push(...item.events);
        group.children.push(item);
        if (item.business_from !== undefined && group.business_from === undefined) group.business_from = item.business_from;
        if (item.business_to !== undefined) group.business_to = item.business_to;
        if (item.task_to !== undefined) group.task_to = item.task_to;
        if (item.result_status !== undefined) group.result_status = item.result_status;
      }
      return [...map.values()].sort((a, b) => a.seq_start - b.seq_start || nodeNumber(a.node_id) - nodeNumber(b.node_id));
    }

    function drawAxis(svg, width, height, left, right, top, bottom, x) {
      for (let i = 0; i <= 10; i++) {
        const seq = data.seqMin + (data.seqMax - data.seqMin) * i / 10;
        const gx = x(seq);
        svg.appendChild(svgEl('line', { x1: gx, x2: gx, y1: top, y2: height - bottom, stroke: '#ecebe4' }));
        svg.appendChild(svgEl('text', { x: gx, y: 16, 'text-anchor': 'middle', 'font-size': 10, fill: color.muted }, Math.round(seq)));
      }
      svg.appendChild(svgEl('line', { x1: left, x2: width - right, y1: height - bottom, y2: height - bottom, stroke: color.line }));
    }

    function showDetail(title, fields, events, children = []) {
      const rows = fields
        .filter(([, value]) => value !== undefined && value !== '')
        .map(([key, value]) => `<div class="k">${key}</div><div class="v">${String(value)}</div>`)
        .join('');
      const childRows = children.length
        ? `<div class="event-list"><h2>Fanout 明细</h2>${children.slice(0, 80).map(child => `<div class="event-item"><b>${child.service_id}</b><br><code>${child.node_id} ${child.command} ${joinState(child.business_from, child.business_to)} ${child.task_to || ''}</code></div>`).join('')}${children.length > 80 ? `<div class="event-item"><code>还有 ${children.length - 80} 条未显示</code></div>` : ''}</div>`
        : '';
      const eventRows = events && events.length
        ? `<div class="event-list"><h2>事件序列</h2>${events.slice(0, 80).map(event => `<div class="event-item"><b>#${event.seq} ${event.event_type}</b><br><code>${event.task_state_before || ''}→${event.task_state_after || ''} ${event.business_state_before || ''}→${event.business_state_after || ''} ${event.message || ''}</code></div>`).join('')}${events.length > 80 ? `<div class="event-item"><code>还有 ${events.length - 80} 条未显示</code></div>` : ''}</div>`
        : '';
      els.detail.innerHTML = `<h2>${title}</h2><div class="kv">${rows}</div>${childRows}${eventRows}`;
    }

    function showCommandGroup(group) {
      showDetail('命令生命周期', [
        ['task_id', group.task_id],
        ['node', `${group.node_id} ${group.node_name}`],
        ['command', group.command],
        ['system', group.system_name],
        ['service_id', group.service_id],
        ['tango_fqdn', group.tango_fqdn],
        ['seq', `${group.seq_start}..${group.seq_end}`],
        ['business_state', joinState(group.business_from, group.business_to)],
        ['health_state', joinState(group.health_from, group.health_to)],
        ['task_state', joinState(group.task_from, group.task_to)],
        ['result_status', group.result_status || ''],
      ], group.events);
    }

    function showAggregateGroup(title, group, children) {
      showDetail(title, [
        ['node', `${group.node_id} ${group.node_name}`],
        ['command', group.command],
        ['system', group.system_name || [...group.systems].join(', ')],
        ['seq', `${group.seq_start}..${group.seq_end}`],
        ['fanout', `${group.done}/${group.total} succeeded, ${group.bad} abnormal`],
        ['devices', group.services.size],
        ['business_state', joinState(group.business_from, group.business_to)],
        ['result', group.bad ? 'abnormal' : 'success'],
      ], group.events, children);
    }

    function renderFlow(groups) {
      const svg = els.flowSvg;
      const nodeGroups = aggregateGroups(groups, g => `${g.node_id}|${g.node_name}|${g.command}`);
      const width = Math.max(980, svg.clientWidth || 980);
      const left = 126, right = 28, top = 30, bottom = 28;
      const span = Math.max(1, data.seqMax - data.seqMin);
      const x = seq => left + ((Number(seq) - data.seqMin) / span) * (width - left - right);
      const byStage = new Map();
      for (const group of nodeGroups) {
        const stage = group.stage_id || '未分阶段';
        if (!byStage.has(stage)) byStage.set(stage, []);
        byStage.get(stage).push(group);
      }
      const stageBlocks = [];
      let y = top + 18;
      for (const stage of (data.stages.length ? data.stages : [...byStage.keys()])) {
        const items = (byStage.get(stage) || []).sort((a, b) => nodeNumber(a.node_id) - nodeNumber(b.node_id));
        if (!items.length) continue;
        const laneEnds = [];
        for (const item of items) {
          const sx = x(item.seq_start);
          let lane = laneEnds.findIndex(end => sx > end + 10);
          if (lane < 0) {
            lane = laneEnds.length;
            laneEnds.push(0);
          }
          item._flowLane = lane;
          laneEnds[lane] = Math.max(laneEnds[lane], x(item.seq_end) + 92);
        }
        const blockHeight = Math.max(44, laneEnds.length * 26 + 18);
        stageBlocks.push({ stage, items, y, height: blockHeight });
        y += blockHeight + 10;
      }
      const height = Math.max(260, y + bottom);
      svg.setAttribute('viewBox', `0 0 ${width} ${height}`);
      svg.innerHTML = '';
      drawAxis(svg, width, height, left, right, 24, bottom, x);

      for (const block of stageBlocks) {
        svg.appendChild(svgEl('rect', { x: 8, y: block.y - 18, width: width - 16, height: block.height, fill: block.stage.includes('正式') ? '#f4f8f8' : '#fbfaf7', stroke: '#ecebe4', rx: 6 }));
        svg.appendChild(svgEl('text', { x: 18, y: block.y + 4, 'font-size': 12, 'font-weight': 800, fill: color.ink }, block.stage));
        for (const group of block.items) {
          const gx = x(group.seq_start);
          const gy = block.y + group._flowLane * 26 - 9;
          const gw = Math.max(44, x(group.seq_end) - gx);
          const fill = group.bad ? color.bad : color.teal;
          const rect = svgEl('rect', { x: gx, y: gy, width: gw, height: 20, rx: 5, fill, opacity: 0.9 });
          rect.style.cursor = 'pointer';
          rect.addEventListener('click', () => showAggregateGroup('流程节点', group, group.children));
          svg.appendChild(rect);
          svg.appendChild(svgEl('text', { x: gx + 5, y: gy + 14, 'font-size': 10, 'font-weight': 800, fill: '#fff', 'pointer-events': 'none' }, truncate(`${group.node_id} ${group.node_name} / ${group.command} / ${group.done}/${group.total}`, Math.floor((gw - 10) / 6))));
          if (gw < 95) {
            svg.appendChild(svgEl('text', { x: gx, y: gy - 3, 'font-size': 9, fill: color.muted }, group.node_id));
          }
        }
      }
      document.getElementById('flow-count').textContent = `${nodeGroups.length} 个流程节点聚合条`;
    }

    function renderLanes(groups) {
      const svg = els.laneSvg;
      const expanded = filters.expandAll;
      const systemGroups = aggregateGroups(groups, g => `${g.system_name}|${g.node_id}|${g.command}`);
      const systems = [...new Set(systemGroups.map(g => g.system_name))].sort();
      const rowInfos = [];
      for (const system of systems) {
        rowInfos.push({ kind: 'system', id: system, label: system });
        if (expanded || filters.expandedSystems.has(system) || filters.system === system || filters.service) {
          const services = [...new Set(groups.filter(g => g.system_name === system).map(g => g.service_id))].sort();
          for (const service of services) {
            rowInfos.push({ kind: 'service', id: service, system, label: service });
          }
        }
      }
      const width = Math.max(1120, svg.clientWidth || 1120);
      const left = 256, right = 28, top = 32, bottom = 28, rowH = 46;
      const height = Math.max(620, top + rowInfos.length * rowH + bottom);
      const span = Math.max(1, data.seqMax - data.seqMin);
      const x = seq => left + ((Number(seq) - data.seqMin) / span) * (width - left - right);
      svg.setAttribute('viewBox', `0 0 ${width} ${height}`);
      svg.innerHTML = '';
      drawAxis(svg, width, height, left, right, 24, bottom, x);

      const yByRow = new Map();
      rowInfos.forEach((row, index) => {
        const y = top + index * rowH;
        yByRow.set(`${row.kind}:${row.id}`, y);
        const bg = row.kind === 'system' ? '#fbfaf7' : '#ffffff';
        svg.appendChild(svgEl('rect', { x: 0, y: y - 19, width, height: rowH, fill: bg }));
        svg.appendChild(svgEl('line', { x1: 0, x2: width, y1: y + 22, y2: y + 22, stroke: '#ecebe4' }));
        const label = row.kind === 'system' ? row.label : `  ${row.label}`;
        svg.appendChild(svgEl('text', { x: 12, y: y + 4, 'font-size': row.kind === 'system' ? 12 : 10, 'font-weight': row.kind === 'system' ? 800 : 500, fill: row.kind === 'system' ? color.ink : color.muted }, truncate(label, 35)));
        if (row.kind === 'system') {
          const mark = svgEl('text', { x: 230, y: y + 4, 'font-size': 11, 'text-anchor': 'end', fill: color.amber }, (filters.expandAll || filters.expandedSystems.has(row.id)) ? '收起' : '展开');
          mark.style.cursor = 'pointer';
          mark.addEventListener('click', () => {
            if (filters.expandedSystems.has(row.id)) filters.expandedSystems.delete(row.id);
            else filters.expandedSystems.add(row.id);
            render();
          });
          svg.appendChild(mark);
        }
      });

      for (const group of systemGroups) {
        const y = yByRow.get(`system:${group.system_name}`);
        if (y === undefined) continue;
        drawBar(svg, x, y, group.seq_start, group.seq_end, barFill(group), formatSystemSummary(group), () => {
          filters.expandedSystems.add(group.system_name);
          showAggregateGroup('系统聚合命令', group, group.children);
          render();
        }, group.bad ? '#fff' : '#fff');
      }
      if (expanded || filters.service || filters.system || filters.expandedSystems.size) {
        for (const group of groups) {
          if (!(expanded || filters.service || filters.system || filters.expandedSystems.has(group.system_name))) continue;
          const y = yByRow.get(`service:${group.service_id}`);
          if (y === undefined) continue;
          drawBar(svg, x, y, group.seq_start, group.seq_end, barFill(group), formatDeviceSummary(group), () => showCommandGroup(group), '#fff', 0.72);
          const transitions = group.events.filter(e => e.event_type === 'state_transition');
          transitions.slice(0, 2).forEach((event, index) => {
            const tx = x(event.seq);
            const text = joinState(event.business_state_before, event.business_state_after);
            const tag = svgEl('text', { x: tx, y: y + 17 + index * 10, 'font-size': 9, fill: color.charcoal }, truncate(text, 24));
            tag.style.cursor = 'pointer';
            tag.addEventListener('click', () => showCommandGroup(group));
            svg.appendChild(tag);
          });
        }
      }
      document.getElementById('lane-count').textContent = `${rowInfos.length} 条泳道，${systemGroups.length} 个系统聚合命令`;
    }

    function drawBar(svg, x, y, seqStart, seqEnd, fill, label, onClick, labelFill = '#fff', opacity = 0.88) {
      const bx = x(seqStart);
      const bw = Math.max(36, x(seqEnd) - bx);
      const rect = svgEl('rect', { x: bx, y: y - 13, width: bw, height: 22, rx: 5, fill, opacity });
      rect.style.cursor = 'pointer';
      rect.addEventListener('click', onClick);
      svg.appendChild(rect);
      const chars = Math.floor((bw - 10) / 6);
      const text = svgEl('text', { x: bx + 5, y: y + 2, 'font-size': 10, 'font-weight': 800, fill: labelFill, 'pointer-events': 'none' }, truncate(label, chars));
      svg.appendChild(text);
      rect.appendChild(svgEl('title', {}, label));
    }

    function render() {
      const groups = commandRuns();
      const systemCount = new Set(groups.map(g => g.system_name)).size;
      const systemAgg = aggregateGroups(groups, g => `${g.system_name}|${g.node_id}|${g.command}`);
      document.getElementById('metric-bars').textContent = groups.length;
      document.getElementById('metric-fanout').textContent = systemAgg.reduce((max, g) => Math.max(max, g.total), 0);
      document.getElementById('metric-systems').textContent = systemCount;
      document.getElementById('metric-errors').textContent = groups.filter(isBadGroup).length;
      renderFlow(groups);
      renderLanes(groups);
    }

    render();
  </script>
</body>
</html>
"""
    return html.replace("__TITLE__", escape(title)).replace("__PAYLOAD__", payload_json)


def write_timeline(input_path: str | Path, output_path: str | Path, title: str = "GXLF Device Timeline") -> int:
    events = load_lifecycle_events(input_path)
    html = render_timeline_html(events, title=title)
    output = Path(output_path)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(html, encoding="utf-8")
    return len(events)
