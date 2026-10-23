import './qualifications.css';
const NS='https://cdi8.cn/control/qualification/v1';

/** Read the deployed extension declarations themselves; never keep a second set of labels. */
export function attachQualificationOverlays(viewer:any) {
 let document:Document|null=null;
 const render=()=>{
  if(!document)return;
  const overlays=viewer.get('overlays'),registry=viewer.get('elementRegistry');
  overlays.remove({type:'qualification'});
  const byOwner=new Map<string,Element[]>();
  for(const name of ['establish','require','actionContract','entryCondition','qualificationPolicy','qualification'])for(const binding of document.getElementsByTagNameNS(NS,name)) {
   if(name==='qualification'&&!binding.getAttribute('dependsOn'))continue;
   const owner=binding.parentElement?.parentElement?.getAttribute('id');
   if(owner&&registry.get(owner))byOwner.set(owner,[...(byOwner.get(owner)||[]),binding]);
  }
  for(const [id,bindings] of byOwner) {
   const badge=window.document.createElement('div');badge.className='qualification-annotation';
   for(const binding of bindings) {
    const row=window.document.createElement('div'),ref=binding.getAttribute('qualificationRef')||'';
    row.textContent=binding.localName==='establish'?`成功后建立：${ref}`:binding.localName==='require'
      ?`${binding.getAttribute('at')==='dispatch'?'下发前':'完成前'}要求全部：${ref}`:binding.localName==='actionContract'
      ?'进入条件：遵守设备动作契约':binding.localName==='entryCondition'
      ?'实验附加条件：设备空闲且状态允许':binding.localName==='qualification'
      ?`资格依赖：${binding.getAttribute('id')} ← ${binding.getAttribute('dependsOn')}`:'持续监测资格；失效或未知即中断';
    badge.append(row);
   }
   badge.title=bindings.map(binding=>new XMLSerializer().serializeToString(binding)).join('\n');
   overlays.add(id,'qualification',{position:{top:-Math.max(52,bindings.length*17+8),left:0},html:badge});
  }
 };
 viewer.on('import.parse.start',(event:any)=>{document=new DOMParser().parseFromString(event.xml,'application/xml');});
 viewer.on('import.done',render);
 viewer.on('root.set',()=>queueMicrotask(render));
}
