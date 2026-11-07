<script setup lang="ts">
import {computed,nextTick,onMounted,onBeforeUnmount,ref,watch} from 'vue';
import {createRuntime} from './runtime-controller';
import './runtime.css';
const props=defineProps<{runId:string}>();
const emit=defineEmits<{change:[run:any]}>();
const host=ref<HTMLElement>(),selectedRun=ref<any>(null),loading=ref(true),view=ref('scene');
const mode=computed(()=>loading.value?'正在载入':selectedRun.value?.ended?'历史快照':selectedRun.value?'实时运行':props.runId?'记录不可用':'流程预览');
let runtime:ReturnType<typeof createRuntime>|undefined,loadTicket=0;
async function loadRun(id:string){
  const ticket=++loadTicket;loading.value=true;view.value='scene';
  await runtime?.setRun(id);if(ticket===loadTicket)loading.value=false;
}
onMounted(()=>{runtime=createRuntime(host.value!,run=>{selectedRun.value=run;emit('change',run);});void loadRun(props.runId);});
watch(()=>props.runId,id=>{void loadRun(id);});
watch(view,async()=>{await nextTick();window.dispatchEvent(new Event('resize'));});
function stepTab(event:KeyboardEvent){
  if(!['ArrowLeft','ArrowRight','Home','End'].includes(event.key))return;
  event.preventDefault();view.value=event.key==='Home'?'scene':event.key==='End'?'records':view.value==='scene'?'records':'scene';
  void nextTick(()=>host.value?.querySelector<HTMLButtonElement>('[role="tab"][aria-selected="true"]')?.focus());
}
onBeforeUnmount(()=>runtime?.destroy());
defineExpose({refresh:()=>runtime?.refresh()});
</script>
<template>
 <section ref="host" class="runtime-view" aria-label="实验流程运行调试">
  <div class="runtime-mode" :class="{'is-history':selectedRun?.ended}"><div><strong>{{mode}}</strong><span>{{loading?'正在读取流程和设备记录…':selectedRun?.ended?'只读回看本次运行记录的设备状态与执行结果。':selectedRun?'流程与设备状态实时更新，等待事项可在右侧处理。':runId?'请重新选择运行记录，或刷新后重试。':'查看已部署的流程结构。开始一次运行，请前往“运行准备”。'}}</span></div><span data-runtime-id="connection" class="badge">正在连接设备</span></div>
  <div v-show="!runId" class="runtime-controls"><label>已部署流程<select data-runtime-id="process" aria-label="查看流程定义"></select></label></div>
    <div data-runtime-id="error" role="alert" hidden></div>
    <section class="run-overview" aria-label="运行概览">
      <div class="overview-item status"><span>运行状态</span><strong data-runtime-id="outcome">流程定义</strong></div>
      <div class="overview-item"><span>当前流程</span><strong data-runtime-id="run-name">尚未启动</strong></div>
      <div class="overview-item"><span>当前活动</span><strong data-runtime-id="run-stage">—</strong></div>
      <div class="overview-item"><span>已登记命令结果</span><strong data-runtime-id="command-summary">—</strong></div>
      <div class="overview-item"><span>运行时间</span><strong data-runtime-id="elapsed">—</strong></div>
    </section>
    <section data-runtime-id="system-incidents" class="system-incidents" aria-label="设施状态与本发证据" hidden><div class="incident-heading"><strong>设施状态与本发证据</strong><span data-runtime-id="incident-count"></span></div><div data-runtime-id="incident-list"></div></section>
    <p data-runtime-id="unresolved-actions" class="unresolved-actions" role="status" hidden></p>
    <div class="runtime-tabs" role="tablist" aria-label="运行调试视图" @keydown="stepTab"><button id="runtime-scene-tab" role="tab" aria-controls="runtime-scene" :aria-selected="view==='scene'" :tabindex="view==='scene'?0:-1" @click="view='scene'">{{selectedRun?.ended?'运行快照':'运行现场'}}</button><button id="runtime-records-tab" role="tab" aria-controls="runtime-records" :aria-selected="view==='records'" :tabindex="view==='records'?0:-1" @click="view='records'">执行记录</button></div>
    <div id="runtime-scene" v-show="view==='scene'" role="tabpanel" aria-labelledby="runtime-scene-tab">
    <div class="debug-workspace">
    <section class="diagram-panel">
      <div class="diagram-heading">
        <div class="section-title"><h2>流程进度</h2><span data-runtime-id="diagram-state" class="badge">流程定义</span><span data-runtime-id="reason"></span></div>
        <div class="diagram-tools"><label>查看层级<select data-runtime-id="scope"></select></label><button data-runtime-id="fit">适合窗口</button><button data-runtime-id="expand-flow">展开流程图 ↗</button></div>
      </div>
      <div class="flow-workspace">
        <div class="flow-maps"><div class="primary-map"><div class="map-caption" data-runtime-id="flow-caption">完整流程</div><div data-runtime-id="canvas"></div></div>
          <section data-runtime-id="child-flow" hidden><div class="map-caption"><span data-runtime-id="child-flow-title">当前阶段</span><span>阶段内部进度</span></div><div data-runtime-id="child-canvas"></div></section>
        </div>

      </div>
      <div class="legend"><span class="dot active"></span>执行中 / 等待 <span class="dot completed"></span>已完成 <span class="dot cancelled"></span>已中断 <span class="legend-help">底图保持全貌 · 点击节点查看设备与记录</span></div>
    </section>
    <aside class="debug-aside" aria-label="当前活动与等待处理">
      <section class="debug-panel attention-panel"><div class="attention-heading"><h2>{{selectedRun?.ended?'结束时等待事项':'等待与反馈'}}</h2><span data-runtime-id="wait-count" class="badge">0 项</span></div><p class="attention-hint">{{selectedRun?.ended?'本次运行记录，只读查看。':'命令结果或外部条件会影响流程推进。'}}</p><p data-runtime-id="wait-empty" class="wait-empty">当前没有等待事项</p><div data-runtime-id="wait-diagnostics" hidden><div data-runtime-id="wait-list"></div></div></section>
      <div class="activity-focus"><div class="focus-heading"><span class="pulse-dot"></span><h3>当前活动 · 局部放大</h3></div><div data-runtime-id="activity-lenses"></div></div>
    </aside>
    </div>
    <section v-show="!!selectedRun" class="devices-panel">
      <div class="section-title"><div><h2>设备状态进度 <span data-runtime-id="device-summary" class="muted"></span></h2><small data-runtime-id="device-hint">状态机全貌与当前状态同时展示</small></div>
        <label class="device-filter"><input type="checkbox" data-runtime-id="only-related" checked>仅参与本流程</label>
      </div>
      <div data-runtime-id="devices" class="devices"></div>
    </section>
    <details v-show="!!selectedRun" class="context-panel"><summary>同步与环境条件 <span data-runtime-id="context-summary" class="muted"></span></summary><div data-runtime-id="context-devices"></div></details>
    </div>
    <section id="runtime-records" v-show="view==='records'" role="tabpanel" aria-labelledby="runtime-records-tab" class="debug-panel execution-records"><div class="section-title"><h2>关联事件时间线</h2><span data-runtime-id="trace-count"></span></div><p class="records-hint">按时间倒序关联流程活动与设备状态，最多展示最近 200 条。点击运行现场的节点可查看命令详情。</p><div data-runtime-id="trace-list"></div></section>
    <p class="note">运行中显示实时设备状态；历史运行显示已记录的设备快照。停止流程会停止后续编排，设备已接受的动作由组件端完成。<span data-runtime-id="run-id"></span></p>

  <dialog data-runtime-id="detail-dialog"><div class="dialog-heading"><div><span class="eyebrow">DETAIL VIEW</span><h2 data-runtime-id="detail-title">节点详情</h2></div><button data-runtime-id="close-detail" aria-label="关闭详情">关闭 ×</button></div>
    <div data-runtime-id="expanded-graph"></div><div class="section-title detail-heading"><h3>执行记录</h3><span data-runtime-id="detail-count"></span></div><div data-runtime-id="details"></div>
  </dialog>
 </section>
</template>
