<script setup lang="ts">
import {computed} from 'vue';
const props=defineProps<{xml:string}>();
const child=(node:Element|null,name:string)=>node?[...node.children].find(item=>item.localName===name)||null:null;
const entries=(node:Element|null,container:string)=>[...(child(node,container)?.children||[])];
const attr=(node:Element|null,name:string)=>node?.getAttribute(name)||'';
const endpoint=(node:Element|null)=>['instanceSetRef','instanceGroupRef','controlNodeRef','externalEndpointRef'].map(key=>attr(node,key)).filter(Boolean).join('')+(attr(node,'portRef')?'.'+attr(node,'portRef'):'');
const condition=(node:Element|null):string=>{
  if(!node)return '';
  if(['condition','all','any'].includes(node.localName))return '('+[...node.children].map(condition).join(node.localName==='any'?' 或 ':' 且 ')+')';
  if(node.localName==='factPredicate')return attr(node,'factRef');
  if(node.localName==='observationPredicate')return attr(node,'observationRef')+(attr(node,'expected')?' = '+attr(node,'expected'):'');
  return attr(node,'instanceSetRef')+': '+[attr(node,'states'),attr(node,'outerStates')].filter(Boolean).join(' / ');
};
const model=computed(()=>{
  const doc=new DOMParser().parseFromString(props.xml,'application/xml');
  const root=doc.documentElement;
  if(doc.querySelector('parsererror'))
    return {kind:'unknown',error:'系统模型 XML 格式错误',types:[],sets:[],resources:[],interfaces:[],connections:[],structuralConstraints:0,openIssues:[],bindings:[],observations:[],facts:[],guards:[]};
  if(root.localName==='facility-binding') {
    const section=(name:string)=>[...root.children].find(node=>node.localName===name)?.children||[];
    return {kind:'binding',error:'',types:[],sets:[],resources:[],interfaces:[],connections:[],structuralConstraints:0,openIssues:[],
      bindings:[...section('instanceBindings')].filter(node=>node.localName==='instanceBinding').map(node=>({id:node.getAttribute('instanceSetRef')||'',runtimeKey:node.getAttribute('runtimeKey')||'',runtimePattern:node.getAttribute('runtimePattern')||'显式绑定'})),
      observations:[],facts:[],guards:[]};
  }
  if(root.localName==='facility-state-projection') {
    const section=(name:string)=>[...root.children].find(node=>node.localName===name)?.children||[];
    return {kind:'projection',error:'',types:[],sets:[],resources:[],interfaces:[],connections:[],structuralConstraints:0,openIssues:[],bindings:[],
      observations:[...section('observations')].filter(node=>node.localName==='observation').map(node=>node.getAttribute('id')||''),
      facts:[...section('facts')].filter(node=>node.localName==='fact').map(node=>({id:node.getAttribute('id')||'',label:node.getAttribute('label')||'',condition:condition(child(node,'condition'))})),
      guards:[...section('guards')].filter(node=>node.localName==='guard').map(node=>({id:attr(node,'id'),condition:condition(child(node,'condition'))}))};
  }
  if(root.localName!=='system-definition')
    return {kind:'unknown',error:'不支持的系统模型类型',types:[],sets:[],resources:[],interfaces:[],connections:[],structuralConstraints:0,openIssues:[],bindings:[],observations:[],facts:[],guards:[]};
  const children=(name:string)=>[...root.children].find(node=>node.localName===name)?.children||[];
  const types=[...children('componentTypes')].filter(node=>node.localName==='componentType').map(node=>({
    id:node.getAttribute('id')||'',label:node.getAttribute('label')||'',role:node.getAttribute('role')||'',quantity:entries(root,'instanceSets').filter(set=>attr(set,'typeRef')===attr(node,'id')).reduce((count,set)=>count+Number(attr(set,'quantity')),0),
    stateModel:node.getAttribute('stateModelHref')||'',capabilities:entries(node,'capabilities').map(item=>item.getAttribute('label')||item.getAttribute('id')||'')
  }));
  const sets=[...children('instanceSets')].filter(node=>node.localName==='instanceSet').map(node=>({id:node.getAttribute('id')||'',type:node.getAttribute('typeRef')||'',quantity:node.getAttribute('quantity')||'',pattern:node.getAttribute('pattern')||''}));
  const resources=[...children('resources')].filter(node=>node.localName==='resource').map(node=>({label:node.getAttribute('label')||node.getAttribute('id')||'',quantity:node.getAttribute('quantity')||''}));
  const interfaces=[...children('interfaces')].filter(node=>node.localName==='interface').map(node=>({label:node.getAttribute('label')||node.getAttribute('id')||'',protocol:[attr(node,'category'),attr(node,'interaction'),attr(node,'protocol')].filter(Boolean).join(' · ')}));
  const connections=[...root.getElementsByTagNameNS('*','connection')].map(node=>({id:node.getAttribute('id')||'',source:endpoint(child(node,'source')),target:endpoint(child(node,'target')),relation:node.getAttribute('relation')||''}));
  const structuralConstraints=[...children('structuralConstraints')];
  const openIssues=entries(child(root,'annotations'),'issues').map(node=>({id:node.getAttribute('id')||'',status:node.getAttribute('status')||'',summary:child(node,'description')?.textContent||''}));
  return {kind:'definition',error:'',types,sets,resources,interfaces,connections,structuralConstraints:structuralConstraints.length,openIssues,bindings:[],observations:[],facts:[],guards:[]};
});
const dependencyModel=computed(()=>{
  const root=new DOMParser().parseFromString(props.xml,'application/xml').documentElement;
  const attr=(node:Element,name:string)=>node.getAttribute(name)||'';
  const children=(node:Element,name:string)=>[...node.children].filter(child=>child.localName===name);
  const inputs=(node:Element)=>entries(node,'requirements').map(req=>({id:attr(req,'id'),path:attr(req,'pathRef'),input:attr(req,'inputPortRef'),signal:attr(req,'signalRef'),maintain:attr(req,'maintain')}));
  const types=children(root,'componentTypes').flatMap(section=>children(section,'componentType'));
  const requirements=types.flatMap(type=>entries(type,'capabilities').flatMap(capability=>inputs(capability).map(input=>({
    ...input,owner:attr(type,'label'),capability:attr(capability,'label'),key:attr(type,'id')+'/'+attr(capability,'id')+'/'+input.id
  }))));
  const outputs=types.flatMap(type=>entries(type,'ports').flatMap(port=>entries(port,'outputs').map(output=>({
    key:attr(type,'id')+'/'+attr(port,'id')+'/'+attr(output,'signalRef'),owner:attr(type,'label'),port:attr(port,'id'),signal:attr(output,'signalRef'),fact:attr(output,'factRef'),inputs:inputs(output)
  }))));
  const facts=children(root,'deviceFacts').flatMap(section=>children(section,'deviceFact')).map(fact=>({id:attr(fact,'id'),component:attr(fact,'typeRef'),label:attr(fact,'label'),states:attr(fact,'states'),outerStates:attr(fact,'outerStates')}));
  return {requirements,outputs,facts};
});
const details=computed(()=>{
  const root=new DOMParser().parseFromString(props.xml,'application/xml').documentElement;
  return {
    mappings:entries(root,'mappings').map(m=>({id:attr(m,'id'),kind:attr(m,'kind'),resource:attr(m,'resourceRef'),size:attr(m,'groupSize'),members:entries(m,'members').map(member=>attr(member,'instanceSetRef')+(attr(member,'last')?'（至 '+attr(member,'last')+'）':'')).join('、')})),
    mappingRefs:[...root.getElementsByTagNameNS('*','mappingBinding')].map(m=>attr(m,'mappingRef')),
    constraints:entries(root,'structuralConstraints').map(c=>({id:attr(c,'id'),detail:attr(c,'instanceSetRef')?attr(c,'instanceSetRef')+' 数量 = '+attr(c,'quantity'):'映射完整覆盖：'+attr(c,'mappingRef')})),
    notes:entries(child(root,'annotations'),'notes').map(n=>({id:attr(n,'id'),text:child(n,'description')?.textContent||''})),
    externals:entries(root,'externalEndpoints').map(e=>({id:attr(e,'id'),label:attr(e,'label'),ports:entries(e,'ports').map(p=>attr(p,'id')+' ('+attr(p,'direction')+')').join('、')}))
  };
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
   <template v-if="model.kind==='definition' && dependencyModel.requirements.length">
    <h3 class="section-title">能力输入依赖</h3>
    <table><thead><tr><th>设备 / 能力</th><th>路径 / 输入端口</th><th>所需信号</th><th>保持范围</th></tr></thead><tbody>
     <tr v-for="req in dependencyModel.requirements" :key="req.key"><td>{{req.owner}}<br>{{req.capability}}</td><td><code>{{req.path}} / {{req.input}}</code></td><td><code>{{req.signal}}</code></td><td>{{req.maintain==='execution'?'持续到本次命令验收完成':req.maintain==='start'?'命令下发前检查':req.maintain}}</td></tr>
    </tbody></table>
   </template>
   <template v-if="model.kind==='definition' && dependencyModel.outputs.length">
    <h3 class="section-title">输出成立条件</h3>
    <table><thead><tr><th>设备 / 输出端口</th><th>输出信号</th><th>自身事实</th><th>进一步依赖</th></tr></thead><tbody>
     <tr v-for="output in dependencyModel.outputs" :key="output.key"><td>{{output.owner}}<br><code>{{output.port}}</code></td><td><code>{{output.signal}}</code></td><td><code>{{output.fact}}</code></td><td><div v-for="input in output.inputs" :key="input.id"><code>{{input.path}} / {{input.input}} → {{input.signal}}</code></div><span v-if="!output.inputs.length">无上游输入依赖</span></td></tr>
    </tbody></table>
   </template>
   <div v-if="model.kind==='definition'" class="component-grid">
    <article v-for="type in model.types" :key="type.id" class="component-card">
      <small>{{type.role}} · {{type.quantity}} 套</small><h3>{{type.label}}</h3><code>{{type.id}}</code>
      <p>{{type.capabilities.length}} 项能力</p>
      <ul><li v-for="capability in type.capabilities.slice(0,5)" :key="capability">{{capability}}</li><li v-if="type.capabilities.length>5">另有 {{type.capabilities.length-5}} 项</li></ul>
    </article>
   </div>
   <table v-if="model.kind==='definition'"><thead><tr><th>实例集合</th><th>类型</th><th>数量</th><th>编号模式</th></tr></thead><tbody><tr v-for="set in model.sets" :key="set.id"><td>{{set.id}}</td><td>{{set.type}}</td><td>{{set.quantity}}</td><td><code>{{set.pattern}}</code></td></tr></tbody></table>
   <template v-if="model.kind==='definition'"><h3 class="section-title">拓扑连接</h3><table><thead><tr><th>连接</th><th>源</th><th>目标</th><th>关系</th></tr></thead><tbody><tr v-for="connection in model.connections" :key="connection.id"><td>{{connection.id}}</td><td><code>{{connection.source}}</code></td><td><code>{{connection.target}}</code></td><td>{{connection.relation}}</td></tr></tbody></table><h3 v-if="model.openIssues.length" class="section-title">未决项</h3><ul v-if="model.openIssues.length" class="issues"><li v-for="issue in model.openIssues" :key="issue.id"><strong>{{issue.id}}</strong> · {{issue.status}} · {{issue.summary}}</li></ul></template>
   <template v-else-if="model.kind==='binding'"><h3 class="section-title">实例绑定</h3><table><thead><tr><th>实例集合</th><th>运行时键</th><th>运行设备模式</th></tr></thead><tbody><tr v-for="binding in model.bindings" :key="binding.id"><td>{{binding.id}}</td><td>{{binding.runtimeKey}}</td><td><code>{{binding.runtimePattern}}</code></td></tr></tbody></table></template>
   <template v-else-if="model.kind==='projection'"><h3 class="section-title">观测</h3><ul class="issues"><li v-for="item in model.observations" :key="item"><code>{{item}}</code></li></ul><h3 class="section-title">事实</h3><ul class="issues"><li v-for="fact in model.facts" :key="fact.id"><strong>{{fact.id}}</strong> · {{fact.label}}<br><code>{{fact.condition}}</code></li></ul><h3 class="section-title">放行谓词</h3><ul class="issues"><li v-for="guard in model.guards" :key="guard.id"><code>{{guard.id}}</code><br>{{guard.condition}}</li></ul></template>
   <template v-if="model.kind==='projection' && dependencyModel.facts.length"><h3 class="section-title">逐设备事实</h3><table><thead><tr><th>事实</th><th>设备类型</th><th>状态判据（任一满足）</th></tr></thead><tbody><tr v-for="fact in dependencyModel.facts" :key="fact.id"><td>{{fact.label}}<br><code>{{fact.id}}</code></td><td>{{fact.component}}</td><td><div v-if="fact.states">状态：<code>{{fact.states}}</code></div><div v-if="fact.outerStates">复合状态：<code>{{fact.outerStates}}</code></div></td></tr></tbody></table></template>
   <template v-if="details.mappings.length"><h3 class="section-title">资源与实例映射</h3><ul class="issues"><li v-for="m in details.mappings" :key="m.id"><strong>{{m.id}}</strong> · {{m.kind}} · {{m.resource}} → {{m.members}}<span v-if="m.size"> · 每组 {{m.size}}</span></li></ul></template>
   <template v-if="details.mappingRefs.length"><h3 class="section-title">引用系统定义中的映射</h3><ul class="issues"><li v-for="ref in details.mappingRefs" :key="ref"><code>{{ref}}</code></li></ul></template>
   <template v-if="details.externals.length"><h3 class="section-title">外部端点</h3><ul class="issues"><li v-for="e in details.externals" :key="e.id"><strong>{{e.label}}</strong> · {{e.ports}}</li></ul></template>
   <template v-if="details.constraints.length"><h3 class="section-title">结构约束</h3><ul class="issues"><li v-for="c in details.constraints" :key="c.id">{{c.id}} · {{c.detail}}</li></ul></template>
   <template v-if="details.notes.length"><h3 class="section-title">设计说明（不参与执行）</h3><ul class="issues"><li v-for="n in details.notes" :key="n.id">{{n.text}}</li></ul></template>
  </template>
 </section>
</template>
<style scoped>
.system-definition-preview{min-height:100%;padding:28px 32px;color:#263a3a;background:#f6f8f7;overflow:auto}.system-definition-preview header{border-bottom:1px solid #d8e0dd;padding-bottom:18px}.eyebrow{font-size:11px;font-weight:700;color:#54716a}.system-definition-preview h2{font-size:18px;margin:6px 0 0}.definition-summary{display:grid;grid-template-columns:repeat(auto-fit,minmax(240px,1fr));gap:16px;margin:22px 0}.definition-summary article{background:#fff;border:1px solid #d4dfdb;padding:14px}.definition-summary small{display:block;color:#627773;margin-bottom:8px}.definition-summary strong{display:block;font-size:12px;line-height:1.7;font-weight:500}.component-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(210px,1fr));gap:14px}.component-card{background:#fff;border:1px solid #d4dfdb;border-top:4px solid #327d69;padding:14px;min-width:0}.component-card small{color:#627773}.component-card h3{font-size:16px;margin:8px 0 2px}.component-card code{font-size:11px;color:#54716a}.component-card p{font-size:12px;color:#60736f;margin:12px 0 4px}.component-card ul{padding-left:17px;margin:4px 0;font-size:12px;line-height:1.6}.section-title{font-size:16px;margin:24px 0 8px}table{border-collapse:collapse;width:100%;margin-top:22px;font-size:13px}th,td{text-align:left;padding:9px 12px;border-bottom:1px solid #d8e0dd}th{color:#526965;font-weight:600}code{overflow-wrap:anywhere}.issues{padding:0 0 0 20px;font-size:12px;line-height:1.7}@media(max-width:650px){.system-definition-preview{padding:18px}}
</style>
