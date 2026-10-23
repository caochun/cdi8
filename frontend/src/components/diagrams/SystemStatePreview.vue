<script setup lang="ts">
import {computed} from 'vue';
const props=defineProps<{xml:string}>();
const model=computed(()=>{
  const doc=new DOMParser().parseFromString(props.xml,'application/xml');
  if(doc.querySelector('parsererror'))return {error:'系统状态 XML 格式错误',states:[],transitions:[]};
  const root=doc.documentElement;
  return {
    error:'',
    states:[...root.children].filter(node=>node.localName==='state').map(node=>({id:node.getAttribute('id')||'',label:node.getAttribute('label')||node.getAttribute('id')||'',initial:(node.getAttribute('id')||'')===root.getAttribute('initial'),checks:[...node.children].filter(child=>child.localName==='require').map(check=>`${check.getAttribute('collection')} · ${check.getAttribute('component')}: ${check.getAttribute('states')||check.getAttribute('outerStates')}`)})),
    transitions:[...root.children].filter(node=>node.localName==='transition').map(node=>({id:node.getAttribute('id')||'',from:node.getAttribute('from')||'',to:node.getAttribute('to')||''}))
  };
});
</script>
<template>
 <section class="system-state-preview" aria-label="实验系统状态声明预览">
  <header><div><span class="eyebrow">EXPERIMENT STATE DECLARATION</span><h2>{{model.states.length}} 个系统状态 · {{model.transitions.length}} 个状态转换</h2></div></header>
  <p v-if="model.error" class="preview-warning">{{model.error}}</p>
  <div v-else class="system-state-grid">
   <article v-for="state in model.states" :key="state.id" class="system-state-node" :class="{initial:state.initial}">
    <small>{{state.initial?'初始系统状态':'系统状态'}}</small><h3>{{state.label}}</h3><code>{{state.id}}</code>
    <ul v-if="state.checks.length"><li v-for="check in state.checks" :key="check">{{check}}</li></ul><p v-else>由后续设备观察或操作转换离开</p>
   </article>
  </div>
  <table v-if="model.transitions.length"><thead><tr><th>转换</th><th>来源</th><th>目标</th></tr></thead><tbody><tr v-for="transition in model.transitions" :key="transition.id"><td>{{transition.id}}</td><td>{{transition.from}}</td><td>{{transition.to}}</td></tr></tbody></table>
 </section>
</template>
<style scoped>
.system-state-preview{min-height:100%;padding:28px 32px;color:#263a3a;background:#f6f8f7;overflow:auto}
header{display:flex;align-items:center;justify-content:space-between;border-bottom:1px solid #d8e0dd;padding-bottom:18px}
.eyebrow{font-size:11px;font-weight:700;letter-spacing:0;color:#54716a}
h2{font-size:18px;margin:6px 0 0}
.system-state-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(230px,1fr));gap:16px;margin:24px 0}
.system-state-node{background:#fff;border:1px solid #d4dfdb;border-top:4px solid #78918a;padding:16px;min-width:0}
.system-state-node.initial{border-top-color:#327d69}
.system-state-node small{color:#627773}.system-state-node h3{font-size:17px;margin:8px 0 2px}.system-state-node code{font-size:12px;color:#54716a}
.system-state-node ul{padding-left:18px;margin:14px 0 0;font-size:13px;line-height:1.6}.system-state-node p{font-size:13px;color:#60736f}
table{border-collapse:collapse;width:100%;margin-top:20px;font-size:13px}th,td{text-align:left;padding:10px 12px;border-bottom:1px solid #d8e0dd}th{color:#526965;font-weight:600}
@media(max-width:650px){.system-state-preview{padding:18px}.system-state-grid{grid-template-columns:1fr}}
</style>
