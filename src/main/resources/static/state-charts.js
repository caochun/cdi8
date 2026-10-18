'use strict';
const StateCharts = (() => {
  const models = new Map();
  let sequence = 0;
  async function load() {
    const response = await fetch('/api/models');
    if (!response.ok) throw Error('设备状态机模型加载失败');
    for (const xml of Object.values(await response.json())) {
      const doc = new DOMParser().parseFromString(xml, 'application/xml');
      const data = [...doc.getElementsByTagNameNS('*','data')].find(n=>n.id==='componentMetadata' || n.getAttribute('id')==='componentMetadata');
      if (!data) continue;
      const metadata = JSON.parse(data.textContent);
      models.set(metadata.model_id, {xml, metadata, labels:{...metadata.outer_labels,...metadata.inner_labels}, doc});
    }
  }
  function layout(model) {
    if (model.promise) return model.promise;
    model.promise = new Promise((resolve,reject) => {
      const frame = document.createElement('iframe');
      frame.className='layout-renderer'; frame.title='状态图布局'; frame.setAttribute('aria-hidden','true');
      const id = 'layout-'+(++sequence);
      let timeout;
      const cleanup = () => {clearTimeout(timeout); window.removeEventListener('message',receive); frame.remove();};
      const receive = event => {
        if (event.origin!==location.origin || event.source!==frame.contentWindow) return;
        if (event.data?.type==='renderer-ready') frame.contentWindow.postMessage({type:'layout-model',id,xml:model.xml},location.origin);
        if (event.data?.type==='model-layout' && event.data.id===id) {
          cleanup();
          if (event.data.error) reject(Error(event.data.error));
          else {model.svg=event.data.svg; model.nodes=event.data.nodes; resolve(model);}
        }
      };
      window.addEventListener('message',receive);
      timeout=setTimeout(()=>{cleanup();reject(Error('状态图布局超时'));},30000);
      frame.src='state-chart-renderer.html'; document.body.append(frame);
    });
    return model.promise;
  }
  function uniqueSvg(source) {
    const svg = new DOMParser().parseFromString(source,'image/svg+xml').documentElement;
    const prefix='chart-'+(++sequence)+'-';
    const ids=new Map([...svg.querySelectorAll('[id]')].map(n=>[n.id,prefix+n.id]));
    for(const node of [svg,...svg.querySelectorAll('*')]) for(const attr of [...node.attributes]) {
      let value=attr.value;
      if(attr.name==='id' && ids.has(value)) value=ids.get(value);
      value=value.replace(/url\(["']?#([^\)"']+)["']?\)/g,(match,id)=>ids.has(id)?`url(#${ids.get(id)})`:match);
      if((attr.name==='href'||attr.name==='xlink:href') && ids.has(value.slice(1))) value='#'+ids.get(value.slice(1));
      node.setAttribute(attr.name,value);
    }
    return document.importNode(svg,true);
  }
  function draw(host, model, snapshot, focused=false) {
    host.replaceChildren();
    const svg=uniqueSvg(model.svg);
    const active=snapshot.state_id;
    const connected=snapshot.connected!==false;
    const node=[...model.doc.getElementsByTagNameNS('*','state')].find(n=>n.getAttribute('id')===active);
    const ancestry=new Set();
    for(let parent=node;parent && parent!==model.doc.documentElement;parent=parent.parentElement) ancestry.add(parent.getAttribute('id'));
    for(const group of svg.querySelectorAll('[data-state]')) {
      const id=group.getAttribute('data-state');
      const text=group.querySelector(':scope > text');
      if(text && model.labels[id]) {
        // Long labels wrap in the focus panel below; avoid altering layout geometry.
        text.textContent=model.labels[id].length>10?model.labels[id].slice(0,9)+'…':model.labels[id];
        const rect=group.querySelector(':scope > rect');
        if(rect){
          const maximum=Math.max(5,Number(rect.getAttribute('width'))-5);
          const estimate=text.textContent.length*parseFloat(text.getAttribute('font-size')||'4');
          if(estimate>maximum){text.setAttribute('textLength',maximum);text.setAttribute('lengthAdjust','spacingAndGlyphs');}
        }
      }
      group.classList.toggle('state-focus-target',id===active);
      group.classList.toggle('state-current',connected && id===active);
      group.classList.toggle('state-ancestor',connected && ancestry.has(id) && id!==active);
    }
    const box=model.nodes[active];
    if(focused && box) svg.setAttribute('viewBox',`${box.x-4} ${box.y-6} ${box.width+8} ${box.height+12}`);
    host.append(svg);
  }
  async function mount(host, modelId, snapshot) {
    const model=models.get(modelId);
    if(!model) {host.textContent=modelId?'当前模型尚未提供状态图':'等待设备模型';return;}
    host.textContent='正在布局状态机…';
    try {
      await layout(model);
      if(!host.isConnected) return;
      host.replaceChildren();
      const overview=document.createElement('div'); overview.className='state-overview';
      const focus=document.createElement('div'); focus.className='state-focus';
      const lens=document.createElement('div'); lens.className='state-lens';
      const label=document.createElement('div');label.className='state-focus-label';
      focus.append(lens,label);host.append(overview,focus);
      host.update = value => {
        draw(overview,model,value);
        draw(lens,model,value,true);
        label.replaceChildren();
        const caption=document.createElement('span');caption.textContent=value.connected===false?'最后已知状态':'当前状态 · 局部放大';
        const title=document.createElement('strong');title.textContent=model.labels[value.state_id] || value.state_id || '状态未确认';
        label.append(caption,title);
      };
      host.update(host.snapshot || snapshot);
    }catch(error){host.textContent='状态图不可用：'+error.message;}
  }
  function update(host,modelId,snapshot) {
    const signature=JSON.stringify([modelId,snapshot.state_id,snapshot.connected]);
    if(host.signature===signature) return;
    host.signature=signature; host.snapshot=snapshot;
    if(host.modelId!==modelId || !host.update) {
      host.modelId=modelId; host.update=null; mount(host,modelId,snapshot);
    } else host.update(snapshot);
  }
  function action(modelId,action) {return models.get(modelId)?.metadata.actions?.[action]?.label || action || '空闲';}
  function expand(host,modelId,snapshot) {
    const model=models.get(modelId);
    if(!model?.svg) {host.textContent='状态图尚未加载';return;}
    draw(host,model,snapshot);
    const svg=host.querySelector('svg'), initial=svg.getAttribute('viewBox');
    let pan=null;
    svg.addEventListener('wheel',event=>{
      event.preventDefault();
      const point=new DOMPoint(event.clientX,event.clientY).matrixTransform(svg.getScreenCTM().inverse());
      const box=svg.viewBox.baseVal, factor=event.deltaY>0?1.15:1/1.15;
      const initialWidth=Number(initial.split(/\s+/)[2]);
      if(box.width*factor<initialWidth/25 || box.width*factor>initialWidth*2) return;
      svg.setAttribute('viewBox',`${point.x-(point.x-box.x)*factor} ${point.y-(point.y-box.y)*factor} ${box.width*factor} ${box.height*factor}`);
    },{passive:false});
    svg.addEventListener('pointerdown',event=>{
      if(event.button!==0)return;
      pan={x:event.clientX,y:event.clientY,box:[svg.viewBox.baseVal.x,svg.viewBox.baseVal.y,svg.viewBox.baseVal.width,svg.viewBox.baseVal.height],matrix:svg.getScreenCTM().inverse()};
      svg.setPointerCapture(event.pointerId);
    });
    svg.addEventListener('pointermove',event=>{
      if(!pan)return;
      const from=new DOMPoint(pan.x,pan.y).matrixTransform(pan.matrix), to=new DOMPoint(event.clientX,event.clientY).matrixTransform(pan.matrix);
      svg.setAttribute('viewBox',`${pan.box[0]+from.x-to.x} ${pan.box[1]+from.y-to.y} ${pan.box[2]} ${pan.box[3]}`);
    });
    for(const type of ['pointerup','pointercancel','lostpointercapture'])svg.addEventListener(type,()=>{pan=null;});
    svg.addEventListener('dblclick',()=>svg.setAttribute('viewBox',initial));
  }
  return {load,update,expand,action};
})();
