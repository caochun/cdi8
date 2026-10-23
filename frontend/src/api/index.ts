export async function api(path:string,body?:unknown):Promise<any> {
 const response=await fetch('/api'+path,body===undefined?{}:{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});
 const value=await response.json();if(!response.ok)throw Error(value.error||response.statusText);return value;
}
export interface Revision {id:string;created:string;xml:string;validation:any}
export interface Model {id:string;name:string;kind:'bpmn'|'scxml'|'system';draft:string;revisions:Revision[]}
