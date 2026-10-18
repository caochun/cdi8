'use strict';
const $ = id => document.getElementById(id);
const viewer = new BpmnJS({container:'#canvas'});
const childViewer = new BpmnJS({container:'#child-canvas'});
let definitions=[], diagrams=[], key='', run=null, picked=null, busy=false, runRecords=[];
let childRun=null, childId='', childLoading='', detailViewer=null, selectedViewer=viewer, deviceCache=[];
const cardNodes=new Map();
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
  const response=await fetch('/api'+path,options);
  if(!response.ok) {let body={};try{body=await response.json();}catch(_){} throw Error(body.error||response.statusText);}
  return response.json();
}
function showError(error){$('error').textContent=error?.message||'';$('error').hidden=!error;}
function parseResult(command){if(!command?.commandResult)return null;if(typeof command.commandResult==='object')return command.commandResult;try{return JSON.parse(command.commandResult);}catch(_){return null;}}
function flattenRuns(current=run){return current?[current,...(current.children||[]).flatMap(c=>flattenRuns(c))]:[];}
function allCommands(){return flattenRuns().flatMap(r=>(r.commands||[]).map(c=>({...c,owner:r})));}
function commandStatus(c){return parseResult(c)?.status||(c.sent?'accepted':'queued');}
function setSourceVisibility(){
  const uses=['optical_frontend_preparation','formal_emission','post_emission_processing','emission_task_lifecycle'].includes($('process').value);
  $('source-options').hidden=!uses;for(const id of ['seed','shg'])$(id).disabled=!uses||Boolean(run&&!run.ended);
}
function fit(target){if(target.getDefinitions())target.get('canvas').zoom('fit-viewport','auto');}
async function xmlFor(current){
  const path=current?'/api/runs/'+encodeURIComponent(current.id)+'/xml':'/api/processes/'+encodeURIComponent(key)+'/xml';
  const response=await fetch(path);if(!response.ok)throw Error('流程图加载失败');return response.text();
}
async function load(processKey){
  key=processKey;await viewer.importXML(await xmlFor(run));
  diagrams=viewer.getDefinitions().diagrams;
  $('scope').replaceChildren(...diagrams.map(d=>new Option(d.plane.bpmnElement.name||d.plane.bpmnElement.id,d.id)));
  const preferred=diagrams.find(d=>d.plane.bpmnElement.id==='normal')||diagrams[0];
  if(preferred){$('scope').value=preferred.id;await viewer.open(preferred.id);fit(viewer);}
  childRun=null;childId='';childLoading='';$('child-flow').hidden=true;
  setSourceVisibility();paint();
}
async function openScope(id){await viewer.open(id);fit(viewer);paint();}
async function syncChild(){
  const children=flattenRuns().slice(1);
  const desired=[...children].reverse().find(c=>!c.ended)||children[children.length-1];
  if(!desired){childRun=null;childId='';$('child-flow').hidden=true;return;}
  childRun=desired;
  if(childId===desired.id){paint();return;}
  if(childLoading===desired.id)return;
  const id=desired.id;childLoading=id;
  try{
    const xml=await xmlFor(desired);
    if(!flattenRuns().some(c=>c.id===id)||childLoading!==id)return;
    $('child-flow').hidden=false;
    await childViewer.importXML(xml);
    const diagram=childViewer.getDefinitions().diagrams.find(d=>d.plane.bpmnElement.id==='normal');
    if(diagram)await childViewer.open(diagram.id);
    if(childLoading!==id)return;
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
  $('stop').disabled=!run||Boolean(run.ended);$('start').disabled=Boolean(run&&!run.ended);$('process').disabled=Boolean(run&&!run.ended);
  $('runs').value=run?.id||'';setSourceVisibility();paint();syncChild();renderDevices();
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
$('process').onchange=async()=>{try{run=null;picked=null;showError(null);await load($('process').value);await refreshHistory(false);renderRun();}catch(e){showError(e);}};
$('start').onclick=async()=>{
  try{
    $('start').disabled=true;
    const sources=$('source-options').hidden?[]:['seed','shg'].filter(id=>$(id).checked);
    run=await api('/runs',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({processKey:$('process').value,sources})});
    showError(null);picked=null;await load(run.key);await refreshHistory(false);renderRun();
  }catch(e){showError(e);renderRun();}
};
$('stop').onclick=async()=>{try{run=await api('/runs/'+encodeURIComponent(run.id)+'/stop',{method:'POST'});showError(null);renderRun();await refreshHistory(false);}catch(e){showError(e);}};
$('runs').onchange=async()=>{
  try{
    picked=null;
    run=$('runs').value?await api('/runs/'+encodeURIComponent($('runs').value)):null;
    if(run)$('process').value=run.key;
    await load($('process').value);showError(null);renderRun();
  }catch(e){showError(e);}
};
async function refreshHistory(selectLatest){
  runRecords=await api('/runs');const selected=run?.id||'';
  $('runs').replaceChildren(new Option('选择运行实例',''),...runRecords.map(r=>new Option(r.name+' · '+new Date(r.started).toLocaleString(),r.id)));
  const wanted=selected||(selectLatest?runRecords.find(r=>!key||r.key===key)?.id:'');if(wanted)$('runs').value=wanted;
  if(selectLatest&&!run&&wanted){run=await api('/runs/'+encodeURIComponent(wanted));$('process').value=run.key;await load(run.key);renderRun();}
}
function makeCard(device){
  const card=document.createElement('article');card.className='device';card.dataset.device=device.id;
  card.innerHTML='<div class="device-head"><div><strong class="device-name"></strong><span class="component-name"></span></div><span class="device-state"></span></div><button class="chart-button" title="展开完整状态机"><div class="state-chart"></div><span class="expand-hint">展开状态机 ↗</span></button><div class="device-action"></div><div class="device-progress"></div><div class="command-trail"></div>';
  card.querySelector('.chart-button').onclick=()=>expandDevice(card.currentDevice);
  cardNodes.set(device.id,card);return card;
}
function renderDevices(){
  if(!deviceCache.length&&!run?.participants?.length)return;
  const catalogue=new Map(deviceCache.map(d=>[d.id,d]));
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
    const badge=card.querySelector('.device-state');badge.textContent=device.connected?(s.outer_state_label||s.outer_state||'状态未确认'):'离线';
    badge.className='device-state '+(device.connected?'online':'offline');
    StateCharts.update(card.querySelector('.state-chart'),s.model,{...s,connected:device.connected});
    const action=s.active_action||(latest?.commandAction), actionText=StateCharts.action(s.model,action);
    card.querySelector('.device-action').textContent=action?(!device.connected?'最后已知动作 · ':active?'正在执行 · ':'最近动作 · ')+actionText:'等待本流程调度';
    card.querySelector('.device-progress').textContent=mine.length?`本流程 ${done} / ${mine.length} 条已登记命令成功 · ${taskLabel[status]||({'queued':'待发送'})[status]||status}`:'本流程尚未下发命令';
    const trail=card.querySelector('.command-trail');const sig=JSON.stringify(mine.map(c=>[c.commandId,commandStatus(c)]));
    if(trail.signature!==sig){trail.signature=sig;trail.innerHTML=mine.map(c=>'<span class="trail-step '+escape(commandStatus(c))+'" title="'+escape(StateCharts.action(s.model,c.commandAction)+' · '+(taskLabel[commandStatus(c)]||'待发送'))+'"></span>').join('');}
  }
  $('device-summary').textContent=`${devices.length} 台设备 · ${working} 台动作中${failed?' · '+failed+' 台需关注':''}`;
  $('device-hint').textContent=run?.ended?'当前设备实时状态 · 下方命令进度来自所选历史运行':'全貌固定展示 · 当前状态局部放大 · 点击查看完整图';
}
async function refreshDevices(){
  const [connection,devices]=await Promise.all([api('/connection'),api('/devices')]);
  $('connection').textContent=connection.connected?'事件已连接':connection.error||'Tango 未连接';
  deviceCache=devices;renderDevices();renderContext(devices.filter(d=>d.component==='context'));
}
function renderContext(devices){
  const signature=JSON.stringify(devices.map(d=>[d.id,d.connected,d.snapshot?.conditions]));
  const host=$('context-devices');if(host.signature===signature)return;host.signature=signature;
  let met=0,total=0;
  host.innerHTML=devices.map(d=>'<div class="conditions"><div class="conditions-title">'+escape(d.id)+' · '+(d.connected?'已连接':'离线')+'</div>'+Object.entries(d.snapshot?.conditions||{}).map(([name,value])=>{
    total++;if(value)met++;
    return '<label class="condition"><input type="checkbox" data-device="'+escape(d.id)+'" data-condition="'+escape(name)+'"'+(value?' checked':'')+(!d.connected?' disabled':'')+'><span>'+escape(conditionLabel[name]||name)+'</span><i>'+(value?'满足':'未满足')+'</i></label>';
  }).join('')+'</div>').join('');
  $('context-summary').textContent=`${met} / ${total} 条件满足`;
}
$('context-devices').addEventListener('change',async e=>{
  const input=e.target;if(!input.matches('input[data-condition]'))return;input.disabled=true;
  try{await api('/devices/'+encodeURIComponent(input.dataset.device)+'/conditions',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({[input.dataset.condition]:input.checked})});showError(null);await refreshDevices();}
  catch(error){input.checked=!input.checked;showError(error);}finally{input.disabled=false;}
});
$('only-related').onchange=renderDevices;
function clearDetailViewer(){if(detailViewer){detailViewer.destroy();detailViewer=null;}}
function expandDevice(device){
  if(!device)return;clearDetailViewer();picked=null;const s=device.snapshot||{};
  $('detail-title').textContent=device.id+' · '+(componentLabel[device.component]||device.component);
  $('expanded-graph').hidden=false;$('expanded-graph').replaceChildren();StateCharts.expand($('expanded-graph'),s.model,{...s,connected:device.connected});
  $('details').innerHTML='<p>当前实时状态：'+escape((s.outer_state_label||s.outer_state||'—')+' / '+(s.inner_state_label||s.state_id||'—'))+'</p><p class="muted">滚轮缩放 · 拖动平移 · 双击复位</p>';
  for(const c of allCommands().filter(c=>c.commandDevice===device.id))$('details').innerHTML+='<div class="command">'+escape(StateCharts.action(s.model,c.commandAction))+'<span class="command-status '+escape(commandStatus(c))+'">'+escape(taskLabel[commandStatus(c)]||'待发送')+'</span><div class="node-id">'+escape(c.commandId)+'</div></div>';
  $('detail-count').textContent='';$('detail-dialog').showModal();
}
$('expand-flow').onclick=async()=>{
  clearDetailViewer();picked=null;$('detail-title').textContent=childRun?.name||run?.name||'流程图';
  $('expanded-graph').hidden=false;$('expanded-graph').replaceChildren();$('details').replaceChildren();$('detail-count').textContent='';$('detail-dialog').showModal();
  detailViewer=new BpmnJS({container:'#expanded-graph'});
  try{
    const current=childRun||run;await detailViewer.importXML(await xmlFor(current));
    const diagram=detailViewer.getDefinitions().diagrams.find(d=>d.plane.bpmnElement.id==='normal');if(diagram)await detailViewer.open(diagram.id);
    fit(detailViewer);paintViewer(detailViewer,current);
  }catch(error){showError(error);}
};
$('close-detail').onclick=()=>$('detail-dialog').close();
$('detail-dialog').addEventListener('close',()=>{clearDetailViewer();picked=null;paint();renderDevices();});
new ResizeObserver(()=>{fit(viewer);fit(childViewer);}).observe($('canvas'));
async function tick(){
  if(busy)return;busy=true;
  try{await refreshHistory(!run);if(run){run=await api('/runs/'+encodeURIComponent(run.id));renderRun();}await refreshDevices();}
  catch(error){showError(error);}finally{busy=false;}
}
(async()=>{
  try{
    await StateCharts.load();definitions=await api('/processes');
    $('process').replaceChildren(...definitions.map(d=>new Option(d.name,d.key)));
    $('process').value=definitions.some(d=>d.key==='emission_task_lifecycle')?'emission_task_lifecycle':definitions[0]?.key;
    await load($('process').value);await refreshHistory(true);renderRun();await refreshDevices();
  }catch(error){showError(error);}setInterval(tick,1000);
})();
