// @ts-nocheck
import BpmnViewer from 'bpmn-js/lib/NavigatedViewer';
import {prepareXml} from '../diagrams/layout';
import {createStateCharts} from './state-charts';

/** Owns the diagram viewers and runtime DOM inside one mounted Workbench view. */
export function createRuntime(root:HTMLElement, onRunChange:(run:any)=>void = () => {}) {
const requests=new AbortController();
let disposed=false, revision=0, changing=false, selection=Promise.resolve(), polling=Promise.resolve();
const StateCharts=createStateCharts(requests.signal);
const request=async (url,options={})=>{const response=await fetch(url,{...options,signal:requests.signal});if(disposed)throw new DOMException('视图已关闭','AbortError');return response;};
const $ = id => root.querySelector(`[data-runtime-id="${id}"]`);
const viewer = new BpmnViewer({container:$('canvas')});
const childViewer = new BpmnViewer({container:$('child-canvas')});
let definitions=[], diagrams=[], key='', run=null, picked=null, busy=false, loaded=false;
let childRun=null, childId='', childLoading='', detailViewer=null, selectedViewer=viewer, deviceCache=[];
const cardNodes=new Map();

let traceEvents=[];
const modelKey=s=>s.model_revision||s.model;
function historyDevices(devices){
  if(!run?.ended)return devices;
  const history=new Map(Object.entries(run.initialDeviceSnapshots||{}));
  for(const event of traceEvents){const d=event.device;if(d?.device_id)history.set(d.device_id,{id:d.device_id,component:devices.find(x=>x.id===d.device_id)?.component||(run.participants||[]).find(x=>x.id===d.device_id)?.component,...d});}
  return (run.participants||[]).map(p=>history.get(p.id)||{...p,connected:false,snapshot:{},unrecorded:true});
}
async function refreshTrace(ticket=revision){
  const current=run;if(!current)return;
  const events=await api('/workbench/runs/'+encodeURIComponent(current.id)+'/trace');
  if(disposed||ticket!==revision)return;
  traceEvents=events;
  const rows=[];
  for(const owner of flattenRuns())for(const activity of owner.activities||[])if(/Task$|Activity$/.test(activity.type)){
    rows.push({time:activity.started,device:owner.name,event:'进入活动',detail:activity.name||activity.id,node:activity.id,owner:owner.id});
    if(activity.ended)rows.push({time:activity.ended,device:owner.name,event:activity.cancelled?'活动中断':'活动完成',detail:activity.name||activity.id,node:activity.id,owner:owner.id});
  }
  for(const item of traceEvents){const d=item.device,s=d.snapshot||{};rows.push({time:item.time,device:d.device_id,event:d.connected===false?'连接中断':s.active_action?'动作受理 / 状态快照':'设备状态',detail:(s.inner_state_label||s.state_id||'')+(s.last_result?' · '+s.last_result.status+' · '+s.last_result.command_id:'')});}
  rows.sort((a,b)=>new Date(b.time)-new Date(a.time));
  $('trace-count').textContent=rows.length+' 条';
  $('trace-list').innerHTML=rows.slice(0,200).map(r=>'<div class="trace-row"><time>'+escape(new Date(r.time).toLocaleTimeString())+'</time><b>'+escape(r.device)+'</b><span>'+escape(r.event)+'</span><span>'+escape(r.detail)+'</span></div>').join('')||'<p class="muted">尚无事件记录。旧运行未记录的设备状态不会用实时状态替代。</p>';
}
function renderWaits(){
  const waits=flattenRuns().flatMap(r=>(r.waits||[]).map(w=>({...w,owner:r})));
  const pending=allCommands().filter(c=>!['succeeded','failed','interrupted','rejected','cancelled'].includes(commandStatus(c)));
  const count=waits.length+pending.length;
  $('wait-count').textContent=count+' 项';
  $('wait-empty').hidden=Boolean(count);
  $('wait-empty').textContent=run?.ended?'结束时没有未完成的等待事项':run?'当前没有等待事项，流程可继续推进。':'开始运行后，在此查看等待与反馈。';
  $('wait-diagnostics').hidden=!count;
  let html=waits.map(w=>'<div class="wait-row"><div><b>'+escape(w.owner.activities.find(a=>a.id===w.activity)?.name||w.activity)+'</b><small>'+escape(w.collection+' → '+(conditionLabel[w.condition]||w.condition))+' · '+(w.met?'已满足，等待推进':'未满足')+(w.started?' · 已等待 '+duration(Date.now()-new Date(w.started)):'')+'</small></div><div>'+w.devices.map(d=>'<button data-condition="'+escape(w.condition)+'" data-device="'+escape(d.id)+'">模拟确认 · '+escape(d.id)+'</button>').join('')+'</div></div>').join('');
  html+=pending.map(c=>'<div class="wait-row"><div><b>'+escape(c.commandDevice+' · '+StateCharts.action(modelKey(deviceCache.find(d=>d.id===c.commandDevice)?.snapshot||{}),c.commandAction))+'</b><small>'+(c.sent?'设备已受理，等待动作结果':'等待命令发送')+' · '+escape(c.commandId)+'</small></div><div>'+(c.sent?'<button data-outcome="success" data-device="'+escape(c.commandDevice)+'" data-command="'+escape(c.commandId)+'">成功反馈</button><button data-outcome="failure" data-device="'+escape(c.commandDevice)+'" data-command="'+escape(c.commandId)+'">失败反馈</button>':'')+'</div></div>').join('');
  const due=flattenRuns().flatMap(r=>r.timers||[]).map(t=>new Date(t.due)).sort((a,b)=>a-b)[0];
  if(due)html+='<p class="muted">最近超时期限：'+escape(due.toLocaleString())+' · 剩余 '+duration(due-Date.now())+'</p>';
  $('wait-list').innerHTML=html;
  if(run?.ended)$('wait-list').querySelectorAll('button').forEach(button=>button.remove());
}
$('wait-list').addEventListener('click',async event=>{
 const button=event.target.closest('button');if(!button||run?.ended||changing||disposed||!loaded)return;button.disabled=true;
 try{if(button.dataset.condition)await api('/devices/'+encodeURIComponent(button.dataset.device)+'/conditions',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({[button.dataset.condition]:true})});
 else await api('/workbench/devices/'+encodeURIComponent(button.dataset.device)+'/simulate',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({command_id:button.dataset.command,outcome:button.dataset.outcome})});showError(null);await tick();}catch(error){showError(error);}finally{button.disabled=false;}
});
const escape=value=>String(value??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
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

async function api(path,options) {
  const response=await request('/api'+path,options);
  if(!response.ok) {let body={};try{body=await response.json();}catch(_){} throw Error(body.error||response.statusText);}
  const value=await response.json();
  if(disposed)throw new DOMException('视图已关闭','AbortError');
  return value;
}
function showError(error){if(disposed||error?.name==='AbortError')return;$('error').textContent=error?.message||'';$('error').hidden=!error;}
function parseResult(command){if(!command?.commandResult)return null;if(typeof command.commandResult==='object')return command.commandResult;try{return JSON.parse(command.commandResult);}catch(_){return null;}}
function flattenRuns(current=run){return current?[current,...(current.children||[]).flatMap(c=>flattenRuns(c))]:[];}
function allCommands(){return flattenRuns().flatMap(r=>(r.commands||[]).map(c=>({...c,owner:r})));}
function commandStatus(c){return parseResult(c)?.status||(c.sent?'accepted':'queued');}
function fit(target){
  if(!target.getDefinitions())return;
  const canvas=target.get('canvas'),rect=canvas.getContainer().getBoundingClientRect();
  if(rect.width>0&&rect.height>0&&canvas.getRootElement()?.children?.length)canvas.zoom('fit-viewport','auto');
}
async function xmlFor(current){
  const path=current?'/api/runs/'+encodeURIComponent(current.id)+'/xml':'/api/processes/'+encodeURIComponent(key)+'/xml';
  const response=await request(path);if(!response.ok)throw Error('流程图加载失败');return prepareXml(await response.text());
}
async function load(processKey,ticket=revision){
  key=processKey;const xml=await xmlFor(run);if(disposed||ticket!==revision)return;
  await viewer.importXML(xml);if(disposed||ticket!==revision)return;
  diagrams=viewer.getDefinitions().diagrams;
  $('scope').replaceChildren(...diagrams.map(d=>new Option(d.plane.bpmnElement.name||d.plane.bpmnElement.id,d.id)));
  const preferred=diagrams.find(d=>d.plane.bpmnElement.id==='normal')||diagrams[0];
  if(preferred){$('scope').value=preferred.id;await viewer.open(preferred.id);if(disposed||ticket!==revision)return;fit(viewer);}
  childRun=null;childId='';childLoading='';$('child-flow').hidden=true;
  paint();
}
async function openScope(id){const ticket=revision;await viewer.open(id);if(disposed||ticket!==revision)return;fit(viewer);paint();}
async function syncChild(ticket=revision){
  const children=flattenRuns().slice(1);
  const desired=[...children].reverse().find(c=>!c.ended)||children[children.length-1];
  if(!desired){childRun=null;childId='';$('child-flow').hidden=true;return;}
  childRun=desired;
  if(childId===desired.id){paint();return;}
  if(childLoading===desired.id)return;
  const id=desired.id;childLoading=id;
  try{
    const xml=await xmlFor(desired);
    if(disposed||ticket!==revision||!flattenRuns().some(c=>c.id===id)||childLoading!==id)return;
    $('child-flow').hidden=false;
    await childViewer.importXML(xml);if(disposed||ticket!==revision)return;
    const diagram=childViewer.getDefinitions().diagrams.find(d=>d.plane.bpmnElement.id==='normal');
    if(diagram)await childViewer.open(diagram.id);
    if(disposed||ticket!==revision||childLoading!==id)return;
    childId=id;$('child-flow-title').textContent=desired.name;
    fit(viewer);fit(childViewer);paint();
  }catch(error){showError(error);}finally{if(childLoading===id)childLoading='';}
}
function paintViewer(target,current){
  if(!target.getDefinitions())return;
  const canvas=target.get('canvas'), registry=target.get('elementRegistry');
  registry.forEach(e=>{for(const marker of ['running','done','interrupted','selected-node'])canvas.removeMarker(e.id,marker);});
  if(!current)return;
  const active=new Set(current.activeActivities||[]), rows=new Map();
  for(const row of current.activities||[]){if(!rows.has(row.id))rows.set(row.id,[]);rows.get(row.id).push(row);}
  for(const [id,items] of rows)if(registry.get(id)){
    const marker=active.has(id)||items.some(i=>!i.ended)?'running':items.some(i=>i.cancelled)?'interrupted':'done';
    canvas.addMarker(id,marker);
  }
  if(picked&&selectedViewer===target&&registry.get(picked))canvas.addMarker(picked,'selected-node');
}
function activeElements(target,current){
  if(!target.getDefinitions())return [];
  return (current?.activeActivities||[]).map(id=>target.get('elementRegistry').get(id))
    .filter(e=>e && /Task$|CallActivity$/.test(e.type));
}
function nodeSvg(target,element){
  const canvas=target.get('canvas'), root=canvas.getContainer().querySelector('svg');
  const svg=document.createElementNS('http://www.w3.org/2000/svg','svg');
  const defs=root?.querySelector('defs');if(defs)svg.append(defs.cloneNode(true));
  const graphic=target.get('elementRegistry').getGraphics(element).cloneNode(true);
  graphic.querySelectorAll('.djs-hit,.djs-outline').forEach(n=>n.remove());
  svg.append(graphic);const label=element.label;
  if(label){const g=target.get('elementRegistry').getGraphics(label);if(g)svg.append(g.cloneNode(true));}
  const x=Math.min(element.x,label?.x??element.x)-12,y=Math.min(element.y,label?.y??element.y)-12;
  const width=Math.max(element.x+element.width,label?label.x+label.width:0)-x+12;
  const height=Math.max(element.y+element.height,label?label.y+label.height:0)-y+12;
  svg.setAttribute('viewBox',`${x} ${y} ${width} ${height}`);return svg;
}
function activityLenses(){
  const target=childRun&&childId===childRun.id?childViewer:viewer,current=target===childViewer?childRun:run;
  const active=activeElements(target,current);$('activity-lenses').replaceChildren();
  if(!active.length){$('activity-lenses').innerHTML='<div class="empty-focus">'+(run?.ended?'本次流程已结束':'等待流程启动')+'<small>执行时在此放大当前活动，支持并行节点。</small></div>';return;}
  for(const element of active){
    const button=document.createElement('button');button.className='activity-lens';
    button.append(nodeSvg(target,element));
    const commands=(current.commands||[]).filter(c=>c.commandNode===element.id);
    const done=commands.filter(c=>commandStatus(c)==='succeeded').length;
    const label=document.createElement('span');label.textContent=element.businessObject.name||element.id;
    const count=document.createElement('small');count.textContent=commands.length?`${done} / ${commands.length} 实例成功`:'等待外部条件';
    button.append(label,count);button.onclick=()=>selectNode(target,element.id);$('activity-lenses').append(button);
  }
}
function paint(){paintViewer(viewer,run);paintViewer(childViewer,childRun);activityLenses();if(picked)showDetails();}
function ownerFor(target){return target===childViewer?childRun:run;}
function selectNode(target,id){
  picked=id;selectedViewer=target;paint();renderDevices();
  $('detail-title').textContent=target.get('elementRegistry').get(id)?.businessObject?.name||id;
  $('expanded-graph').replaceChildren();$('expanded-graph').hidden=true;
  $('detail-dialog').showModal();
}
function showDetails(){
  const owner=ownerFor(selectedViewer),element=selectedViewer.get('elementRegistry').get(picked);
  let html='<strong>'+escape(element?.businessObject?.name||picked)+'</strong><div class="node-id">'+escape(picked)+'</div>';
  const commands=(owner?.commands||[]).filter(c=>c.commandNode===picked),activities=(owner?.activities||[]).filter(a=>a.id===picked);
  $('detail-count').textContent=activities.length?activities.length+' 次进入':'';
  for(const command of commands){const result=parseResult(command),status=commandStatus(command);
    html+='<div class="command"><b>'+escape(command.commandDevice)+'</b><span class="command-status '+escape(status)+'">'+escape(taskLabel[status]||status)+'</span><br>动作：'+escape(command.commandAction)+'<br>命令：'+escape(command.commandId)+(result?.reason?'<br>'+escape(result.reason):'')+'</div>';
  }
  if(!commands.length)html+='<p class="muted">'+(activities.length?'该节点为流程活动或条件等待。':'尚未产生执行记录。')+'</p>';
  $('details').innerHTML=html;
}
function renderRun(){
  renderWaits();
  const state=run?(outcomeLabel[run.outcome]||run.outcome):'流程定义';
  $('outcome').textContent=state;$('diagram-state').textContent=state;
  $('reason').textContent=run?.failureReason||run?.communicationError||'';
  $('run-name').textContent=run?.name||definitions.find(d=>d.key===key)?.name||'尚未启动';
  $('flow-caption').textContent=run?.name||'完整流程';$('run-id').textContent=run?'运行 ID：'+run.id:'';
  const current=[...flattenRuns()].reverse().find(r=>!r.ended)||run;
  const names=(current?.activities||[]).filter(a=>(current.activeActivities||[]).includes(a.id)&&/Task$|Activity$/.test(a.type)).map(a=>a.name||a.id);
  $('run-stage').textContent=[...new Set(names)].join('、')||(run?.ended?'已结束':'—');
  $('run-stage').title=$('run-stage').textContent;
  const commands=allCommands(), finished=commands.filter(c=>['succeeded','failed','interrupted','rejected','cancelled'].includes(commandStatus(c))).length;
  $('command-summary').textContent=commands.length?`${finished} / ${commands.length} 已返回结果`:'暂无命令';
  $('elapsed').textContent=run?duration(new Date(run.ended||Date.now())-new Date(run.started)):'—';
  if(run?.ended)$('details').querySelector('.device-debug-controls')?.remove();
  $('process').disabled=Boolean(run);onRunChange(run);paint();renderDevices();
}
function duration(ms){if(!Number.isFinite(ms))return '—';const seconds=Math.max(0,Math.floor(ms/1000));return `${Math.floor(seconds/60)} 分 ${seconds%60} 秒`;}
for(const target of [viewer,childViewer]){
  target.on('element.click',e=>{if(!/SequenceFlow|label|Process/.test(e.element.type))selectNode(target,e.element.id);});
  target.on('element.dblclick',async e=>{
    const diagram=target.getDefinitions().diagrams.find(d=>d.plane.bpmnElement.id===e.element.id);
    if(diagram){await target.open(diagram.id);fit(target);paint();}
  });
}
$('scope').onchange=()=>openScope($('scope').value).catch(showError);
$('fit').onclick=()=>{fit(viewer);fit(childViewer);};
$('process').onchange=()=>setRun('');
function makeCard(device){
  const card=document.createElement('article');card.className='device';card.dataset.device=device.id;
  card.innerHTML='<div class="device-head"><div><strong class="device-name"></strong><span class="component-name"></span></div><span class="device-state"></span></div><button class="chart-button" title="展开完整状态机"><div class="state-chart"></div><span class="expand-hint">展开状态机 ↗</span></button><div class="device-action"></div><div class="device-progress"></div><div class="command-trail"></div>';
  card.querySelector('.chart-button').onclick=()=>expandDevice(card.currentDevice);
  cardNodes.set(device.id,card);return card;
}
function renderDevices(){
  const catalogue=new Map(historyDevices(deviceCache).map(d=>[d.id,d]));
  for(const p of run?.participants||[])if(!catalogue.has(p.id))catalogue.set(p.id,{...p,connected:false});
  const participantIds=new Set((run?.participants||[]).map(p=>p.id));
  const commands=allCommands();const onlyRelated=$('only-related').checked&&run&&participantIds.size;
  const devices=[...catalogue.values()].filter(d=>d.component!=='context'&&(!onlyRelated||participantIds.has(d.id)));
  const present=new Set(devices.map(d=>d.id));
  for(const [id,card] of cardNodes)if(!present.has(id)){card.remove();cardNodes.delete(id);}
  let failed=0,working=0;
  for(const device of devices){
    let card=cardNodes.get(device.id);if(!card){card=makeCard(device);$('devices').append(card);}
    card.currentDevice=device;
    const s=device.snapshot||{},mine=commands.filter(c=>c.commandDevice===device.id),latest=mine[mine.length-1];
    const status=latest?commandStatus(latest):'',done=mine.filter(c=>commandStatus(c)==='succeeded').length;
    const failure=['failed','interrupted','rejected','cancelled'].includes(status),active=device.connected&&Boolean(s.active_command_id);
    if(failure||s.outer_state==='fault')failed++;if(active)working++;
    card.classList.toggle('is-offline',!device.connected);card.classList.toggle('is-failed',failure||s.outer_state==='fault');card.classList.toggle('is-working',active);
    const selected=commands.filter(c=>c.commandNode===picked&&c.owner.id===ownerFor(selectedViewer)?.id);
    card.classList.toggle('node-related',selected.some(c=>c.commandDevice===device.id));
    card.querySelector('.device-name').textContent=device.id;card.querySelector('.component-name').textContent=componentLabel[device.component]||device.component;
    const badge=card.querySelector('.device-state');badge.textContent=device.unrecorded?'未记录':device.connected?(s.outer_state_label||s.outer_state||'状态未确认'):'离线';
    badge.className='device-state '+(device.connected?'online':'offline');
    const chart=card.querySelector('.state-chart');
    StateCharts.update(chart,modelKey(s),{...s,connected:device.connected});
    if(device.unrecorded)chart.textContent='本次运行未记录设备状态';
    const action=s.active_action||(latest?.commandAction), actionText=StateCharts.action(modelKey(s),action);
    card.querySelector('.device-action').textContent=action?(!device.connected?'最后已知动作 · ':active?'正在执行 · ':'最近动作 · ')+actionText:'等待本流程调度';
    card.querySelector('.device-progress').textContent=mine.length?`本流程 ${done} / ${mine.length} 条已登记命令成功 · ${taskLabel[status]||({'queued':'待发送'})[status]||status}`:'本流程尚未下发命令';
    const trail=card.querySelector('.command-trail');const sig=JSON.stringify(mine.map(c=>[c.commandId,commandStatus(c)]));
    if(trail.signature!==sig){trail.signature=sig;trail.innerHTML=mine.map(c=>'<span class="trail-step '+escape(commandStatus(c))+'" title="'+escape(StateCharts.action(modelKey(s),c.commandAction)+' · '+(taskLabel[commandStatus(c)]||'待发送'))+'"></span>').join('');}
  }
  $('device-summary').textContent=`${devices.length} 台设备 · ${working} 台动作中${failed?' · '+failed+' 台需关注':''}`;
  $('device-hint').textContent=run?.ended?'设备图来自本次运行记录的快照 · 命令进度来自历史记录':'全貌固定展示 · 当前状态局部放大 · 点击查看完整图';
}
async function refreshDevices(ticket=revision){
  const [connection,devices]=await Promise.all([api('/connection'),api('/devices')]);
  if(disposed||ticket!==revision)return;
  $('connection').textContent=connection.connected?'事件已连接':connection.error||'Tango 未连接';
  deviceCache=devices;renderDevices();renderContext(historyDevices(devices).filter(d=>d.component==='context'));
}
function renderContext(devices){
  const signature=JSON.stringify([Boolean(run?.ended),devices.map(d=>[d.id,d.connected,d.snapshot?.conditions])]);
  const host=$('context-devices');if(host.signature===signature)return;host.signature=signature;
  let met=0,total=0;
  host.innerHTML=devices.map(d=>'<div class="conditions"><div class="conditions-title">'+escape(d.id)+' · '+(d.connected?'已连接':'离线')+'</div>'+Object.entries(d.snapshot?.conditions||{}).map(([name,value])=>{
    total++;if(value)met++;
    return '<label class="condition"><input type="checkbox" data-device="'+escape(d.id)+'" data-condition="'+escape(name)+'"'+(value?' checked':'')+(!d.connected||run?.ended?' disabled':'')+'><span>'+escape(conditionLabel[name]||name)+'</span><i>'+(value?'满足':'未满足')+'</i></label>';
  }).join('')+'</div>').join('');
  $('context-summary').textContent=`${met} / ${total} 条件满足`;
}
$('context-devices').addEventListener('change',async e=>{
  const input=e.target;if(!input.matches('input[data-condition]')||run?.ended||changing||disposed||!loaded)return;input.disabled=true;
  try{await api('/devices/'+encodeURIComponent(input.dataset.device)+'/conditions',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({[input.dataset.condition]:input.checked})});showError(null);await refreshTrace();await refreshDevices();}
  catch(error){input.checked=!input.checked;showError(error);}finally{input.disabled=false;}
});
$('only-related').onchange=renderDevices;

function renderDeviceControls(device){
 if(run?.ended||!device.connected)return;
 const s=device.snapshot||{},metadata=StateCharts.capabilities(modelKey(s));
 const controls=document.createElement('div');controls.className='device-debug-controls';
 const actions=Object.entries(metadata.actions||{}).filter(([key,spec])=>Object.prototype.hasOwnProperty.call(spec.requests||{},s.inner_state||s.state_id));
 for(const [key,spec]of actions){const button=document.createElement('button');button.textContent=spec.label||key;button.disabled=Boolean(s.active_command_id);button.onclick=()=>operate('commands',{command_id:crypto.randomUUID(),action:key,parameters:{}});controls.append(button);}
 if(s.active_command_id)for(const [outcome,label]of [['success','成功反馈'],['failure','失败反馈']]){const button=document.createElement('button');button.textContent=label;button.onclick=()=>operate('simulate',{command_id:s.active_command_id,outcome});controls.append(button);}
 const confirmations=Object.entries(metadata.confirmations||{}).filter(([key,c])=>(c.source_outer||[]).includes(s.outer_state));
 if(confirmations.length){const select=document.createElement('select');for(const [key,c]of confirmations)select.add(new Option(c.label,key));const button=document.createElement('button');button.textContent='注入确认事件';button.onclick=()=>operate('events',{event_id:crypto.randomUUID(),event:select.value,observed_outer_state:metadata.confirmations[select.value].observation});controls.append(select,button);}
 async function operate(operation,payload){if(disposed||changing||run?.ended||!loaded)return;const ticket=revision;const fields=[...controls.querySelectorAll('button,select')].map(c=>[c,c.disabled]);fields.forEach(([c])=>c.disabled=true);try{await api('/workbench/devices/'+encodeURIComponent(device.id)+'/'+operation,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(payload)});await refreshDevices(ticket);if(disposed||ticket!==revision)return;$('detail-dialog').close();expandDevice(deviceCache.find(d=>d.id===device.id));showError(null);}catch(error){showError(error);}finally{fields.forEach(([c,disabled])=>c.disabled=disabled);}}
 $('details').prepend(controls);
}

function clearDetailViewer(){if(detailViewer){detailViewer.destroy();detailViewer=null;}}
function expandDevice(device){
  if(!device)return;clearDetailViewer();picked=null;const s=device.snapshot||{};
  $('detail-title').textContent=device.id+' · '+(componentLabel[device.component]||device.component);
  $('expanded-graph').hidden=false;$('expanded-graph').replaceChildren();StateCharts.expand($('expanded-graph'),modelKey(s),{...s,connected:device.connected});
  $('details').innerHTML='<p>'+ (run?.ended?'本次运行记录状态：':'当前实时状态：')+escape((s.outer_state_label||s.outer_state||'—')+' / '+(s.inner_state_label||s.state_id||'—'))+'</p><p class="muted">滚轮缩放 · 拖动平移 · 双击复位</p>';
  for(const c of allCommands().filter(c=>c.commandDevice===device.id))$('details').innerHTML+='<div class="command">'+escape(StateCharts.action(modelKey(s),c.commandAction))+'<span class="command-status '+escape(commandStatus(c))+'">'+escape(taskLabel[commandStatus(c)]||'待发送')+'</span><div class="node-id">'+escape(c.commandId)+'</div></div>';
  renderDeviceControls(device);$('detail-count').textContent='';$('detail-dialog').showModal();
}
$('expand-flow').onclick=async()=>{
  clearDetailViewer();picked=null;$('detail-title').textContent=childRun?.name||run?.name||'流程图';
  $('expanded-graph').hidden=false;$('expanded-graph').replaceChildren();$('details').replaceChildren();$('detail-count').textContent='';$('detail-dialog').showModal();
  detailViewer=new BpmnViewer({container:$('expanded-graph')});
  try{
    const current=childRun||run,target=detailViewer,ticket=revision;const xml=await xmlFor(current);
    if(disposed||ticket!==revision||detailViewer!==target)return;
    await target.importXML(xml);if(disposed||ticket!==revision||detailViewer!==target)return;
    const diagram=target.getDefinitions().diagrams.find(d=>d.plane.bpmnElement.id==='normal');if(diagram)await target.open(diagram.id);
    if(disposed||ticket!==revision||detailViewer!==target)return;
    fit(target);paintViewer(target,current);
  }catch(error){showError(error);}
};
$('close-detail').onclick=()=>$('detail-dialog').close();
$('detail-dialog').addEventListener('close',()=>{if(disposed||$('detail-dialog').open)return;clearDetailViewer();picked=null;paint();renderDevices();});
const resize=new ResizeObserver(()=>{if(!disposed){fit(viewer);fit(childViewer);}});resize.observe($('canvas'));
function tick(){
  if(disposed||changing||!loaded)return Promise.resolve();
  if(busy)return polling;
  busy=true;const ticket=revision;
  polling=(async()=>{
    const next=run?await api('/runs/'+encodeURIComponent(run.id)):null;
    if(disposed||ticket!==revision)return;
    run=next;
    await refreshTrace(ticket);
    if(disposed||ticket!==revision)return;
    renderRun();await syncChild(ticket);
    if(disposed||ticket!==revision)return;
    await refreshDevices(ticket);
  })().catch(showError).finally(()=>{busy=false;});
  return polling;
}
const ready=StateCharts.load().then(async()=>{
  definitions=await api('/processes');
  $('process').replaceChildren(...definitions.map(d=>new Option(d.name,d.key)));
  key=definitions[0]?.key||'';
}).catch(error=>{showError(error);throw error;});
function setRun(id){
  const ticket=++revision;changing=true;loaded=false;onRunChange(null);$('detail-dialog').close();
  selection=selection.catch(()=>{}).then(async()=>{
    await ready;await polling;
    if(disposed||ticket!==revision)return;
    const next=id?await api('/runs/'+encodeURIComponent(id)):null;
    if(disposed||ticket!==revision)return;
    $('detail-dialog').close();picked=null;run=next;traceEvents=[];
    $('trace-list').replaceChildren();$('trace-count').textContent='';
    key=run?.key||$('process').value||key;
    $('process').value=key;
    if(key)await load(key,ticket);
    else viewer.clear();
    if(disposed||ticket!==revision)return;
    await refreshTrace(ticket);
    if(disposed||ticket!==revision)return;
    loaded=true;renderRun();await syncChild(ticket);
    if(disposed||ticket!==revision)return;
    await refreshDevices(ticket);showError(null);
  }).catch(error=>{
    if(disposed||ticket!==revision)return;
    loaded=false;run=null;childRun=null;picked=null;deviceCache=[];
    viewer.clear();childViewer.clear();$('child-flow').hidden=true;
    renderRun();renderContext([]);showError(error);
  }).finally(()=>{if(ticket===revision)changing=false;});
  return selection;
}
const timer=window.setInterval(tick,1000);
return {
  setRun,
  refresh:tick,
  destroy(){
    disposed=true;revision++;clearInterval(timer);requests.abort();resize.disconnect();
    StateCharts.destroy();$('detail-dialog').close();clearDetailViewer();viewer.destroy();childViewer.destroy();cardNodes.clear();
  }
};
}
