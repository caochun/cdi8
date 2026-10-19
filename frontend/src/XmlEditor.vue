<script setup lang="ts">
import {onMounted,onBeforeUnmount,ref,watch} from 'vue';
import * as monaco from 'monaco-editor/esm/vs/editor/editor.api';
import EditorWorker from 'monaco-editor/esm/vs/editor/editor.worker?worker';
import 'monaco-editor/esm/vs/basic-languages/xml/xml.contribution';
(self as any).MonacoEnvironment={getWorker:()=>new EditorWorker()};
const props=defineProps<{value:string;diagnostics:any[]}>();
const emit=defineEmits<{change:[value:string];select:[id:string]}>();
const host=ref<HTMLElement>();let editor:monaco.editor.IStandaloneCodeEditor;let changing=false;
onMounted(()=>{
 editor=monaco.editor.create(host.value!,{value:props.value,language:'xml',theme:'vs',automaticLayout:true,minimap:{enabled:false},fontSize:12,lineHeight:21,scrollBeyondLastLine:false,tabSize:2,wordWrap:'off',padding:{top:14},renderLineHighlight:'gutter'});
 editor.onDidChangeModelContent(()=>{if(!changing)emit('change',editor.getValue());});
 editor.onDidChangeCursorPosition(e=>{const line=editor.getModel()?.getLineContent(e.position.lineNumber)||'';const id=line.match(/\bid="([^"]+)"/);if(id)emit('select',id[1]);});
});
watch(()=>props.value,value=>{if(editor&&editor.getValue()!==value){changing=true;editor.setValue(value);changing=false;}});
watch(()=>props.diagnostics,items=>{const model=editor?.getModel();if(model)monaco.editor.setModelMarkers(model,'validation',items.map(item=>({severity:monaco.MarkerSeverity.Error,message:item.message,startLineNumber:Math.max(1,item.line||1),startColumn:Math.max(1,item.column||1),endLineNumber:Math.max(1,item.line||1),endColumn:Math.max(2,(item.column||1)+1)})));},{deep:true});
function reveal(id:string){const model=editor?.getModel();if(!model)return;const matches=model.findMatches(`id="${id}"`,false,false,true,null,true);if(matches.length){editor.setSelection(matches[0].range);editor.revealLineInCenter(matches[0].range.startLineNumber);editor.focus();}}
onBeforeUnmount(()=>editor?.dispose());defineExpose({reveal});
</script>
<template><div class="xml-editor" ref="host"></div></template>
