'use strict';
// A domain element carried by a durable BPMN wait activity. Its own renderer
// exposes gateway semantics, while Flowable executes its extension policy.
const qualificationProperties=['targets','action','state','outer','continuationStates','timeout'];
window.qualificationModdle={
  name:'GXLF',uri:'https://cdi8.cn/bpmn/qualification',prefix:'gxlf',xml:{tagAlias:'lowerCase'},
  types:[{name:'QualificationGateway',superClass:['Element'],properties:qualificationProperties.map(name=>({name,type:'String',isAttr:true}))}]
};
window.qualificationPolicy=element=>element?.businessObject?.extensionElements?.values?.find(v=>v.$type==='gxlf:QualificationGateway');
function QualificationRenderer(eventBus,textRenderer){
  const svg=(tag,attrs)=>{const e=document.createElementNS('http://www.w3.org/2000/svg',tag);for(const [k,v] of Object.entries(attrs))e.setAttribute(k,v);return e;};
  eventBus.on('render.shape',1600,event=>{
    const el=event.element;if(!window.qualificationPolicy(el))return;
    const w=el.width,h=el.height;
    const diamond=svg('polygon',{points:`${w/2},0 ${w},${h/2} ${w/2},${h} 0,${h/2}`,fill:'#fff',stroke:'#1c5267','stroke-width':2});
    event.gfx.appendChild(diamond);
    const all=svg('text',{x:w/2,y:h*.47,'text-anchor':'middle','font-size':27,fill:'#1c5267','pointer-events':'none'});all.textContent='∀';event.gfx.appendChild(all);
    const check=svg('path',{d:`M ${w*.38} ${h*.61} L ${w*.47} ${h*.70} L ${w*.65} ${h*.51}`,fill:'none',stroke:'#1c5267','stroke-width':3,'pointer-events':'none'});event.gfx.appendChild(check);
    const label=textRenderer.createText(el.businessObject.name||el.id,{box:{width:w+100,height:40},align:'center-middle',style:{fontSize:13}});
    label.setAttribute('transform',`translate(-50,${h+5})`);event.gfx.appendChild(label);
    return diamond;
  });
  eventBus.on('render.getShapePath',1600,event=>{
    const el=event.element;if(!window.qualificationPolicy(el))return;
    const x=el.x,y=el.y,w=el.width,h=el.height;
    return `M ${x+w/2} ${y} L ${x+w} ${y+h/2} L ${x+w/2} ${y+h} L ${x} ${y+h/2} Z`;
  });
}
QualificationRenderer.$inject=['eventBus','textRenderer'];
window.qualificationRendererModule={__init__:['qualificationRenderer'],qualificationRenderer:['type',QualificationRenderer]};
