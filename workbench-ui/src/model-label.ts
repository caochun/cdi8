import type {Model} from './api';

/** Prefer the model's business name while keeping the filename available separately. */
export function modelLabel(model:Model,xml=model.draft):string {
  if(!/\.(?:xml|scxml|bpmn)$/i.test(model.name))return model.name;
  try {
    const doc=new DOMParser().parseFromString(xml,'application/xml');
    if(model.kind==='bpmn')return doc.getElementsByTagNameNS('http://www.omg.org/spec/BPMN/20100524/MODEL','process')[0]?.getAttribute('name')||model.name;
    if(model.kind==='system')return doc.documentElement.getAttribute('id')||model.name;
    const metadata=[...doc.getElementsByTagNameNS('*','data')].find(item=>item.getAttribute('id')==='componentMetadata');
    return (metadata?JSON.parse(metadata.textContent||'{}').display_name:'')||model.name;
  }catch{return model.name;}
}
