// @ts-ignore bpmn-auto-layout publishes a browser API without TypeScript declarations.
import {layoutProcess} from 'bpmn-auto-layout';
const BPMN='http://www.omg.org/spec/BPMN/20100524/MODEL',DI='http://www.omg.org/spec/BPMN/20100524/DI';
/** Generate missing presentation coordinates without changing executable XML. */
export async function prepareXml(xml:string):Promise<string> {
 const original=new DOMParser().parseFromString(xml,'application/xml');
 if(original.querySelector('parsererror'))return xml;
 const scopes=[...original.getElementsByTagNameNS(BPMN,'process'),...original.getElementsByTagNameNS(BPMN,'subProcess')];
 let changed=false;
 for(const scope of scopes){
  const id=scope.getAttribute('id')!;
  const plane=[...original.getElementsByTagNameNS(DI,'BPMNPlane')].find(p=>p.getAttribute('bpmnElement')===id);
  const drawn=new Set(plane?[...plane.children].map(e=>e.getAttribute('bpmnElement')):[]);
  const flow=[...scope.children].filter(e=>e.namespaceURI===BPMN&&/Task$|Event$|Gateway$|subProcess$|callActivity$|sequenceFlow$/.test(e.localName));
  if(plane&&flow.every(e=>drawn.has(e.getAttribute('id'))))continue;
  const copy=original.cloneNode(true) as Document,root=copy.documentElement;
  for(const child of [...root.children])if(['process','collaboration','BPMNDiagram'].includes(child.localName))child.remove();
  const process=copy.createElementNS(BPMN,'bpmn:process');process.setAttribute('id',id);process.setAttribute('isExecutable','true');
  for(const child of [...scope.childNodes])process.appendChild(copy.importNode(child,true));root.appendChild(process);
  const laidOut=await layoutProcess(new XMLSerializer().serializeToString(copy));
  const diagram=new DOMParser().parseFromString(laidOut,'application/xml').getElementsByTagNameNS(DI,'BPMNDiagram')[0];
  if(!diagram)throw Error('无法为流程生成布局：'+id);
  const node=original.importNode(diagram,true);node.setAttribute('id',id+'_auto_diagram');node.getElementsByTagNameNS(DI,'BPMNPlane')[0].setAttribute('id',id+'_auto_plane');
  plane?.parentElement?.remove();original.documentElement.appendChild(node);changed=true;
 }
 return changed?new XMLSerializer().serializeToString(original):xml;
}
