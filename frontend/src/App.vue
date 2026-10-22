<script setup lang="ts">
import {computed,onMounted,onBeforeUnmount,ref,watch,nextTick} from 'vue';
import XmlEditor from './components/editors/XmlEditor.vue';import Diagram from './components/diagrams/Diagram.vue';
import RuntimeView from './components/runtime/RuntimeView.vue';
import RunPreparation from './components/preparation/RunPreparation.vue';
import {modelLabel} from './model-label';
import {emptyConfiguration,type RunConfiguration} from './components/preparation/configuration';
import {api,type Model} from './api';import {scxmlTemplate,bpmnTemplate} from './templates';
const runtimeEntry=location.pathname==='/runtime.html'||new URLSearchParams(location.search).has('run');
const tab=ref(runtimeEntry?'runs':'models'),models=ref<Model[]>([]),selectedId=ref(''),xml=ref(''),editor=ref<InstanceType<typeof XmlEditor>>();
const error=ref(''),notice=ref(''),busy=ref(false),connection=ref(false),selectedNode=ref(''),diagnostics=ref<any[]>([]),validation=ref<any>(null);
const previewXml=ref('');const previewError=ref(''),previewStale=ref(false),validatedXml=ref(''),savedXml=ref(''),version=ref('draft'),filter=ref('all');
const trial=ref<any>(null),trialAction=ref(''),trialParameters=ref('{}'),trialEvent=ref(''),trialAuto=ref(true),trialDelay=ref(500);
const config=ref<RunConfiguration>(emptyConfiguration());
const preparationOperation=ref(''),preparationChecked=ref(false),preparationError=ref(''),liveEnvironment=ref<any>(null),liveDevices=ref<any[]>([]);let preparationRequest:Promise<void>|undefined,preparationTicket=0;
const activeRun=computed(()=>runs.value.some(r=>!r.ended));
const currentRun=ref<any>(null);const runs=ref<any[]>([]),runId=ref(''),runtimeView=ref<InstanceType<typeof RuntimeView>>(),newDialog=ref<HTMLDialogElement>(),newName=ref(''),newKind=ref<'scxml'|'bpmn'>('scxml');
const preparationView=ref<InstanceType<typeof RunPreparation>>(),modelSearch=ref('');
const trialView=ref('control');const editorWidth=ref(50),splitDragging=ref(false);let splitGrid:HTMLElement|null=null;let previewTimer:ReturnType<typeof setTimeout>,saveTimer:ReturnType<typeof setTimeout>,poll:ReturnType<typeof setInterval>,validateTicket=0;
const model=computed(()=>models.value.find(m=>m.id===selectedId.value));const currentRevision=computed(()=>model.value?.revisions.at(-1));
const modelLabels=computed(()=>new Map(models.value.map(m=>[m.id,modelLabel(m)])));
const visibleModels=computed(()=>models.value.filter(m=>(filter.value==='all'||m.kind===filter.value)&&[m.name,modelLabels.value.get(m.id)].join(' ').toLowerCase().includes(modelSearch.value.trim().toLowerCase())));
const selectedModelLabel=computed(()=>model.value?modelLabel(model.value,xml.value):'');
const runnableRevision=computed(()=>version.value!=='draft'?version.value:model.value?.revisions.slice().reverse().find(r=>r.xml===xml.value)?.id||'');
const viewingVersion=computed(()=>version.value!=='draft');
const draftChanged=computed(()=>!viewingVersion.value&&savedXml.value!==xml.value);
const canTrial=computed(()=>validation.value?.valid&&validatedXml.value===xml.value&&model.value?.kind==='scxml');
const trialMatches=computed(()=>trial.value?.xml===xml.value);
const diagramState=computed(()=>trialMatches.value?trial.value?.snapshot.state_id:undefined);
const availableActions=computed(()=>trial.value?.availableActions||[]);
const availableEvents=computed(()=>Object.entries(trial.value?.capabilities.confirmations||{}).filter(([,c]:any)=>(c.source_outer||[]).includes(trial.value?.snapshot.outer_state)));
function fail(e:any){error.value=e.message||String(e);}
async function action(fn:()=>Promise<void>){if(busy.value)return;busy.value=true;error.value='';notice.value='';try{await fn();}catch(e){fail(e);}finally{busy.value=false;}}
function revisionLabel(id:string){return id?id.slice(0,8):'未保存版本';}
async function refreshModels(){models.value=await api('/workbench/models');}
function selectModel(id:string){if(id===selectedId.value)return;clearTimeout(saveTimer);if(model.value&&draftChanged.value){model.value.draft=xml.value;void persist(model.value,xml.value).catch(fail);}selectedId.value=id;const chosen=models.value.find(m=>m.id===id)!;xml.value=chosen.draft;previewXml.value=chosen.draft;savedXml.value=chosen.draft;version.value='draft';selectedNode.value='';trial.value=null;trialView.value='control';trialAuto.value=true;validation.value=null;diagnostics.value=[];previewError.value='';queueValidation();}
function edit(value:string){xml.value=value;version.value='draft';previewStale.value=true;queueValidation();clearTimeout(saveTimer);const current=model.value;if(current)saveTimer=setTimeout(()=>persist(current,value).catch(fail),1400);}
const saveQueues=new Map<string,Promise<any>>();
async function persist(current:Model,value:string){const previous=saveQueues.get(current.id)||Promise.resolve();const pending=previous.catch(()=>{}).then(()=>persistNow(current,value));saveQueues.set(current.id,pending);return pending;}
async function persistNow(current:Model,value:string){const saved=await api('/workbench/models',{id:current.id,name:current.name,kind:current.kind,xml:value});const index=models.value.findIndex(m=>m.id===current.id);if(index>=0)models.value[index]=saved;if(selectedId.value===current.id)savedXml.value=value;}
function queueValidation(){clearTimeout(previewTimer);const ticket=++validateTicket;previewTimer=setTimeout(()=>{previewXml.value=xml.value;validate(ticket);},650);}
async function validate(ticket=++validateTicket){const current=model.value;if(!current)return;const value=xml.value;try{const result=await api('/workbench/validate',{kind:current.kind,xml:value});if(ticket!==validateTicket||current.id!==selectedId.value||value!==xml.value)return;validation.value=result;diagnostics.value=result.diagnostics;validatedXml.value=value;}catch(e){if(ticket===validateTicket)fail(e);}}
async function saveVersion(){const current=model.value;if(!current)return;clearTimeout(saveTimer);await persist(current,xml.value);const revision=await api('/workbench/models/'+current.id+'/versions',{});await refreshModels();if(selectedId.value===current.id)version.value=revision.id;notice.value='已保存不可变版本 '+revisionLabel(revision.id);await validate();}
function chooseVersion(id:string){
  const current=model.value;if(!current)return;
  clearTimeout(saveTimer);if(draftChanged.value){current.draft=xml.value;void persist(current,xml.value).catch(fail);}
  const value=id==='draft'?current.draft:current.revisions.find(r=>r.id===id)?.xml;
  if(value!==undefined){version.value=id;xml.value=value;validation.value=null;diagnostics.value=[];queueValidation();previewStale.value=true;}
}
async function prepareModel(){
  if(!model.value||!runnableRevision.value)return;
  await nextTick();preparationView.value?.selectEntry(model.value.id,runnableRevision.value);switchTab('config');
}
function runLabel(run:any){return (run.ended?'历史 · ':'运行中 · ')+run.name+' · '+new Date(run.started).toLocaleString();}

function exportXml(){const blob=new Blob([xml.value],{type:'application/xml'}),url=URL.createObjectURL(blob),link=document.createElement('a');link.href=url;link.download=model.value?.name||'model.xml';link.click();URL.revokeObjectURL(url);}
async function createModel(){const name=newName.value.trim();if(!name)throw Error('请输入文件名');const saved=await api('/workbench/models',{name,kind:newKind.value,xml:newKind.value==='scxml'?scxmlTemplate:bpmnTemplate});await refreshModels();selectModel(saved.id);newDialog.value?.close();}
async function importFile(event:Event){const input=event.target as HTMLInputElement,file=input.files?.[0];if(!file)return;await action(async()=>{const value=await file.text();const doc=new DOMParser().parseFromString(value,'application/xml');const kind=doc.documentElement.localName==='scxml'?'scxml':'bpmn';const saved=await api('/workbench/models',{name:file.name,kind,xml:value});await refreshModels();selectModel(saved.id);});input.value='';}
async function createTrial(){trial.value=await api('/workbench/trials',{xml:xml.value});trialAction.value=trial.value.availableActions[0]||'';trialEvent.value=Object.keys(trial.value.capabilities.confirmations)[0]||'';notice.value='已创建独立试运行，上一轮记录保留在本地数据目录';}
async function trialCommand(){const session=trial.value.id;const value=await api('/workbench/trials/'+session+'/commands',{action:trialAction.value,parameters:JSON.parse(trialParameters.value)});trial.value=value;trialAction.value=value.availableActions[0]||'';if(trialAuto.value&&value.snapshot.active_command_id){const command=value.snapshot.active_command_id;setTimeout(()=>{if(trial.value?.id===session&&trial.value.snapshot.active_command_id===command)action(()=>trialComplete('success'));},trialDelay.value);}}
async function sendTrialAction(actionId:string){trialAction.value=actionId;await action(trialCommand);}
async function trialComplete(outcome:string){trial.value=await api('/workbench/trials/'+trial.value.id+'/simulate',{command_id:trial.value.snapshot.active_command_id,outcome});trialAction.value=trial.value.availableActions[0]||'';}
async function injectTrial(){const confirmation=trial.value.capabilities.confirmations[trialEvent.value];trial.value=await api('/workbench/trials/'+trial.value.id+'/events',{event_id:crypto.randomUUID(),event:trialEvent.value,observed_outer_state:confirmation.observation});trialAction.value=trial.value.availableActions[0]||'';}
async function preparationAction(operation:string,value:RunConfiguration){
  preparationOperation.value=operation;
  await action(async()=>{
    if(operation==='save'){config.value=await api('/workbench/configuration',value);notice.value='运行方案已保存';}
    if(operation==='apply'){
      preparationTicket++;preparationChecked.value=false;
      try{config.value=await api('/workbench/environment',value);notice.value='模拟设备已创建，并恢复到所选状态机的初始状态';}
      finally{await pollConnection();await checkPreparation(true);}
    }
    if(operation==='start'){
      const run=await api('/workbench/runs',value);config.value=value;runId.value=run.id;tab.value='runs';await refreshRuns();
    }
  });
  preparationOperation.value='';
}
function checkPreparation(force=false):Promise<void>{
  if(preparationRequest)return force?preparationRequest.then(()=>checkPreparation()):preparationRequest;
  const ticket=preparationTicket;
  preparationRequest=(async()=>{
    try{
      const [environment,devices,records]=await Promise.all([api('/workbench/environment'),api('/devices'),api('/runs')]);
      if(ticket!==preparationTicket)return;
      liveEnvironment.value=environment;liveDevices.value=devices;runs.value=records;preparationError.value='';
    }catch(e){if(ticket===preparationTicket)preparationError.value=(e as Error).message;}
    finally{preparationRequest=undefined;if(ticket===preparationTicket)preparationChecked.value=true;}
  })();
  return preparationRequest;
}
async function refreshRuns(){runs.value=await api('/runs');}
async function stopRun(){currentRun.value=await api('/runs/'+encodeURIComponent(runId.value)+'/stop',{});notice.value='流程已停止，设备已受理动作仍由组件端完成';await refreshRuns();await runtimeView.value?.refresh();}
async function pollConnection(){try{connection.value=(await api('/connection')).connected;}catch{connection.value=false;}}
function switchTab(value:string){tab.value=value;if(value==='runs')refreshRuns().catch(fail);}
function updateEditorWidth(value:number){editorWidth.value=Math.min(75,Math.max(25,Math.round(value*10)/10));}
function startSplit(event:PointerEvent){if(window.matchMedia('(max-width: 760px)').matches)return;const separator=event.currentTarget as HTMLElement;splitGrid=separator.parentElement;const rect=splitGrid?.getBoundingClientRect();if(!rect)return;splitDragging.value=true;separator.setPointerCapture?.(event.pointerId);window.addEventListener('pointermove',moveSplit);window.addEventListener('pointerup',stopSplit,{once:true});window.addEventListener('pointercancel',stopSplit,{once:true});event.preventDefault();}
function moveSplit(event:PointerEvent){if(!splitDragging.value||!splitGrid)return;const rect=splitGrid.getBoundingClientRect();if(rect.width)updateEditorWidth((event.clientX-rect.left)/rect.width*100);}
function stopSplit(){window.removeEventListener('pointermove',moveSplit);window.removeEventListener('pointerup',stopSplit);window.removeEventListener('pointercancel',stopSplit);splitDragging.value=false;splitGrid=null;}
function keySplit(event:KeyboardEvent){const step=event.shiftKey?5:2;if(event.key==='ArrowLeft'){updateEditorWidth(editorWidth.value-step);event.preventDefault();}else if(event.key==='ArrowRight'){updateEditorWidth(editorWidth.value+step);event.preventDefault();}else if(event.key==='Home'){updateEditorWidth(25);event.preventDefault();}else if(event.key==='End'){updateEditorWidth(75);event.preventDefault();}}
onMounted(async()=>{await action(async()=>{await refreshModels();const saved=await api('/workbench/configuration');if(Object.keys(saved).length)config.value={...emptyConfiguration(),...saved};const first=models.value.find(m=>m.id==='seed-source-state-machine')||models.value[0];if(first)selectModel(first.id);await refreshRuns();runId.value=new URLSearchParams(location.search).get('run')||runs.value[0]?.id||'';await pollConnection();});poll=setInterval(()=>{void pollConnection();if(tab.value==='config'&&!busy.value)void checkPreparation();},3000);});
onBeforeUnmount(()=>{clearTimeout(previewTimer);clearTimeout(saveTimer);clearInterval(poll);stopSplit();});
watch(availableEvents,events=>{if(!events.some(([key])=>key===trialEvent.value))trialEvent.value=String(events[0]?.[0]||'');});
watch(runId,()=>{currentRun.value=null;});
watch(()=>tab.value,async value=>{if(value==='config')void checkPreparation();await nextTick();window.dispatchEvent(new Event('resize'));});
</script>

<template>
 <header class="app-header"><a class="brand" href="/"><span class="brand-mark">C</span><span><b>CDI8 WORKBENCH</b><small>实验流程与组件状态机 · 开发运行调试</small></span></a>
  <nav aria-label="工作区"><button :class="{active:tab==='models'}" @click="switchTab('models')">模型开发</button><button :class="{active:tab==='config'}" @click="switchTab('config')">运行准备</button><button :class="{active:tab==='runs'}" @click="switchTab('runs')">运行调试</button></nav>
  <span class="connection"><i :class="{online:connection}"></i>{{connection?'Tango 已连接':'Tango 未连接'}}</span>
 </header>
 <div v-if="error" class="message error" role="alert">{{error}}<button @click="error=''">关闭</button></div><div v-if="notice" class="message success" role="status">{{notice}}<button @click="notice=''">关闭</button></div>
 <main v-show="tab==='models'" class="development">
  <aside class="model-sidebar"><div class="sidebar-title"><h2>项目模型</h2><button title="新建模型" aria-label="新建模型" @click="newName='';newDialog?.showModal()">＋</button></div><div class="model-filters"><button v-for="item in [['all','全部'],['bpmn','流程'],['scxml','状态机']]" :class="{active:filter===item[0]}" @click="filter=item[0]">{{item[1]}}</button></div>
   <input class="model-search" aria-label="搜索模型" v-model="modelSearch" placeholder="搜索名称或文件名">
   <button v-for="item in visibleModels" :key="item.id" class="model-item" :class="{selected:item.id===selectedId}" @click="selectModel(item.id)"><span class="file-icon" :class="item.kind">{{item.kind==='bpmn'?'B':'S'}}</span><span><b>{{modelLabels.get(item.id)}}</b><small class="model-filename" :title="item.name">{{item.name}}</small><small>{{item.kind==='bpmn'?'实验流程':'组件状态机'}} · {{item.revisions.length}} 个版本</small></span></button>
   <p v-if="!visibleModels.length" class="model-search-empty">没有匹配的模型</p>
   <label class="import-model">导入 XML 文件<input type="file" accept=".xml,.scxml,.bpmn,.bpmn20.xml" @change="importFile"></label>
   <p class="sidebar-help">编辑草稿 → 验证模型 → 保存版本<br>流程到“运行准备”中组合组件；状态机可直接在预览中试验。</p>
  </aside>
  <section v-if="model" class="model-workspace">
   <div class="workspace-toolbar model-heading"><div class="model-heading-main"><span class="eyebrow">{{model.kind==='bpmn'?'实验流程 / BPMN':'组件状态机 / SCXML'}}</span><h1>{{selectedModelLabel}}</h1><p class="model-subtitle">{{model.name}} <span :class="{'is-saving':draftChanged}">· {{viewingVersion?'已保存版本 · 只读':draftChanged?'草稿保存中…':'草稿已自动保存'}}</span></p></div><div class="toolbar-actions"><button @click="exportXml">导出 XML</button><button :disabled="busy" @click="action(()=>validate())">校验模型</button><button class="primary" :disabled="busy||viewingVersion" @click="action(saveVersion)">保存版本</button><button v-if="model.kind==='bpmn'" :disabled="busy||!runnableRevision" :title="runnableRevision?'使用当前内容对应的已保存版本':'请先保存当前内容为版本'" @click="prepareModel">用于运行准备 →</button></div></div>
   <div class="model-context-bar"><label>当前内容<select aria-label="模型版本" :value="version" @change="chooseVersion(($event.target as HTMLSelectElement).value)"><option value="draft">工作草稿 · 自动保存</option><option v-for="r in model.revisions.slice().reverse()" :key="r.id" :value="r.id">版本 {{revisionLabel(r.id)}} · {{new Date(r.created).toLocaleString()}}</option></select></label><span v-if="validation&&!validation.valid" class="validation-warning" role="alert">校验未通过{{diagnostics[0]?.message?'：'+diagnostics[0].message:''}}</span><span v-else class="model-task-hint">{{viewingVersion?'历史版本只读；返回工作草稿继续编辑。':model.kind==='scxml'?'修改源码后，在右侧试验状态迁移。':'编辑流程后保存版本，再准备组件并运行。'}}</span><button v-if="viewingVersion" @click="chooseVersion('draft')">返回草稿编辑</button></div>
   <div class="editing-grid" :class="{'is-split-dragging':splitDragging}" :style="{'--editor-width':editorWidth+'%'}"><section class="editor-panel"><div class="panel-heading"><span>XML 源码</span><small>{{viewingVersion?'已保存版本 · 只读':'自动保存草稿'}}</small></div><XmlEditor ref="editor" :value="xml" :diagnostics="diagnostics" :read-only="viewingVersion" @change="edit" @select="selectedNode=$event" /></section><div class="panel-splitter" role="separator" aria-orientation="vertical" aria-label="调整源码区和实时预览宽度" :aria-valuenow="Math.round(editorWidth)" aria-valuemin="25" aria-valuemax="75" tabindex="0" @pointerdown="startSplit" @keydown="keySplit"></div>
    <section class="preview-panel">
     <div class="panel-heading"><span>实时预览</span><small :class="{bad:previewError}">{{previewError?'保留上次有效预览':previewStale?'正在更新…':'与当前 XML 一致'}}</small></div>
     <Diagram :kind="model.kind" :xml="previewXml" :state="diagramState" :selected="selectedNode" @select="selectedNode=$event;editor?.reveal($event)" @error="previewError=$event;previewStale=true" @ready="previewError='';previewStale=false" />
     <div v-if="previewError" class="preview-warning">{{previewError}}</div><div class="preview-caption">滚轮缩放 · 拖动平移 · 点击节点定位 XML</div>
     <section v-if="model.kind==='scxml'" class="preview-trial" aria-label="独立设备试验">
      <div class="preview-trial-tabs" role="tablist" aria-label="试验视图"><button role="tab" :aria-selected="trialView==='control'" :class="{active:trialView==='control'}" @click="trialView='control'">试验控制</button><button role="tab" :aria-selected="trialView==='history'" :class="{active:trialView==='history'}" @click="trialView='history'">实验记录 <span class="count">{{trial?.history.length||0}}</span></button></div>
      <div v-if="trialView==='control'" class="preview-trial-content">
       <div class="preview-trial-head"><div><strong>独立设备试验</strong><small>{{trial?'实例 '+trial.id.slice(0,8):'在预览中直接验证状态迁移'}}</small></div><button :disabled="!canTrial||busy" @click="action(createTrial)">{{trial?'重新试验':'开始独立试验'}}</button></div>
       <template v-if="trial">
        <p v-if="!trialMatches" class="inline-warning">模型已修改；当前实例仍运行创建时的版本，重新试验可加载新内容。</p>
        <div class="preview-trial-state"><span>当前状态</span><b>{{trial.snapshot.outer_state_label}} / {{trial.snapshot.inner_state_label}}</b></div>
        <div class="preview-trial-actions"><div class="preview-trial-section-label">动作（点击立即发送）</div><div class="preview-trial-action-buttons"><button v-for="a in availableActions" type="button" :disabled="busy||!!trial.snapshot.active_command_id" @click="sendTrialAction(a)"><span>{{trial.capabilities.actions[a].label}}</span><small>{{a}}</small></button><span v-if="!availableActions.length" class="preview-trial-empty">当前状态没有可执行动作。</span></div></div>
        <div class="preview-trial-command"><label class="preview-trial-parameters">参数 JSON<textarea v-model="trialParameters" rows="2" spellcheck="false"></textarea></label><div class="preview-trial-options"><label class="preview-trial-auto"><input type="checkbox" v-model="trialAuto">自动成功反馈</label><label class="preview-trial-delay"><span>延迟</span><input aria-label="试运行自动反馈延迟" type="number" min="0" max="3600000" v-model="trialDelay"><small>ms</small></label></div></div>
        <div v-if="trial.snapshot.active_command_id" class="preview-feedback"><span>等待反馈 · {{trial.snapshot.active_action}}</span><button @click="action(()=>trialComplete('success'))" :disabled="busy">成功</button><button class="danger" @click="action(()=>trialComplete('failure'))" :disabled="busy">失败</button></div>
        <div v-if="availableEvents.length" class="preview-event"><select aria-label="外部确认事件" v-model="trialEvent"><option v-for="[key,c] in availableEvents" :value="key">{{(c as any).label}}</option></select><button :disabled="busy||!trialEvent" @click="action(injectTrial)">注入确认</button></div>
       </template>
       <p v-else class="preview-trial-empty">校验通过后，即可用当前内容开始独立试验，无需先保存版本。活跃状态会直接高亮在上方预览中。</p>
      </div>
      <div v-else class="preview-trial-history"><div class="preview-trial-history-head"><strong>状态迁移记录</strong><small>{{trial?.history.length||0}} 条</small></div><table v-if="trial?.history.length"><thead><tr><th>时间</th><th>事件</th><th>源 → 目标</th></tr></thead><tbody><tr v-for="event in trial.history.slice().reverse()"><td>{{event.occurred_at?.slice(11,23)}}</td><td>{{event.event}}</td><td>{{event.source}} → {{event.target}}</td></tr></tbody></table><div v-else class="empty-state">在“试验控制”中创建试运行并发送动作请求，观察状态迁移。</div></div>
     </section>
    </section>
   </div>
  </section>
 </main>
 <RunPreparation ref="preparationView" v-show="tab==='config'" :models="models" :configuration="config" :busy="busy" :operation="preparationOperation" :connected="connection" :checked="preparationChecked" :check-error="preparationError" :environment="liveEnvironment" :devices="liveDevices" :active-run="activeRun" @save="preparationAction('save',$event)" @apply="preparationAction('apply',$event)" @start="preparationAction('start',$event)" @refresh="checkPreparation" />
 <main v-if="tab==='runs'" class="run-page"><div class="run-toolbar"><div><span class="eyebrow">RUN DEBUGGING</span><h1>运行调试</h1><p class="run-subtitle">观察流程与设备联动，处理等待，并回看执行记录。</p></div><div class="toolbar-actions"><label class="run-record-field">运行记录<select aria-label="联调运行记录" v-model="runId"><option value="">预览已部署流程</option><option v-for="r in runs" :key="r.id" :value="r.id">{{runLabel(r)}}</option></select></label><button :disabled="busy" @click="action(async()=>{await refreshRuns();await runtimeView?.refresh();})">刷新记录</button><button :disabled="busy||!currentRun||currentRun.ended" class="danger" @click="action(stopRun)">停止流程</button><button class="primary" @click="switchTab('config')">准备下一次运行 →</button></div></div><RuntimeView ref="runtimeView" :run-id="runId" @change="currentRun=$event" /></main>
 <dialog ref="newDialog" class="new-dialog"><form @submit.prevent="action(createModel)"><div class="card-heading"><h2>新建模型</h2><button type="button" @click="newDialog?.close()">关闭</button></div><label>模型类型<select v-model="newKind"><option value="scxml">SCXML 状态机</option><option value="bpmn">BPMN 流程</option></select></label><label>文件名<input v-model="newName" required :placeholder="newKind==='scxml'?'demo.scxml':'demo.bpmn20.xml'"></label><p class="hint">从可执行的最小示例开始编辑。创建后保存版本，再到运行准备中选择。</p><button class="primary" :disabled="busy">创建模型</button></form></dialog>
</template>
