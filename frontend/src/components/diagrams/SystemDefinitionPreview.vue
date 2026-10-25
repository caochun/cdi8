<script setup lang="ts">
import {computed} from 'vue';
const props=defineProps<{xml:string}>();
const model=computed(()=>{
  const doc=new DOMParser().parseFromString(props.xml,'application/xml');
  const root=doc.documentElement;
  if(doc.querySelector('parsererror'))
    return {kind:'unknown',error:'系统模型 XML 格式错误',types:[],sets:[],resources:[],interfaces:[],connections:[],invariants:0,openIssues:[],bindings:[],observations:[],facts:[],guards:[],invalidators:[]};
  if(root.localName==='facility-binding') {
    const section=(name:string)=>[...root.children].find(node=>node.localName===name)?.children||[];
    return {kind:'binding',error:'',types:[],sets:[],resources:[],interfaces:[],connections:[],invariants:0,openIssues:[],
      bindings:[...section('instanceBindings')].filter(node=>node.localName==='binding').map(node=>({id:node.getAttribute('instanceSet')||'',pattern:node.getAttribute('logicalPattern')||'',runtimeKey:node.getAttribute('runtimeKey')||''})),
      observations:[],facts:[],guards:[],invalidators:[]};
  }
  if(root.localName==='facility-state-projection') {
    const section=(name:string)=>[...root.children].find(node=>node.localName===name)?.children||[];
    return {kind:'projection',error:'',types:[],sets:[],resources:[],interfaces:[],connections:[],invariants:0,openIssues:[],bindings:[],
      observations:[...section('observations')].filter(node=>node.localName==='observation').map(node=>node.getAttribute('id')||''),
      facts:[...section('facts')].filter(node=>node.localName==='fact').map(node=>({id:node.getAttribute('id')||'',label:node.getAttribute('label')||''})),
      guards:[...section('guards')].filter(node=>node.localName==='guard').map(node=>node.getAttribute('id')||''),
      invalidators:[...section('invalidators')].filter(node=>node.localName==='invalidate').map(node=>node.getAttribute('event')||'')};
  }
  if(root.localName!=='system-definition')
    return {kind:'unknown',error:'不支持的系统模型类型',types:[],sets:[],resources:[],interfaces:[],connections:[],invariants:0,openIssues:[],bindings:[],observations:[],facts:[],guards:[],invalidators:[]};
  const children=(name:string)=>[...root.children].find(node=>node.localName===name)?.children||[];
  const types=[...children('componentTypes')].filter(node=>node.localName==='componentType').map(node=>({
    id:node.getAttribute('id')||'',label:node.getAttribute('label')||'',role:node.getAttribute('role')||'',quantity:node.getAttribute('quantity')||'',
    stateModel:node.getAttribute('stateModelRef')||'',capabilities:[...node.children].filter(item=>item.localName==='capability').map(item=>item.getAttribute('label')||item.getAttribute('id')||'')
  }));
  const sets=[...children('instanceSets')].filter(node=>node.localName==='instanceSet').map(node=>({id:node.getAttribute('id')||'',type:node.getAttribute('type')||'',quantity:node.getAttribute('quantity')||'',pattern:node.getAttribute('pattern')||''}));
  const resources=[...children('resources')].filter(node=>node.localName==='resource').map(node=>({label:node.getAttribute('label')||node.getAttribute('id')||'',quantity:node.getAttribute('quantity')||''}));
  const interfaces=[...children('interfaces')].filter(node=>node.localName==='interface').map(node=>({label:node.getAttribute('label')||node.getAttribute('id')||'',protocol:node.getAttribute('protocol')||''}));
  const connections=[...root.getElementsByTagNameNS('*','connection')].map(node=>({id:node.getAttribute('id')||'',source:node.getAttribute('source')||'',target:node.getAttribute('target')||'',relation:node.getAttribute('relation')||''}));
  const invariants=[...children('invariants')].filter(node=>node.localName==='invariant');
  const openIssues=[...children('openIssues')].filter(node=>node.localName==='issue').map(node=>({id:node.getAttribute('id')||'',status:node.getAttribute('status')||'',summary:node.getAttribute('summary')||''}));
  return {kind:'definition',error:'',types,sets,resources,interfaces,connections,invariants:invariants.length,openIssues,bindings:[],observations:[],facts:[],guards:[],invalidators:[]};
});
</script>
<template>
 <section class="system-definition-preview" aria-label="实验设施系统定义预览">
  <header><div><span class="eyebrow">{{model.kind==='binding'?'FACILITY INSTANCE BINDING':model.kind==='projection'?'FACILITY STATE PROJECTION':'FACILITY SYSTEM DEFINITION'}}</span><h2 v-if="model.kind==='definition'">{{model.types.length}} 类设备 · {{model.sets.length}} 组实例 · {{model.connections.length}} 条连接</h2><h2 v-else-if="model.kind==='binding'">{{model.bindings.length}} 个实例集合绑定</h2><h2 v-else-if="model.kind==='projection'">{{model.observations.length}} 项观测 · {{model.facts.length}} 项事实 · {{model.guards.length}} 项放行谓词</h2></div></header>
  <p v-if="model.error" class="preview-warning">{{model.error}}</p>
  <template v-else>
   <div v-if="model.kind==='definition'" class="definition-summary">
    <article><small>资源</small><strong v-for="resource in model.resources" :key="resource.label">{{resource.label}} · {{resource.quantity}}</strong></article>
    <article><small>接口</small><strong v-for="item in model.interfaces" :key="item.label">{{item.label}} · {{item.protocol}}</strong></article>
   </div>
   <div v-if="model.kind==='definition'" class="component-grid">
    <article v-for="type in model.types" :key="type.id" class="component-card">
      <small>{{type.role}} · {{type.quantity}} 套</small><h3>{{type.label}}</h3><code>{{type.id}}</code>
      <p>{{type.capabilities.length}} 项能力</p>
      <ul><li v-for="capability in type.capabilities.slice(0,5)" :key="capability">{{capability}}</li><li v-if="type.capabilities.length>5">另有 {{type.capabilities.length-5}} 项</li></ul>
    </article>
   </div>
   <table v-if="model.kind==='definition'"><thead><tr><th>实例集合</th><th>类型</th><th>数量</th><th>编号模式</th></tr></thead><tbody><tr v-for="set in model.sets" :key="set.id"><td>{{set.id}}</td><td>{{set.type}}</td><td>{{set.quantity}}</td><td><code>{{set.pattern}}</code></td></tr></tbody></table>
   <template v-if="model.kind==='definition'"><h3 class="section-title">拓扑连接</h3><table><thead><tr><th>连接</th><th>源</th><th>目标</th><th>关系</th></tr></thead><tbody><tr v-for="connection in model.connections" :key="connection.id"><td>{{connection.id}}</td><td><code>{{connection.source}}</code></td><td><code>{{connection.target}}</code></td><td>{{connection.relation}}</td></tr></tbody></table><h3 v-if="model.openIssues.length" class="section-title">未决项</h3><ul v-if="model.openIssues.length" class="issues"><li v-for="issue in model.openIssues" :key="issue.id"><strong>{{issue.id}}</strong> · {{issue.status}} · {{issue.summary}}</li></ul></template>
   <template v-else-if="model.kind==='binding'"><h3 class="section-title">实例绑定</h3><table><thead><tr><th>实例集合</th><th>逻辑模式</th><th>运行时键</th></tr></thead><tbody><tr v-for="binding in model.bindings" :key="binding.id"><td>{{binding.id}}</td><td><code>{{binding.pattern}}</code></td><td>{{binding.runtimeKey}}</td></tr></tbody></table></template>
   <template v-else-if="model.kind==='projection'"><h3 class="section-title">观测</h3><ul class="issues"><li v-for="item in model.observations" :key="item"><code>{{item}}</code></li></ul><h3 class="section-title">事实</h3><ul class="issues"><li v-for="fact in model.facts" :key="fact.id"><strong>{{fact.id}}</strong> · {{fact.label}}</li></ul><h3 class="section-title">放行谓词</h3><ul class="issues"><li v-for="guard in model.guards" :key="guard"><code>{{guard}}</code></li></ul></template>
  </template>
 </section>
</template>
<style scoped>
.system-definition-preview{min-height:100%;padding:28px 32px;color:#263a3a;background:#f6f8f7;overflow:auto}.system-definition-preview header{border-bottom:1px solid #d8e0dd;padding-bottom:18px}.eyebrow{font-size:11px;font-weight:700;color:#54716a}.system-definition-preview h2{font-size:18px;margin:6px 0 0}.definition-summary{display:grid;grid-template-columns:repeat(auto-fit,minmax(240px,1fr));gap:16px;margin:22px 0}.definition-summary article{background:#fff;border:1px solid #d4dfdb;padding:14px}.definition-summary small{display:block;color:#627773;margin-bottom:8px}.definition-summary strong{display:block;font-size:12px;line-height:1.7;font-weight:500}.component-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(210px,1fr));gap:14px}.component-card{background:#fff;border:1px solid #d4dfdb;border-top:4px solid #327d69;padding:14px;min-width:0}.component-card small{color:#627773}.component-card h3{font-size:16px;margin:8px 0 2px}.component-card code{font-size:11px;color:#54716a}.component-card p{font-size:12px;color:#60736f;margin:12px 0 4px}.component-card ul{padding-left:17px;margin:4px 0;font-size:12px;line-height:1.6}.section-title{font-size:16px;margin:24px 0 8px}table{border-collapse:collapse;width:100%;margin-top:22px;font-size:13px}th,td{text-align:left;padding:9px 12px;border-bottom:1px solid #d8e0dd}th{color:#526965;font-weight:600}code{overflow-wrap:anywhere}.issues{padding:0 0 0 20px;font-size:12px;line-height:1.7}@media(max-width:650px){.system-definition-preview{padding:18px}}
</style>
