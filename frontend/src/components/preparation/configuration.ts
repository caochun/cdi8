import type {Model} from '../../api';

export interface ProcessSelection {model:string;revision:string}
export interface DeviceGroup {model:string;revision:string;component:string;count:number;autoComplete:boolean;delayMs:number}
export interface RunConfiguration {
  entry:string;processes:ProcessSelection[];devices:DeviceGroup[];autoComplete:boolean;
  bindings:Record<string,string[]>;variables:Record<string,unknown>;conditions:Record<string,boolean>;
  environment?:{id:string};appliedDevices?:DeviceGroup[];appliedAutoComplete?:boolean;appliedConditions?:Record<string,boolean>;
}
export const emptyConfiguration=():RunConfiguration=>({entry:'',processes:[],devices:[],autoComplete:true,bindings:{},variables:{commandTimeout:'PT15M',workflowTimeout:'PT45M'},conditions:{}});
export function parseObject(text:string,label:string):Record<string,any> {
  let value:unknown;try{value=JSON.parse(text);}catch{throw Error(label+'的 JSON 格式不正确');}
  if(!value||typeof value!=='object'||Array.isArray(value))throw Error(label+'必须是 JSON 对象');
  return value as Record<string,any>;
}
export function stableJson(value:unknown):string {
  return JSON.stringify(value,(_,item)=>item&&typeof item==='object'&&!Array.isArray(item)?Object.fromEntries(Object.entries(item).sort(([a],[b])=>a.localeCompare(b))):item);
}
const BPMN='http://www.omg.org/spec/BPMN/20100524/MODEL';
/** Follow callActivity references in the selected immutable XML, including cycles. */
export function resolveProcesses(models:Model[],entry:string,preferred:ProcessSelection[]) {
  const candidates=models.filter(m=>m.kind==='bpmn');
  const chosen=new Map(preferred.map(p=>[p.model,p.revision]));
  const catalogue=candidates.map(model=>{
    const revision=chosen.get(model.id)||model.revisions.at(-1)?.id||'';
    const version=model.revisions.find(r=>r.id===revision);
    const doc=version?new DOMParser().parseFromString(version.xml,'application/xml'):null;
    return {model,revision,doc,keys:doc?[...doc.getElementsByTagNameNS(BPMN,'process')].map(p=>p.getAttribute('id')):[]};
  });
  const selections:ProcessSelection[]=[],issues:string[]=[],queue=[entry],seen=new Set<string>(),collections=new Set<string>();
  if(!entry)return {selections,collections:[],issues:['请选择要调试的流程']};
  while(queue.length){
    const id=queue.shift()!;if(seen.has(id))continue;seen.add(id);
    const item=catalogue.find(c=>c.model.id===id);
    if(!item){issues.push('找不到流程模型：'+id);continue;}
    selections.push({model:id,revision:item.revision});
    if(!item.doc||item.doc.querySelector('parsererror')){issues.push('请为“'+item.model.name+'”选择有效的已保存版本');continue;}
    for(const loop of item.doc.getElementsByTagNameNS(BPMN,'multiInstanceLoopCharacteristics')){
      const expression=loop.getAttributeNS('http://flowable.org/bpmn','collection')||'';
      const match=/^\$\{([A-Za-z0-9_]+)\}$/.exec(expression);if(match)collections.add(match[1]);
    }
    for(const wait of item.doc.getElementsByTagNameNS(BPMN,'receiveTask'))if(wait.getAttributeNS('https://cdi8.cn/workbench','condition'))collections.add(wait.getAttributeNS('https://cdi8.cn/workbench','collection')||'contextTargets');
    for(const call of item.doc.getElementsByTagNameNS(BPMN,'callActivity')){
      const key=call.getAttribute('calledElement')||'';
      const owners=catalogue.filter(c=>c.keys.includes(key));
      const preferredOwners=owners.filter(owner=>chosen.has(owner.model.id)),matches=preferredOwners.length?preferredOwners:owners;
      if(matches.length===1)queue.push(matches[0].model.id);
      else issues.push(matches.length?'子流程标识重复：'+key:'找不到子流程的已保存版本：'+(key||call.getAttribute('id')));
    }
  }
  return {selections,collections:[...collections],issues:[...new Set(issues)]};
}
export function deviceIssues(rows:DeviceGroup[],models:Model[]):string[] {
  const issues:string[]=[],types=new Set<string>();
  rows.forEach((row,i)=>{
    const label='组件 '+(i+1)+'：';
    if(!models.some(m=>m.kind==='scxml'&&m.id===row.model&&m.revisions.some(r=>r.id===row.revision)))issues.push(label+'请选择已保存的状态机版本');
    if(!/^[A-Za-z_][A-Za-z0-9_-]*$/.test(row.component)||row.component==='context')issues.push(label+'组件类型需以字母或下划线开头，可包含数字、下划线和连字符，不能使用保留类型 context');
    if(types.has(row.component))issues.push(label+'组件类型重复：'+row.component);types.add(row.component);
    if(!Number.isInteger(row.count)||row.count<1||row.count>200)issues.push(label+'实例数量需为 1–200 的整数');
    if(!Number.isInteger(row.delayMs)||row.delayMs<0||row.delayMs>3600000)issues.push(label+'反馈延迟需为 0–3600000 毫秒的整数');
  });
  return issues;
}
export function defaultBindings(rows:DeviceGroup[]) {
  const bindings:Record<string,string[]>={allComponents:[],contextTargets:['context_01']};
  for(const row of rows){
    const ids=Array.from({length:Math.min(200,Math.max(0,Number(row.count)||0))},(_,i)=>row.component+'_'+String(i+1).padStart(2,'0'));
    bindings[row.component+'Targets']=ids;bindings.allComponents.push(...ids);
  }
  return bindings;
}
