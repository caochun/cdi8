import './scope-navigation.css';

/** A boundary event belongs to the host activity's parent plane, not its inner plane. */
export async function openOverview(viewer:any) {
 const diagrams=viewer.getDefinitions()?.diagrams||[];
 const overview=diagrams.find((d:any)=>['bpmn:Process','bpmn:Collaboration'].includes(d.plane.bpmnElement.$type))||diagrams[0];
 if(overview)await viewer.open(overview.id);
}

export function attachScopeNavigation(viewer:any, options:{select?:HTMLSelectElement;onChange?:()=>void;onError:(error:any)=>void}) {
 const select=options.select||document.createElement('select');
 let toolbar:HTMLLabelElement|undefined;
 if(!options.select){
  toolbar=document.createElement('label');toolbar.className='bpmn-scope-navigation';
  toolbar.append('查看层级',select);toolbar.hidden=true;
  viewer.get('canvas').getContainer().append(toolbar);
 }
 select.setAttribute('aria-label','查看流程层级');
 function sync(){
  const diagrams=viewer.getDefinitions()?.diagrams||[];
  const current=viewer.get('canvas').getRootElement()?.businessObject;
  const active=diagrams.find((d:any)=>d.plane.bpmnElement.id===current?.id);
  if(active)select.value=active.id;
 }
 viewer.on('import.done',()=>{
  const diagrams=viewer.getDefinitions()?.diagrams||[];
  select.replaceChildren(...diagrams.map((d:any)=>{
   const scope=d.plane.bpmnElement,inner=scope.$type==='bpmn:SubProcess';
   const boundaries=scope.flowElements?.some((e:any)=>e.$type==='bpmn:BoundaryEvent');
   const label=inner?'子流程内部':boundaries?'完整流程（含边界事件）':'完整流程';
   return new Option(`${label} · ${scope.name||scope.id}`,d.id);
  }));
  select.disabled=diagrams.length<2;if(toolbar)toolbar.hidden=diagrams.length<2;
  sync();
 });
 viewer.on('root.set',sync);
 async function open(id:string){
  try{
   await viewer.open(id);viewer.get('canvas').zoom('fit-viewport','auto');options.onChange?.();
  }catch(error){options.onError(error);}
 }
 select.onchange=()=>{void open(select.value);};
 viewer.on('element.dblclick',(event:any)=>{
  const diagram=viewer.getDefinitions()?.diagrams?.find((d:any)=>d.plane.bpmnElement.id===event.element.id);
  if(diagram)void open(diagram.id);
 });
}
