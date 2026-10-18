'use strict';

const $ = id => document.getElementById(id);
const viewer = new BpmnJS({container: '#canvas'});
let definitions = [], diagrams = [], key = '', run = null, picked = null, busy = false, runRecords = [];

const escape = value => String(value ?? '').replace(/[&<>"']/g, c => ({
  '&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;'
}[c]));
const outcomeLabel = {RUNNING:'执行中', SUCCEEDED:'已完成', FAILED:'已停止'};
const taskLabel = {accepted:'已接受', succeeded:'已完成', failed:'失败', interrupted:'已中断', rejected:'已拒绝', cancelled:'已取消'};
const conditionLabel = {
  safety_permit: '安全许可',
  preamp_repeat_ready: '预放重频模式',
  collimation_environment_ready: '准直环境',
  diagnosis_acquisition_ready: '诊断采集设置',
  crystal_pose_ready: '晶体位姿设置',
  charge_preparation_ready: '发射充电准备',
  preamp_single_recipe_ready: '预放单次配方',
  measurement_single_recipe_ready: '测量单次配方',
  target_measurement_ready: '打靶测量准备',
  switch_charge_ready: '开关充电准备',
  charge_complete: '发射充电完成',
  warning_music_stopped: '警示音乐停止',
  trigger_countdown_started: '主发射倒计时开始',
  pump_trigger_ready: '泵浦触发准备',
  switch_trigger_ready: '开关触发准备',
  synchronized_triggered: '主发射同步触发完成',
  diagnostic_postprocess_ready: '物理诊断后处理',
  pump_data_collected: '主发射后泵浦数据采集',
  pump_standby_ready: '主发射后泵浦待机',
  preionization_prepared: '预电离发射准备',
  preionization_music_started: '预电离警示音乐开启',
  preionization_charge_complete: '预电离发射充电',
  preionization_music_stopped: '预电离警示音乐停止',
  preionization_countdown_started: '预电离同步倒计时',
  preionization_pump_trigger_ready: '预电离泵浦触发准备',
  preionization_sync_triggered: '预电离同步触发',
  preionization_data_collected: '预电离后泵浦数据采集',
  preionization_standby_ready: '预电离后泵浦待机',
  warning_light_off: '警灯关闭',
  laser_parameter_collected: '激光参数采集',
  target_analysis_ready: '靶瞄数据分析',
  target_retracted: '靶瞄收靶',
  switch_waveform_collected: '放电波形采集',
  switch_standby_ready: '开关待机',
  slab_purge_complete: '片放吹扫',
  shielding_door_open: '靶场中子屏蔽门打开'
};
const componentLabel = {seed:'种子源', shg:'二倍频', preamp:'再生与双程放大', multipass:'多程放大', context:'同步/环境条件'};

async function api(path, options) {
  const response = await fetch('/api' + path, options);
  if (!response.ok) {
    let body = {};
    try { body = await response.json(); } catch (_) {}
    throw Error(body.error || response.statusText);
  }
  return response.json();
}
function showError(error) {
  $('error').textContent = error?.message || '';
  $('error').hidden = !error;
}
function parseResult(command) {
  if (!command?.commandResult) return null;
  if (typeof command.commandResult === 'object') return command.commandResult;
  try { return JSON.parse(command.commandResult); } catch (_) { return null; }
}
function setSourceVisibility() {
  const usesSources = ['optical_frontend_preparation', 'formal_emission', 'post_emission_processing', 'emission_task_lifecycle'].includes($('process').value);
  $('source-options').hidden = !usesSources;
  $('seed').disabled = !usesSources || Boolean(run && !run.ended);
  $('shg').disabled = !usesSources || Boolean(run && !run.ended);
}
async function load(processKey) {
  key = processKey;
  const response = await fetch('/api/processes/' + encodeURIComponent(processKey) + '/xml');
  if (!response.ok) throw Error('流程定义加载失败');
  await viewer.importXML(await response.text());
  diagrams = viewer.getDefinitions().diagrams;
  $('scope').replaceChildren(...diagrams.map(d =>
    new Option(d.plane.bpmnElement.name || d.plane.bpmnElement.id, d.id)));
  const preferred = diagrams.find(d => d.plane.bpmnElement.id === 'normal') || diagrams[0];
  if (preferred) {
    $('scope').value = preferred.id;
    await openScope(preferred.id);
  }
  setSourceVisibility();
}
async function openScope(id) {
  await viewer.open(id);
  const canvas = viewer.get('canvas');
  canvas.zoom('fit-viewport', 'auto');
  const size = canvas.getSize();
  canvas.zoom(canvas.zoom() * .9, {x:size.width / 2, y:size.height / 2});
  paint();
}
function paint() {
  if (!viewer.getDefinitions()) return;
  const canvas = viewer.get('canvas'), registry = viewer.get('elementRegistry');
  registry.forEach(element => {
    for (const marker of ['running', 'done', 'interrupted', 'selected-node']) canvas.removeMarker(element.id, marker);
  });
  if (run) {
    const active = new Set(run.activeActivities || []);
    const rows = new Map();
    for (const row of (run.activities || [])) {
      if (!rows.has(row.id)) rows.set(row.id, []);
      rows.get(row.id).push(row);
    }
    for (const [id, items] of rows) {
      if (!registry.get(id)) continue;
      let state;
      if (active.has(id) || items.some(item => !item.ended)) state = 'running';
      else if (items.some(item => item.cancelled)) state = 'interrupted';
      else state = 'done';
      canvas.addMarker(id, state);
    }
    if (picked && registry.get(picked)) canvas.addMarker(picked, 'selected-node');
  }
  showDetails();
}
function showDetails() {
  if (!picked) {
    $('details').textContent = '点击流程节点查看命令及执行记录。';
    $('detail-count').textContent = '';
    return;
  }
  const element = viewer.get('elementRegistry').get(picked);
  let html = '<strong>' + escape(element?.businessObject?.name || picked) + '</strong><div class="node-id">' + escape(picked) + '</div>';
  if (run) {
    const commands = (run.commands || []).filter(c => c.commandNode === picked || picked.startsWith((c.commandNode || '') + '_'));
    const activities = (run.activities || []).filter(a => a.id === picked);
    $('detail-count').textContent = activities.length ? activities.length + ' 次进入' : '';
    for (const command of commands) {
      const result = parseResult(command);
      const status = result?.status || (command.sent ? 'accepted' : '待发送');
      html += '<div class="command"><b>' + escape(componentLabel[command.commandDevice] || command.commandDevice) + '</b>' +
        '<span class="command-status ' + escape(status) + '">' + escape(taskLabel[status] || status) + '</span>' +
        '<br>动作：' + escape(command.commandAction) + '<br>命令：' + escape(command.commandId) +
        (result?.reason ? '<br><span class="muted">' + escape(result.reason) + '</span>' : '') + '</div>';
    }
    if (activities.length) html += '<p>已结束 ' + activities.filter(a => a.ended && !a.cancelled).length +
      ' 次；中断 ' + activities.filter(a => a.cancelled).length + ' 次</p>';
    if (!commands.length && !activities.length) html += '<p class="muted">该节点尚未产生执行记录。</p>';
  } else {
    html += '<p class="muted">尚未选择运行实例。</p>';
  }
  $('details').innerHTML = html;
}
function renderRun() {
  const state = run ? (outcomeLabel[run.outcome] || run.outcome) : '流程定义';
  $('outcome').textContent = state;
  $('diagram-state').textContent = state;
  $('reason').textContent = run?.failureReason || run?.communicationError || '';
  $('run-name').textContent = run?.name || '尚未启动';
  $('run-id').textContent = run?.id || '—';
  const active = (run?.activeActivities || []).map(id => viewer.get('elementRegistry')?.get(id)?.businessObject?.name || id);
  $('run-stage').textContent = active.length ? active.join('、') : (run && !run.ended ? '等待事件' : '—');
  const commands = run?.commands || [];
  const finished = commands.filter(c => ['succeeded','failed','interrupted','rejected','cancelled'].includes(parseResult(c)?.status)).length;
  $('command-summary').textContent = commands.length ? finished + ' / ' + commands.length + ' 已完成' : '暂无命令';
  $('stop').disabled = !run || Boolean(run.ended);
  $('start').disabled = Boolean(run && !run.ended);
  $('process').disabled = Boolean(run && !run.ended);
  $('runs').value = run?.id || '';
  setSourceVisibility();
  paint();
}
viewer.on('element.click', event => {
  if (event.element.type === 'bpmn:SequenceFlow') return;
  picked = event.element.id;
  paint();
});
viewer.on('element.dblclick', event => {
  const diagram = diagrams.find(d => d.plane.bpmnElement.id === event.element.id);
  if (diagram) {
    $('scope').value = diagram.id;
    openScope(diagram.id).catch(showError);
  }
});
$('scope').onchange = () => openScope($('scope').value).catch(showError);
$('fit').onclick = () => viewer.get('canvas').zoom('fit-viewport', 'auto');
$('process').onchange = async () => {
  try {
    run = null; picked = null; showError(null);
    await load($('process').value);
    await refreshHistory(false);
    renderRun();
  } catch (error) { showError(error); }
};
$('start').onclick = async () => {
  try {
    $('start').disabled = true;
    const sources = ['optical_frontend_preparation', 'formal_emission', 'post_emission_processing', 'emission_task_lifecycle'].includes($('process').value)
      ? ['seed', 'shg'].filter(id => $(id).checked) : [];
    run = await api('/runs', {
      method:'POST', headers:{'Content-Type':'application/json'},
      body: JSON.stringify({processKey:$('process').value, sources})
    });
    showError(null);
    await refreshHistory(false);
    renderRun();
  } catch (error) {
    showError(error);
    renderRun();
  }
};
$('stop').onclick = async () => {
  try {
    run = await api('/runs/' + encodeURIComponent(run.id) + '/stop', {method:'POST'});
    showError(null); renderRun(); await refreshHistory(false);
  } catch (error) { showError(error); }
};
$('runs').onchange = async () => {
  try {
    if (!$('runs').value) { run = null; renderRun(); return; }
    picked = null;
    run = await api('/runs/' + encodeURIComponent($('runs').value));
    if (run.key !== key) {
      $('process').value = run.key;
      await load(run.key);
    }
    showError(null); renderRun();
  } catch (error) { showError(error); }
};
async function refreshHistory(selectLatest) {
  runRecords = await api('/runs');
  const selected = run?.id || '';
  $('runs').replaceChildren(new Option('选择运行实例', ''), ...runRecords.map(record =>
    new Option(record.name + ' · ' + new Date(record.started).toLocaleString(), record.id)));
  let wanted = selected;
  if (selectLatest && !wanted) {
    const latest = runRecords.find(record => !key || record.key === key);
    if (latest) wanted = latest.id;
  }
  if (wanted) $('runs').value = wanted;
  if (selectLatest && !run && wanted) {
    run = await api('/runs/' + encodeURIComponent(wanted));
    if (run.key !== key) {
      $('process').value = run.key;
      await load(run.key);
    }
    showError(null);
    renderRun();
  }
}
async function refreshDevices() {
  const [connection, devices] = await Promise.all([api('/connection'), api('/devices')]);
  $('connection').textContent = connection.connected ? '事件已连接' : connection.error || 'Tango 未连接';
  const connected = devices.filter(d => d.connected).length;
  const faults = devices.filter(d => d.snapshot?.outer_state === 'fault').length;
  $('device-summary').textContent = connected + ' / ' + devices.length + ' 已连接' + (faults ? ' · ' + faults + ' 个异常' : '');
  $('devices').innerHTML = devices.map(device => {
    const snapshot = device.snapshot || {}, conditions = snapshot.conditions;
    const state = snapshot.outer_state_label || snapshot.outer_state || (device.connected ? '读取中' : '连接中断');
    const inner = snapshot.inner_state_label || snapshot.state_id || '—';
    let body = '<div class="device-head"><div><strong>' + escape(device.id) + '</strong><span class="component-name">' +
      escape(componentLabel[device.component] || snapshot.component || device.component) + '</span></div>' +
      '<span class="device-state ' + (device.connected ? 'online' : 'offline') + '">' + escape(state) + '</span></div>';
    body += '<div class="device-line"><span>关键状态</span><b>' + escape(inner) + '</b></div>';
    body += '<div class="device-line"><span>当前动作</span><b>' + escape(snapshot.active_action || '空闲') + '</b></div>';
    body += '<div class="device-line"><span>任务结果</span><b>' + escape(taskLabel[snapshot.task_state] || snapshot.task_state || '—') + '</b></div>';
    if (conditions) {
      body += '<div class="conditions"><div class="conditions-title">演示条件</div>';
      for (const [condition, value] of Object.entries(conditions)) {
        body += '<label class="condition"><input type="checkbox" data-device="' + escape(device.id) + '" data-condition="' + escape(condition) + '"' +
          (value ? ' checked' : '') + '><span>' + escape(conditionLabel[condition] || condition) + '</span><i>' + (value ? '满足' : '未满足') + '</i></label>';
      }
      body += '</div>';
    }
    body += '<div class="address">' + escape(device.address || '') + '</div>';
    return '<article class="device ' + (device.connected ? '' : 'is-offline') + '">' + body + '</article>';
  }).join('');
}
$('devices').addEventListener('change', async event => {
  const input = event.target;
  if (!input.matches('input[data-device][data-condition]')) return;
  input.disabled = true;
  try {
    await api('/devices/' + encodeURIComponent(input.dataset.device) + '/conditions', {
      method:'POST', headers:{'Content-Type':'application/json'},
      body: JSON.stringify({[input.dataset.condition]: input.checked})
    });
    showError(null);
    await refreshDevices();
  } catch (error) {
    input.checked = !input.checked;
    showError(error);
  } finally { input.disabled = false; }
});
async function tick() {
  if (busy) return;
  busy = true;
  try {
    await refreshHistory(!run);
    if (run) {
      run = await api('/runs/' + encodeURIComponent(run.id));
      showError(null);
      renderRun();
    }
    await refreshDevices();
  } catch (error) { showError(error); }
  finally { busy = false; }
}
(async () => {
  try {
    definitions = await api('/processes');
    $('process').replaceChildren(...definitions.map(d => new Option(d.name, d.key)));
    $('process').value = definitions.some(d => d.key === 'emission_task_lifecycle')
      ? 'emission_task_lifecycle' : 'optical_frontend_preparation';
    await load($('process').value);
    await refreshHistory(true);
    renderRun();
    await refreshDevices();
  } catch (error) { showError(error); }
  setInterval(tick, 1000);
})();
