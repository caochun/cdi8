<script setup lang="ts">
import {onMounted,onBeforeUnmount,ref,watch} from 'vue';
import {prepareXml} from './layout';
// @ts-ignore The viewer exposes a stable runtime API; XML is the model contract.
import BpmnViewer from 'bpmn-js/lib/NavigatedViewer';
import 'bpmn-js/dist/assets/diagram-js.css';import 'bpmn-js/dist/assets/bpmn-js.css';import 'bpmn-js/dist/assets/bpmn-font/css/bpmn.css';
const props=defineProps<{kind:string;xml:string;state?:string;selected?:string}>();const emit=defineEmits<{select:[id:string];error:[message:string];ready:[]}>();
const host=ref<HTMLElement>();let viewer:any;let svg:SVGSVGElement|null=null;let frame:HTMLIFrameElement|null=null;let sequence=0;let lastGood='';let resize:ResizeObserver;let cleanup:(()=>void)|undefined;let stateAncestry=new Set<string>();
const labels=ref<Record<string,string>>({});
function updateStateAncestry(){stateAncestry=new Set<string>();if(!props.state)return;const doc=new DOMParser().parseFromString(props.xml,'application/xml');const active=[...doc.getElementsByTagNameNS('*','state')].find(node=>node.getAttribute('id')===props.state);let parent:Element|null=active??null;while(parent&&parent!==doc.documentElement){const id=parent.getAttribute('id');if(id)stateAncestry.add(id);parent=parent.parentElement;}}
function mark(){if(viewer&&props.kind==='bpmn'){const registry=viewer.get('elementRegistry'),canvas=viewer.get('canvas');registry.forEach((e:any)=>canvas.removeMarker(e.id,'editor-selected'));if(props.selected&&registry.get(props.selected))canvas.addMarker(props.selected,'editor-selected');}
updateStateAncestry();
if(svg)for(const node of svg.querySelectorAll('[data-state]')){const id=node.getAttribute('data-state');node.classList.toggle('state-current',id===props.state);node.classList.toggle('state-ancestor',!!id&&stateAncestry.has(id)&&id!==props.state);node.classList.toggle('editor-selected',id===props.selected);}}
async function render(){
 if(!host.value||!props.xml)return;const ticket=++sequence;
 try{
   const doc=new DOMParser().parseFromString(props.xml,'application/xml');if(doc.querySelector('parsererror'))throw Error(doc.querySelector('parsererror')!.textContent||'XML 格式错误');
   updateStateAncestry();
  if(props.kind==='bpmn'){
   cleanup?.();cleanup=undefined;if(!viewer){host.value.replaceChildren();viewer=new BpmnViewer({container:host.value});viewer.on('element.click',(event:any)=>emit('select',event.element.id));}
   await viewer.importXML(await prepareXml(props.xml));if(ticket!==sequence)return;
   const diagrams=viewer.getDefinitions().diagrams;if(!diagrams?.length)throw Error('BPMN 缺少可视化布局（BPMN DI）');
   const preferred=diagrams.find((d:any)=>d.plane.bpmnElement.id==='normal')||diagrams[0];await viewer.open(preferred.id);viewer.get('canvas').zoom('fit-viewport','auto');svg=null;lastGood=props.xml;mark();emit('ready');
  }else{
   if(viewer){viewer.destroy();viewer=null;}
   const data=doc.querySelector('data[id="componentMetadata"]');const metadata=data?JSON.parse(data.textContent||'{}'):{};labels.value={...metadata.outer_labels,...metadata.inner_labels};
   if(lastGood===props.xml&&svg){mark();return;}
   cleanup?.();frame=document.createElement('iframe');frame.className='layout-renderer';frame.src='/state-chart-renderer.html';frame.title='SCXML 布局';document.body.append(frame);
   const source=frame.contentWindow;const id='preview-'+ticket;let timeout:ReturnType<typeof setTimeout>;
   const receive=(event:MessageEvent)=>{
    if(event.origin!==location.origin||event.source!==source)return;
    if(event.data?.type==='renderer-ready')source?.postMessage({type:'layout-model',id,xml:props.xml},location.origin);
    if(event.data?.type==='model-layout'&&event.data.id===id){
     cleanup?.();cleanup=undefined;if(ticket!==sequence)return;
     if(event.data.error){emit('error',event.data.error);return;}
     const parsed=new DOMParser().parseFromString(event.data.svg,'image/svg+xml');svg=document.importNode(parsed.documentElement,true) as unknown as SVGSVGElement;
     for(const node of svg.querySelectorAll('[data-state]')){const key=node.getAttribute('data-state')!;const text=node.querySelector(':scope > text') as SVGTextElement|null;const full=labels.value[key]||key;if(text){text.textContent=full;text.setAttribute('font-family','Inter, PingFang SC, Microsoft YaHei, sans-serif');text.setAttribute('font-weight','600');fitLabel(node as SVGGElement,text,full);const title=document.createElementNS('http://www.w3.org/2000/svg','title');title.textContent=full;node.prepend(title);}}
     svg.addEventListener('click',event=>{const node=(event.target as Element).closest('[data-state]');if(node)emit('select',node.getAttribute('data-state')!);});
     enableNavigation(svg);host.value!.replaceChildren(svg);lastGood=props.xml;mark();emit('ready');
    }
   };
   cleanup=()=>{clearTimeout(timeout);window.removeEventListener('message',receive);frame?.remove();};window.addEventListener('message',receive);
   timeout=setTimeout(()=>{cleanup?.();emit('error','状态图布局超时，保留上次预览');},30000);
  }
 }catch(error:any){if(ticket===sequence){if(viewer&&lastGood&&props.kind==='bpmn'){try{await viewer.importXML(await prepareXml(lastGood));viewer.get('canvas').zoom('fit-viewport','auto');}catch{}}emit('error',error.message);}}
}
function enableNavigation(target:SVGSVGElement){let pan:any;const initial=target.getAttribute('viewBox')!;target.addEventListener('wheel',e=>{e.preventDefault();const p=new DOMPoint(e.clientX,e.clientY).matrixTransform(target.getScreenCTM()!.inverse()),b=target.viewBox.baseVal,f=e.deltaY>0?1.15:1/1.15;if(b.width*f<1||b.width*f>100000)return;target.setAttribute('viewBox',`${p.x-(p.x-b.x)*f} ${p.y-(p.y-b.y)*f} ${b.width*f} ${b.height*f}`);},{passive:false});target.addEventListener('pointerdown',e=>{if(e.button!==0)return;pan={x:e.clientX,y:e.clientY,box:[target.viewBox.baseVal.x,target.viewBox.baseVal.y,target.viewBox.baseVal.width,target.viewBox.baseVal.height],matrix:target.getScreenCTM()!.inverse()};target.setPointerCapture(e.pointerId);});target.addEventListener('pointermove',e=>{if(!pan)return;const from=new DOMPoint(pan.x,pan.y).matrixTransform(pan.matrix),to=new DOMPoint(e.clientX,e.clientY).matrixTransform(pan.matrix);target.setAttribute('viewBox',`${pan.box[0]+from.x-to.x} ${pan.box[1]+from.y-to.y} ${pan.box[2]} ${pan.box[3]}`);});target.addEventListener('pointerup',()=>pan=null);target.addEventListener('pointercancel',()=>pan=null);target.addEventListener('dblclick',()=>target.setAttribute('viewBox',initial));}
function fitLabel(node:SVGGElement,text:SVGTextElement,full:string){const rect=node.querySelector(':scope > rect') as SVGRectElement|null;if(!rect)return;const width=Number(rect.getAttribute('width')||0);if(!width)return;const estimate=[...full].reduce((sum,char)=>sum+(char.charCodeAt(0)>255?1:0.58),0)*5.5;const available=Math.max(4,width-4);if(estimate>available){text.setAttribute('textLength',String(available));text.setAttribute('lengthAdjust','spacingAndGlyphs');}else{text.removeAttribute('textLength');text.removeAttribute('lengthAdjust');}}
onMounted(()=>{render();resize=new ResizeObserver(()=>{if(viewer)try{viewer.get('canvas').zoom('fit-viewport','auto');}catch{}});resize.observe(host.value!);});
watch(()=>[props.kind,props.xml],render);watch(()=>[props.state,props.selected],mark);
onBeforeUnmount(()=>{sequence++;cleanup?.();resize?.disconnect();viewer?.destroy();});
</script>
<template><div ref="host" class="diagram-preview"></div></template>
