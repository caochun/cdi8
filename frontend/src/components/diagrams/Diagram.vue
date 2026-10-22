<script setup lang="ts">
import {onMounted,onBeforeUnmount,ref,watch} from 'vue';
import {prepareXml} from './layout';
import {attachQualificationOverlays} from './qualifications';
// @ts-ignore The viewer exposes a stable runtime API; XML is the model contract.
import BpmnViewer from 'bpmn-js/lib/NavigatedViewer';
import 'bpmn-js/dist/assets/diagram-js.css';import 'bpmn-js/dist/assets/bpmn-js.css';import 'bpmn-js/dist/assets/bpmn-font/css/bpmn.css';
const props=defineProps<{kind:string;xml:string;state?:string;selected?:string}>();const emit=defineEmits<{select:[id:string];error:[message:string];ready:[]}>();
const host=ref<HTMLElement>();let viewer:any;let svg:SVGSVGElement|null=null;let frame:HTMLIFrameElement|null=null;let sequence=0;let lastGood='';let resize:ResizeObserver;let cleanup:(()=>void)|undefined;let stateAncestry=new Set<string>();
const labels=ref<Record<string,string>>({});
const navigation=ref(false),zoom=ref(100),viewMode=ref<'readable'|'fit'|'manual'>('readable');let wholeView='';
const SVG_FONT=6,READABLE_FONT=14;
function initialState(){
 const doc=new DOMParser().parseFromString(props.xml,'application/xml');
 let node:Element=doc.documentElement;
 while(node){const id=node.getAttribute('initial')||[...node.children].find(child=>child.localName==='initial')?.querySelector('transition')?.getAttribute('target');const children=[...node.children].filter(child=>['state','final'].includes(child.localName));const next=children.find(child=>child.id===id)||children[0];if(!next)break;node=next;}
 return node.getAttribute('id')||'';
}
function currentBox(){
 const id=props.state||initialState();
 const node=[...svg?.querySelectorAll<SVGGElement>('[data-state]')||[]].find(node=>node.getAttribute('data-state')===id);
 const shape=node?.querySelector<SVGRectElement>(':scope > rect');if(!shape||!svg)return null;
 const box=shape.getBBox(),matrix=svg.getScreenCTM()!.inverse().multiply(shape.getScreenCTM()!);
 return new DOMPoint(box.x+box.width/2,box.y+box.height/2).matrixTransform(matrix);
}
function setView(x:number,y:number,width:number,height:number){
 if(!svg||width<=0||height<=0)return;
 svg.setAttribute('viewBox',`${x} ${y} ${width} ${height}`);
 zoom.value=Math.round(Math.min(host.value!.clientWidth/width,host.value!.clientHeight/height)*100);
}
function readableView(){
 if(!svg||!host.value?.clientWidth||!host.value.clientHeight)return;
 viewMode.value='readable';const center=currentBox()||new DOMPoint(svg.viewBox.baseVal.x+svg.viewBox.baseVal.width/2,svg.viewBox.baseVal.y+svg.viewBox.baseVal.height/2);
 const scale=READABLE_FONT/SVG_FONT,width=host.value.clientWidth/scale,height=host.value.clientHeight/scale;
 setView(center.x-width/2,center.y-height/2,width,height);
}
function fitView(){if(!svg)return;viewMode.value='fit';const [x,y,width,height]=wholeView.split(/\s+/).map(Number);setView(x,y,width,height);}
function zoomBy(factor:number){if(!svg)return;viewMode.value='manual';const b=svg.viewBox.baseVal;setView(b.x+b.width*(1-factor)/2,b.y+b.height*(1-factor)/2,b.width*factor,b.height*factor);}
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
   navigation.value=false;cleanup?.();cleanup=undefined;if(!viewer){host.value.replaceChildren();viewer=new BpmnViewer({container:host.value});attachQualificationOverlays(viewer);viewer.on('element.click',(event:any)=>emit('select',event.element.id));}
   await viewer.importXML(await prepareXml(props.xml));if(ticket!==sequence)return;
   const diagrams=viewer.getDefinitions().diagrams;if(!diagrams?.length)throw Error('BPMN 缺少可视化布局（BPMN DI）');
   const preferred=diagrams.find((d:any)=>d.plane.bpmnElement.id==='normal')||diagrams[0];await viewer.open(preferred.id);viewer.get('canvas').zoom('fit-viewport','auto');svg=null;lastGood=props.xml;mark();emit('ready');
  }else{
   if(viewer){viewer.destroy();viewer=null;}
   const data=doc.querySelector('data[id="componentMetadata"]');const metadata=data?JSON.parse(data.textContent||'{}'):{};labels.value={...metadata.outer_labels,...metadata.inner_labels};
   if(lastGood===props.xml&&svg){mark();emit('ready');return;}
   cleanup?.();frame=document.createElement('iframe');frame.className='layout-renderer';frame.src='/state-chart-renderer.html';frame.title='SCXML 布局';document.body.append(frame);
   const source=frame.contentWindow;const id='preview-'+ticket;let timeout:ReturnType<typeof setTimeout>;
   const receive=(event:MessageEvent)=>{
    if(event.origin!==location.origin||event.source!==source)return;
    if(event.data?.type==='renderer-ready')source?.postMessage({type:'layout-model',id,xml:props.xml,labels:{...labels.value}},location.origin);
    if(event.data?.type==='model-layout'&&event.data.id===id){
     cleanup?.();cleanup=undefined;if(ticket!==sequence)return;
     if(event.data.error){emit('error',event.data.error);return;}
     const parsed=new DOMParser().parseFromString(event.data.svg,'image/svg+xml');svg=document.importNode(parsed.documentElement,true) as unknown as SVGSVGElement;
     for(const node of svg.querySelectorAll('[data-state]')){const key=node.getAttribute('data-state')!;const text=node.querySelector(':scope > text') as SVGTextElement|null;const full=labels.value[key]||key;if(text){if(!text.querySelector('tspan'))text.textContent=full;text.removeAttribute('textLength');text.removeAttribute('lengthAdjust');const title=document.createElementNS('http://www.w3.org/2000/svg','title');title.textContent=full;node.prepend(title);}}
     svg.addEventListener('click',event=>{const node=(event.target as Element).closest('[data-state]');if(node)emit('select',node.getAttribute('data-state')!);});
     wholeView=svg.getAttribute('viewBox')!;host.value!.replaceChildren(svg);enableNavigation(svg);navigation.value=true;readableView();lastGood=props.xml;mark();emit('ready');
    }
   };
   cleanup=()=>{clearTimeout(timeout);window.removeEventListener('message',receive);frame?.remove();};window.addEventListener('message',receive);
   timeout=setTimeout(()=>{cleanup?.();emit('error','状态图布局超时，保留上次预览');},30000);
  }
 }catch(error:any){if(ticket===sequence){if(viewer&&lastGood&&props.kind==='bpmn'){try{await viewer.importXML(await prepareXml(lastGood));viewer.get('canvas').zoom('fit-viewport','auto');}catch{}}emit('error',error.message);}}
}
function enableNavigation(target:SVGSVGElement){let pan:any;target.addEventListener('wheel',e=>{e.preventDefault();const p=new DOMPoint(e.clientX,e.clientY).matrixTransform(target.getScreenCTM()!.inverse()),b=target.viewBox.baseVal,f=e.deltaY>0?1.15:1/1.15;if(b.width*f<1||b.width*f>100000)return;viewMode.value='manual';setView(p.x-(p.x-b.x)*f,p.y-(p.y-b.y)*f,b.width*f,b.height*f);},{passive:false});target.addEventListener('pointerdown',e=>{if(e.button!==0)return;pan={x:e.clientX,y:e.clientY,box:[target.viewBox.baseVal.x,target.viewBox.baseVal.y,target.viewBox.baseVal.width,target.viewBox.baseVal.height],matrix:target.getScreenCTM()!.inverse()};target.setPointerCapture(e.pointerId);});target.addEventListener('pointermove',e=>{if(!pan)return;const from=new DOMPoint(pan.x,pan.y).matrixTransform(pan.matrix),to=new DOMPoint(e.clientX,e.clientY).matrixTransform(pan.matrix);viewMode.value='manual';setView(pan.box[0]+from.x-to.x,pan.box[1]+from.y-to.y,pan.box[2],pan.box[3]);});target.addEventListener('pointerup',()=>pan=null);target.addEventListener('pointercancel',()=>pan=null);target.addEventListener('dblclick',readableView);}

onMounted(()=>{render();resize=new ResizeObserver(()=>{if(viewer)try{viewer.get('canvas').zoom('fit-viewport','auto');}catch{}else if(svg){if(viewMode.value==='readable')readableView();else if(viewMode.value==='fit')fitView();else{const b=svg.viewBox.baseVal,scale=zoom.value/100,width=host.value!.clientWidth/scale,height=host.value!.clientHeight/scale;setView(b.x+(b.width-width)/2,b.y+(b.height-height)/2,width,height);}}});resize.observe(host.value!);});
watch(()=>[props.kind,props.xml],render);watch(()=>[props.state,props.selected],mark);watch(()=>props.state,()=>{if(viewMode.value==='readable')readableView();});
onBeforeUnmount(()=>{sequence++;cleanup?.();resize?.disconnect();viewer?.destroy();});
</script>
<template><div class="diagram-preview"><div ref="host" class="diagram-content"></div><div v-if="navigation" class="diagram-navigation" role="toolbar" aria-label="状态机视图"><div class="diagram-zoom"><span class="diagram-zoom-modes" role="group" aria-label="状态图显示模式"><button :aria-pressed="viewMode==='readable'" @click="readableView">清晰阅读</button><button :aria-pressed="viewMode==='fit'" @click="fitView">查看全图</button></span><span class="diagram-zoom-scale" role="group" aria-label="状态图缩放"><button aria-label="缩小状态图" @click="zoomBy(1.25)">−</button><output aria-label="状态图缩放比例">{{zoom}}%</output><button aria-label="放大状态图" @click="zoomBy(0.8)">＋</button></span></div></div></div></template>
