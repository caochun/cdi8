<script setup lang="ts">
import {computed,ref,watch} from 'vue';
import type {Model} from '../../api';
import {modelLabel as businessLabel} from '../../model-label';
import {emptyConfiguration,resolveProcesses,parseObject,stableJson,deviceIssues,defaultBindings,type RunConfiguration} from './configuration';
import './preparation.css';
const props=defineProps<{models:Model[];configuration:RunConfiguration;busy:boolean;operation:string;connected:boolean;checked:boolean;checkError:string;environment:any;devices:any[];activeRun:boolean}>();
const emit=defineEmits<{save:[value:RunConfiguration];apply:[value:RunConfiguration];start:[value:RunConfiguration];refresh:[]}>();
const draft=ref<RunConfiguration>(emptyConfiguration());
const bindingsText=ref('{}'),variablesText=ref('{}'),conditionsText=ref('{}'),localError=ref(''),exampleNotice=ref('');
watch(()=>props.configuration,value=>{
  draft.value=JSON.parse(JSON.stringify({...emptyConfiguration(),...value}));
  bindingsText.value=JSON.stringify(draft.value.bindings||{},null,2);
  variablesText.value=JSON.stringify(draft.value.variables||{},null,2);
  conditionsText.value=JSON.stringify(draft.value.conditions||{},null,2);localError.value='';
},{immediate:true});
const flows=computed(()=>props.models.filter(m=>m.kind==='bpmn'));
const stateMachines=computed(()=>props.models.filter(m=>m.kind==='scxml'));
const plan=computed(()=>resolveProcesses(props.models,draft.value.entry,draft.value.processes));
// Keep defaults pinned once chosen; newly saved versions do not silently replace them.
watch(plan,value=>{if(stableJson(draft.value.processes)!==stableJson(value.selections))draft.value.processes=value.selections;},{immediate:true});
const entryModel=computed(()=>modelFor(draft.value.entry));
const dependencies=computed(()=>plan.value.selections.filter(p=>p.model!==draft.value.entry));
const componentIssues=computed(()=>deviceIssues(draft.value.devices,props.models));
const parsed=computed<{bindings:Record<string,string[]>;variables:Record<string,unknown>;conditions:Record<string,boolean>;error:string}>(()=>{
  try{
    const bindings=parseObject(bindingsText.value,'设备集合绑定'),variables=parseObject(variablesText.value,'运行参数'),conditions=parseObject(conditionsText.value,'外部条件');
    for(const [key,ids] of Object.entries(bindings))if(!Array.isArray(ids)||ids.some(id=>typeof id!=='string'))throw Error('设备集合 '+key+' 必须是设备编号数组');
    if(Object.values(conditions).some(value=>typeof value!=='boolean'))throw Error('外部条件的值必须是 true 或 false');
    return {bindings,variables,conditions,error:''};
  }catch(error){return {bindings:{},variables:{},conditions:{},error:(error as Error).message};}
});
const instanceCount=computed(()=>draft.value.devices.reduce((n,row)=>n+(Number(row.count)||0),0));
const bindingPreview=computed(()=>({...defaultBindings(draft.value.devices),...parsed.value.bindings}));
const bindingIssues=computed(()=>{
  const known=new Set(defaultBindings(draft.value.devices).allComponents.concat('context_01'));
  return Object.entries(parsed.value.bindings).flatMap(([name,ids])=>ids.filter(id=>!known.has(id)).map(id=>'集合 '+name+' 绑定了不存在的实例：'+id));
});
const issues=computed(()=>[...plan.value.issues,...componentIssues.value,...bindingIssues.value,...plan.value.collections.filter(name=>!(name in bindingPreview.value)).map(name=>'流程需要设备集合 '+name+'，请添加对应组件或在高级配置中绑定'),...(parsed.value.error?[parsed.value.error]:[])]);
const deviceConfigurationChanged=computed(()=>
  stableJson(props.configuration.appliedDevices)!==stableJson(draft.value.devices)||
  Boolean(props.configuration.appliedAutoComplete)!==draft.value.autoComplete||
  stableJson(props.configuration.appliedConditions||{})!==stableJson(parsed.value.conditions));
const health=computed(()=>{
  const wanted=defaultBindings(draft.value.devices).allComponents.concat('context_01');
  return {offline:wanted.filter(id=>!props.devices.some(d=>d.id===id&&d.connected)),working:props.devices.filter(d=>wanted.includes(d.id)&&d.snapshot?.active_command_id)};
});
const readiness=computed(()=>{
  if(!props.configuration.environment?.id)return {kind:'pending',title:'尚未创建模拟设备',detail:'选好组件后，创建设备以加载状态机并进入初始状态。'};
  if(deviceConfigurationChanged.value)return {kind:'pending',title:'设备配置已修改',detail:'组件、反馈方式或初始条件已变更，需要重新创建设备后再调试。'};
  if(!props.checked)return {kind:'pending',title:'正在检查设备',detail:'正在核对当前设备环境和连接状态。'};
  if(props.checkError)return {kind:'error',title:'无法检查设备状态',detail:props.checkError};
  if(props.environment?.id!==props.configuration.environment.id)return {kind:'pending',title:'需要重新创建模拟设备',detail:'当前设备环境与保存的方案不一致，可能已重启或被替换。'};
  if(props.environment?.status==='failed')return {kind:'error',title:'模拟设备服务已退出',detail:props.environment.error||'请重新创建模拟设备后再调试。'};
  if(!props.connected||health.value.offline.length)return {kind:'error',title:'设备尚未连接',detail:health.value.offline.length?'等待连接：'+health.value.offline.join('、'):'等待 Tango 事件连接恢复。'};
  if(props.activeRun)return {kind:'pending',title:'已有流程正在运行',detail:'在“运行调试”中查看进度或停止后，再准备下一次运行。'};
  if(health.value.working.length)return {kind:'pending',title:'设备正在执行动作',detail:'等待 '+health.value.working.map(d=>d.id).join('、')+' 的动作结束后再启动。'};
  return {kind:'ready',title:'设备已就绪',detail:'开始调试将沿用设备当前状态。需要从头试验时，可重建设备恢复初始状态。'};
});
const canStart=computed(()=>readiness.value.kind==='ready'&&!issues.value.length&&!props.busy);
const canApply=computed(()=>!props.busy&&!props.activeRun&&!componentIssues.value.length&&!parsed.value.error);
function modelFor(id:string){return props.models.find(m=>m.id===id);}
function modelLabel(id:string){
  const model=modelFor(id);if(!model)return id;
  return businessLabel(model,model.revisions.find(r=>r.id===revisionFor(id))?.xml||model.revisions.at(-1)?.xml||model.draft);
}
function revisionFor(id:string){return plan.value.selections.find(p=>p.model===id)?.revision||'';}
function setVersion(id:string,revision:string){draft.value.processes=[...draft.value.processes.filter(p=>p.model!==id),{model:id,revision}];}
function setDeviceModel(row:RunConfiguration['devices'][number]){row.revision=modelFor(row.model)?.revisions.at(-1)?.id||'';}
function addDevice(){
  const model=stateMachines.value.find(m=>m.revisions.length);if(!model)return;
  let component='demo',suffix=2;while(draft.value.devices.some(row=>row.component===component))component='demo'+suffix++;
  draft.value.devices.push({model:model.id,revision:model.revisions.at(-1)!.id,component,count:1,autoComplete:true,delayMs:500});
}
function useExample(){
  const ids=['seed-source-state-machine','shg-injection-state-machine','preamp-state-machine','multipass-state-machine'];
  if(!modelFor('emission_task_lifecycle')?.revisions.length||ids.some(id=>!modelFor(id)?.revisions.length)){localError.value='GXLF 示例模型不完整，请先导入并保存示例模型版本';return;}
  const devices=ids.map((id,i)=>({model:id,revision:modelFor(id)!.revisions.at(-1)!.id,component:['seed','shg','preamp','multipass'][i],count:1,autoComplete:true,delayMs:500}));
  const order=props.configuration.appliedDevices?.map(row=>row.component)||[];
  if(order.length===devices.length&&devices.every(row=>order.includes(row.component)))devices.sort((a,b)=>order.indexOf(a.component)-order.indexOf(b.component));
  draft.value={...draft.value,entry:'emission_task_lifecycle',processes:[],devices,autoComplete:true};
  bindingsText.value=JSON.stringify({sourceTargets:['seed_01']},null,2);variablesText.value=JSON.stringify({commandTimeout:'PT15M',workflowTimeout:'PT45M'},null,2);conditionsText.value='{}';
  localError.value='';exampleNotice.value='已填入 GXLF 四组件示例方案。请检查设备就绪状态后开始调试。';
}
function submit(operation:'save'|'apply'|'start'){
  localError.value='';
  if(parsed.value.error){localError.value=parsed.value.error;return;}
  if(operation==='start'&&!canStart.value)return;
  if(operation==='apply'&&!canApply.value)return;
  const payload={...draft.value,processes:plan.value.selections,bindings:parsed.value.bindings,variables:parsed.value.variables,conditions:parsed.value.conditions};
  if(operation==='save')emit('save',payload);else if(operation==='apply')emit('apply',payload);else emit('start',payload);
}
function selectEntry(id:string,revision:string){
  draft.value.entry=id;draft.value.processes=[...draft.value.processes.filter(item=>item.model!==id),{model:id,revision}];
  localError.value='';exampleNotice.value='已选择流程及版本，请检查组件与设备状态后开始调试。';
}
defineExpose({selectEntry});
</script>

<template>
 <main class="preparation-page" aria-label="运行准备">
  <div class="preparation-heading"><div><span class="eyebrow">RUN PREPARATION</span><h1>运行准备</h1><p>选择要调试的流程，为它准备组件设备，然后开始运行。</p></div><div class="toolbar-actions"><button class="example-button" :disabled="busy" @click="useExample">使用示例方案</button><button :disabled="busy||!!parsed.error" @click="submit('save')">{{operation==='save'?'正在保存…':'保存方案'}}</button></div></div>
  <p v-if="exampleNotice" class="preparation-notice" role="status">{{exampleNotice}}</p><p v-if="localError" class="preparation-error" role="alert">{{localError}}</p>
  <ol class="preparation-steps" aria-label="运行准备步骤"><li :class="{complete:!plan.issues.length}"><span>01</span><div><b>选择流程</b><small>{{entryModel?modelLabel(entryModel.id):'选择入口与版本'}}</small></div></li><li :class="{complete:readiness.kind==='ready'}"><span>02</span><div><b>准备组件</b><small>{{instanceCount}} 个组件实例 · {{readiness.title}}</small></div></li><li><span>03</span><div><b>开始调试</b><small>查看流程与状态机联动</small></div></li></ol>
  <div class="preparation-layout"><div class="preparation-main">
   <section class="preparation-card"><div class="preparation-card-heading"><span class="step-number">01</span><div><h2>调试哪个流程？</h2><p>使用已保存的版本，子流程依赖会自动识别。</p></div></div>
    <div class="entry-fields"><label>入口流程<select v-model="draft.entry" aria-label="入口流程" :disabled="busy"><option value="" disabled>选择要调试的流程</option><option v-for="m in flows" :key="m.id" :value="m.id" :disabled="!m.revisions.length">{{modelLabel(m.id)}}{{!m.revisions.length?' · 请先保存版本':''}}</option></select></label><label>流程版本<select :value="revisionFor(draft.entry)" aria-label="入口流程版本" :disabled="busy||!entryModel?.revisions.length" @change="setVersion(draft.entry,($event.target as HTMLSelectElement).value)"><option v-if="!entryModel?.revisions.length" value="">先选择已保存的流程</option><option v-for="r in entryModel?.revisions.slice().reverse()" :key="r.id" :value="r.id">{{r.id.slice(0,8)}} · {{new Date(r.created).toLocaleString()}}</option></select></label></div>
    <details v-if="dependencies.length" class="dependency-list"><summary class="dependency-caption"><b>已自动识别 {{dependencies.length}} 个子流程</b><span>展开调整版本</span></summary><label v-for="item in dependencies" :key="item.model"><span :title="modelFor(item.model)?.name">{{modelLabel(item.model)}}</span><select :aria-label="(modelFor(item.model)?.name||item.model)+'版本'" :value="item.revision" :disabled="busy" @change="setVersion(item.model,($event.target as HTMLSelectElement).value)"><option v-for="r in modelFor(item.model)?.revisions.slice().reverse()" :key="r.id" :value="r.id">{{r.id.slice(0,8)}} · {{new Date(r.created).toLocaleString()}}</option></select></label></details>
    <p v-else-if="draft.entry&&!plan.issues.length" class="preparation-hint">该流程没有需要另选版本的子流程。</p><p class="preparation-hint">启动后固定本次使用的版本，后续编辑模型不影响在途运行。</p>
   </section>
   <section class="preparation-card"><div class="preparation-card-heading"><span class="step-number">02</span><div><h2>需要哪些组件？</h2><p>每组组件使用一个状态机版本，可创建多个独立实例。</p></div><button :disabled="busy||!stateMachines.some(m=>m.revisions.length)" @click="addDevice">＋ 添加组件</button></div>
    <div v-if="!draft.devices.length" class="preparation-empty">尚未添加组件。可添加状态机实例，或使用四组件示例方案。<small>仅依赖外部条件的流程可以只使用自动创建的同步条件设备。</small></div>
    <article v-for="(row,i) in draft.devices" :key="i" class="preparation-device"><div class="preparation-device-heading"><b>组件 {{i+1}}</b><button class="quiet-danger" :disabled="busy" :aria-label="'移除组件 '+(i+1)" @click="draft.devices.splice(i,1)">移除</button></div><div class="preparation-device-fields"><label>状态机模型<select v-model="row.model" :aria-label="'组件 '+(i+1)+' 状态机模型'" :disabled="busy" @change="setDeviceModel(row)"><option v-for="m in stateMachines" :key="m.id" :value="m.id" :disabled="!m.revisions.length">{{modelLabel(m.id)}}</option></select></label><label>状态机版本<select v-model="row.revision" :aria-label="'组件 '+(i+1)+' 状态机版本'" :disabled="busy"><option v-for="r in modelFor(row.model)?.revisions.slice().reverse()" :key="r.id" :value="r.id">{{r.id.slice(0,8)}} · {{new Date(r.created).toLocaleString()}}</option></select></label><label>组件类型（实例前缀）<input v-model="row.component" :aria-label="'组件 '+(i+1)+' 类型'" :disabled="busy" placeholder="例如 seed 或 demo"></label><label>实例数量<input v-model.number="row.count" type="number" min="1" max="200" :aria-label="'组件 '+(i+1)+' 实例数量'" :disabled="busy"></label><label>动作反馈<select v-model="row.autoComplete" :aria-label="'组件 '+(i+1)+' 动作反馈'" :disabled="busy"><option :value="true">自动成功反馈</option><option :value="false">手动反馈成功／失败</option></select></label><label v-if="row.autoComplete">自动反馈延迟（毫秒）<input v-model.number="row.delayMs" type="number" min="0" max="3600000" :aria-label="'组件 '+(i+1)+' 反馈延迟'" :disabled="busy"></label></div><div class="instance-preview"><span>将创建</span><code>{{row.component||'组件类型'}}_01{{row.count>1?' … '+row.component+'_'+String(row.count).padStart(2,'0'):''}}</code></div></article>
    <p class="preparation-hint">自动反馈用于模拟动作完成。安全许可等外部条件仍需明确确认，可在高级配置中设定初始值。</p>
   </section>
   <details class="preparation-card preparation-advanced"><summary><div><h2>高级配置</h2><p>设备集合绑定、运行参数和外部条件；常规示例可以沿用默认值。</p></div><span>展开配置</span></summary><div class="advanced-content">
    <section><h3>设备集合绑定</h3><p>流程按集合选择设备，例如 <code>sourceTargets</code> 对应 <code>seed_01</code>。以下是本次配置使用的集合。</p><dl class="binding-preview"><div v-for="(ids,name) in bindingPreview" :key="name"><dt>{{name}}</dt><dd>{{ids.join('、')||'空集合'}}</dd></div></dl><label>覆盖集合（JSON）<textarea v-model="bindingsText" aria-label="设备集合绑定 JSON" :disabled="busy" spellcheck="false"></textarea></label></section>
    <section><h3>运行参数</h3><p>例如动作超时、流程超时和光源选择。修改这些参数可复用设备当前状态。</p><label>运行参数（JSON）<textarea v-model="variablesText" aria-label="流程初始变量 JSON" :disabled="busy" spellcheck="false"></textarea></label></section>
    <section><h3>外部条件初始值</h3><p>布尔条件使用 true 或 false。修改初始值需要重新创建设备；运行中的确认在“运行调试”中操作。</p><label>条件初始值（JSON）<textarea v-model="conditionsText" aria-label="外部条件定义 JSON" :disabled="busy" spellcheck="false"></textarea></label><label class="checkbox"><input v-model="draft.autoComplete" type="checkbox" :disabled="busy">同步条件设备的动作自动成功反馈</label><p>这一选项只完成命令，外部许可不会因此自动满足。</p></section>
   </div></details>
  </div><aside class="preparation-aside"><section class="preparation-card readiness-card"><div class="readiness-title"><h2>运行检查</h2><button :disabled="busy" @click="emit('refresh')">检查状态</button></div><div class="readiness-state" :class="readiness.kind" role="status"><span class="readiness-dot"></span><strong>{{readiness.title}}</strong></div><p class="readiness-description">{{readiness.detail}}</p><dl class="preparation-facts"><div><dt>入口流程</dt><dd>{{entryModel?modelLabel(entryModel.id):'尚未选择'}}</dd></div><div><dt>流程依赖</dt><dd>{{dependencies.length}} 个子流程</dd></div><div><dt>模拟实例</dt><dd>{{instanceCount}} 个组件 + 1 个条件设备</dd></div></dl>
    <div v-if="issues.length" class="preparation-issues" role="alert"><strong>开始前还需处理</strong><ul><li v-for="issue in issues" :key="issue">{{issue}}</li></ul></div>
    <div class="device-setup"><button class="setup-button" :disabled="!canApply" @click="submit('apply')">{{operation==='apply'?'正在创建设备…':configuration.environment?.id?'重新创建模拟设备':'创建模拟设备'}}</button><p>{{configuration.environment?.id?'重新创建会复位全部模拟设备，恢复所选状态机的初始状态。':'按所选状态机创建设备，并从初始状态开始。'}}</p><p v-if="activeRun" class="setup-blocked">请先停止当前运行，再重新创建设备。</p><small>保存方案只保存选择；创建设备后，才会装载状态机。</small></div>
   </section></aside></div>
  <footer class="preparation-footer"><div><strong>{{canStart?'可以开始调试':issues.length?'请完成运行准备':readiness.title}}</strong><span>{{canStart?'将使用选定流程版本和设备当前状态':issues[0]||readiness.detail}}</span></div><button class="primary" :disabled="!canStart" @click="submit('start')">{{operation==='start'?'正在启动…':'开始调试 →'}}</button></footer>
 </main>
</template>
