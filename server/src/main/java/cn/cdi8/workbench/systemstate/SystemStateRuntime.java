package cn.cdi8.workbench.systemstate;

import cn.cdi8.workbench.integration.tango.TangoGateway;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.flowable.bpmn.model.*;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.delegate.DelegateExecution;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import cn.cdi8.workbench.run.ActionContractModel;

/** Runtime projection of the declared whole-experiment state, driven by verified BPMN milestones. */
@Component("systemStates")
public class SystemStateRuntime {
    public static final String STATE="experimentSystemState";
    private final ObjectMapper json;
    private final TangoGateway gateway;
    private final ObjectProvider<RepositoryService> repositories;
    private final ObjectProvider<RuntimeService> engine;
    private final Map<String,SystemStateModel> models=new HashMap<>();
    private final Deque<Observation> observations=new ConcurrentLinkedDeque<>();
    private final String eventEpoch=UUID.randomUUID().toString();
    private long sequence;
    private volatile long lostBefore;
    private record Observation(long sequence,JsonNode value) {}

    public SystemStateRuntime(ObjectMapper json,TangoGateway gateway,ObjectProvider<RepositoryService> repositories,ObjectProvider<RuntimeService> engine) {
        this.json=json;this.gateway=gateway;this.repositories=repositories;this.engine=engine;
    }
    private RuntimeService runtime(){return engine.getObject();}
    private RepositoryService repository(){return repositories.getObject();}
    public synchronized void observe(TangoGateway.Notice notice) {
        observations.addLast(new Observation(++sequence,notice.payload().deepCopy()));
        while(observations.size()>16384) { Observation removed=observations.removeFirst();lostBefore=removed.sequence(); }
    }
    private SystemStateModel model(String definitionId,String id) {
        String key=definitionId+":"+id;
        return models.computeIfAbsent(key,ignored->{
            try {
                String resource="system-model-"+id+".xml";
                String deploymentId=repository().getProcessDefinition(definitionId).getDeploymentId();
                try(var input=repository().getResourceAsStream(deploymentId,resource)) {
                    if(input!=null)return SystemStateModel.parse(new String(input.readAllBytes(),StandardCharsets.UTF_8));
                } catch(org.flowable.common.engine.api.FlowableObjectNotFoundException missing) { /* built-in model fallback */ }
                var classpath=new ClassPathResource("system-models/"+id+".system.xml");
                if(classpath.exists())try(var input=classpath.getInputStream()){return SystemStateModel.parse(new String(input.readAllBytes(),StandardCharsets.UTF_8));}
                throw new IllegalArgumentException("部署缺少系统状态声明："+id);
            } catch(Exception ex){throw new IllegalStateException("无法加载系统状态声明："+ex.getMessage(),ex);}
        });
    }
    public synchronized void open(DelegateExecution execution) {
        var process=repository().getBpmnModel(execution.getProcessDefinitionId()).getMainProcess();
        var bindings=SystemStateModel.elements(process,"systemModel");
        if(bindings.size()!=1)throw new IllegalStateException("BPMN process 必须引用一个 systemModel");
        String id=SystemStateModel.attr(bindings.get(0),"ref");
        if(id.isBlank())throw new IllegalStateException("systemModel 缺少 ref");
        execution.setVariable("systemStateModelId",id);
        var definition=model(execution.getProcessDefinitionId(),id);var state=json.createObjectNode();
        state.put("state",definition.initial()).put("integrity","valid").put("model",id)
            .put("changedAt",System.currentTimeMillis()).putNull("lastTransition").putNull("reason");
        state.putArray("history");state.putObject("evidence").put("eventCursor",sequence).put("eventEpoch",eventEpoch);
        execution.setVariable(STATE,state.toString());
    }
    public void bind(DelegateExecution execution) {
        if(!ActionContractModel.elements(execution.getCurrentFlowElement(),"actionContract").isEmpty())
            execution.setVariableLocal("deviceActionContract",true);
        for(var entry:ActionContractModel.elements(execution.getCurrentFlowElement(),"entryCondition")) {
            var parameters=json.createObjectNode();
            parameters.putObject("entry_condition").put("states",ActionContractModel.attr(entry,"states"))
                .put("outer_states",ActionContractModel.attr(entry,"outerStates")).put("idle",true);
            execution.setVariableLocal("commandParameters",parameters.toString());
        }
    }
    public synchronized void report(DelegateExecution execution,String transitionId) {
        var definition=model(execution.getProcessDefinitionId(),Objects.toString(execution.getVariable("systemStateModelId"),""));
        var transition=definition.transition(transitionId);
        ObjectNode state=current(execution.getProcessInstanceId());
        requireValid(state);
        if(!transition.from().equals(state.path("state").asText()))
            throw new IllegalStateException("系统状态转换来源不匹配：要求 "+transition.from()+"，当前为 "+state.path("state").asText());
        String sourceStatus=checkState(execution.getProcessInstanceId(),definition,
            definition.state(transition.from()),state,true);
        if(sourceStatus!=null) {
            Violation issue=latch(execution.getProcessInstanceId(),state,
                sourceStatus.startsWith("未知：")?"unknown":"violated",
                sourceStatus.replaceFirst("^(未知：|违反：)",""));
            throw new IllegalStateException(issue.reason());
        }
        String targetStatus=checkState(execution.getProcessInstanceId(),definition,
            definition.state(transition.to()),state,true);
        if(targetStatus!=null) {
            Violation issue=latch(execution.getProcessInstanceId(),state,
                targetStatus.startsWith("未知：")?"unknown":"violated",
                targetStatus.replaceFirst("^(未知：|违反：)",""));
            throw new IllegalStateException(issue.reason());
        }
        long now=System.currentTimeMillis();String from=state.path("state").asText();
        ObjectNode item=json.createObjectNode().put("transition",transitionId).put("from",from).put("to",transition.to()).put("at",now);
        ((ArrayNode)state.path("history")).add(item);state.put("state",transition.to()).put("changedAt",now).put("lastTransition",transitionId);
        runtime().setVariable(execution.getProcessInstanceId(),STATE,state.toString());
    }
    public synchronized void require(DelegateExecution execution,String expected) {
        Violation issue=inspect(execution.getProcessInstanceId(),true);
        if(issue!=null)throw new IllegalStateException(issue.reason());
        ObjectNode state=current(execution.getProcessInstanceId());requireValid(state);
        if(!expected.equals(state.path("state").asText()))
            throw new IllegalStateException("实验系统状态不满足：要求 "+expected+"，当前为 "+state.path("state").asText());
    }
    public synchronized JsonNode dispatch(org.flowable.engine.runtime.Execution execution,java.util.function.Supplier<JsonNode> send) {
        Violation issue=inspect(execution.getProcessInstanceId(),true);
        if(issue!=null)return rejected(execution,issue.reason());
        var variables=runtime().getVariablesLocal(execution.getId());
        Object parameters=variables.get("commandParameters");
        boolean contract=Boolean.TRUE.equals(variables.get("deviceActionContract"));
        if(contract||parameters!=null) {
            String device=Objects.toString(variables.get("commandDevice"),"");String reason=null;
            try {
                JsonNode snapshot=gateway.snapshot(device);
                if(contract) {
                    String action=Objects.toString(variables.get("commandAction"),"");
                    JsonNode readiness=snapshot.path("action_contracts").path(action);
                    if(!snapshot.path("supports_action_contract").asBoolean(false))reason="设备未声明支持动作契约";
                    else if(!readiness.path("ready").isBoolean())reason="设备动作就绪状态未知："+action;
                    else if(!readiness.path("ready").asBoolean())reason="设备动作未就绪："+readiness.path("reason").asText(action);
                }
                if(reason==null&&parameters!=null) {
                    JsonNode entry=json.readTree(parameters.toString()).path("entry_condition");
                    if(!snapshot.path("supports_entry_condition").asBoolean(false))reason="设备未声明支持受理时进入条件检查";
                    else if(!snapshot.has("active_command_id"))reason="设备忙闲状态未知";
                    else if(snapshot.hasNonNull("active_command_id"))reason="设备已有执行中的动作";
                    else if(!contains(entry.path("states").asText(),snapshot.path("state_id").asText())
                            && !contains(entry.path("outer_states").asText(),snapshot.path("outer_state").asText()))reason="当前设备状态不满足实验附加进入条件";
                }
            } catch(Exception ex){reason="动作进入条件无法确认："+ex.getMessage();}
            if(reason!=null)return rejected(execution,device+"："+reason);
            runtime().setVariableLocal(execution.getId(),"dispatchAttempted",true);
        }
        return send.get();
    }
    private boolean contains(String choices,String value) { return !value.isBlank()&&Arrays.asList(choices.trim().split("\\s+")).contains(value); }
    private JsonNode rejected(org.flowable.engine.runtime.Execution execution,String reason) {
        var value=json.createObjectNode().put("command_id",Objects.toString(runtime().getVariableLocal(execution.getId(),"commandId"),""))
            .put("action",Objects.toString(runtime().getVariableLocal(execution.getId(),"commandAction"),""))
            .put("status","rejected").put("reason",reason);
        runtime().setVariableLocal(execution.getId(),"sent",true);
        return value;
    }
    public synchronized Violation inspect(String pid,boolean forceRead) {
        if(!runtime().hasVariable(pid,STATE))return null;
        ObjectNode state=current(pid);
        if(!state.path("integrity").asText().equals("valid"))return new Violation(state.path("reason").asText("实验系统状态已失效"));
        if(!gateway.connected())return latch(pid,state,"unknown","Tango 连接已断开，无法确认实验系统状态");
        JsonNode evidence=state.path("evidence");
        if(evidence.has("eventEpoch")&&!eventEpoch.equals(evidence.path("eventEpoch").asText())
                &&evidence.fields().hasNext())return latch(pid,state,"unknown","控制服务重启后系统状态事件连续性无法确认");
        String modelId=state.path("model").asText();
        var process=runtime().createProcessInstanceQuery().processInstanceId(pid).singleResult();
        if(process==null)return null;
        SystemStateModel model;
        try { model=model(process.getProcessDefinitionId(),modelId); }
        catch(Exception ex){return latch(pid,state,"unknown","系统状态声明无法恢复："+ex.getMessage());}
        String status=checkState(pid,model,model.state(state.path("state").asText()),state,forceRead);
        if(status!=null)return latch(pid,state,status.startsWith("未知：")?"unknown":"violated",status.replaceFirst("^(未知：|违反：)",""));
        return null;
    }
    private String checkState(String pid,SystemStateModel model,SystemStateModel.State target,ObjectNode state,boolean forceRead) {
        ObjectNode evidence=(ObjectNode)state.path("evidence");
        long initialCursor=evidence.path("eventCursor").asLong(0);
        long now=System.currentTimeMillis();
        for(var predicate:target.predicates()) {
            Object raw=runtime().getVariable(pid,predicate.collection());
            if(!(raw instanceof List<?> targets)||targets.isEmpty())return "违反：系统状态目标集合为空："+predicate.collection();
            boolean matched=false;
            for(Object item:targets) {
                if(!(item instanceof Map<?,?> targetDevice))return "违反：系统状态目标格式无效";
                String device=Objects.toString(targetDevice.get("id"),""),component=Objects.toString(targetDevice.get("component"),"");
                if(!predicate.component().equals(component))continue;
                matched=true;
                ObjectNode prior=evidence.has(device)?(ObjectNode)evidence.get(device):json.createObjectNode();
                if(!prior.has("cursor"))prior.put("cursor",initialCursor);
                String reason=observeEvents(device,predicate,prior);
                if(reason!=null)return reason;
                long observed=prior.path("observedAt").asLong(0);
                if(forceRead||now-observed>=model.evidenceMaxAgeMs()/2) {
                    if(!gateway.connected())return "未知：Tango 连接已断开，无法确认 "+device;
                    try {
                        JsonNode snapshot=gateway.snapshot(device);
                        reason=evidenceProblem(prior,snapshot);
                        if(reason!=null)return reason;
                        if(!accepts(predicate,snapshot))return "违反："+device+" 当前状态不满足系统状态 "+target.label()+"："+snapshot.path("state_id").asText();
                        update(prior,snapshot,sequence,now);evidence.set(device,prior);
                    }catch(Exception ex){return "未知：读取设备 "+device+" 状态失败："+ex.getMessage();}
                }
            }
            if(!matched)return "违反：系统状态条件未匹配任何 "+predicate.component()+" 设备："+predicate.collection();
        }
        return null;
    }
    private String observeEvents(String device,SystemStateModel.Predicate predicate,ObjectNode evidence) {
        long cursor=evidence.path("cursor").asLong(0);
        if(cursor<lostBefore)return "未知：系统状态事件证据出现缺口";
        long latest=cursor;
        for(Observation observation:observations)if(observation.sequence()>cursor) {
            JsonNode event=observation.value();
            if("connection_lost".equals(event.path("type").asText()))return "未知：Tango 事件通道曾断开";
            if(!device.equals(event.path("device_id").asText()))continue;
            if(!event.path("connected").asBoolean(true))return "未知：设备断连："+device;
            JsonNode snapshot=event.path("snapshot");String reason=evidenceProblem(evidence,snapshot);
            if(reason!=null)return reason;
            if(!accepts(predicate,snapshot))return "违反：设备事件显示系统状态条件失效："+device+" / "+snapshot.path("state_id").asText();
            update(evidence,snapshot,observation.sequence(),System.currentTimeMillis());
            latest=observation.sequence();
        }
        if(latest>cursor)evidence.put("cursor",latest);
        return null;
    }
    private String evidenceProblem(JsonNode previous,JsonNode current) {
        if(current==null||!current.path("state_revision").isIntegralNumber()||current.path("state_revision").asLong(-1)<0
                ||current.path("source_id").asText().isBlank())return "未知：设备状态缺少实例标识或修订号";
        if(previous.hasNonNull("source")&&!previous.path("source").asText().equals(current.path("source_id").asText()))return "未知：设备实例已更换";
        if(previous.has("revision")&&current.path("state_revision").asLong()<previous.path("revision").asLong())return "未知：设备状态修订号倒退";
        return null;
    }
    private boolean accepts(SystemStateModel.Predicate predicate,JsonNode snapshot) {
        return predicate.states().contains(snapshot.path("state_id").asText())||predicate.outerStates().contains(snapshot.path("outer_state").asText());
    }
    private void update(ObjectNode evidence,JsonNode snapshot,long cursor,long now) {
        evidence.put("source",snapshot.path("source_id").asText()).put("revision",snapshot.path("state_revision").asLong())
            .put("state",snapshot.path("state_id").asText()).put("outerState",snapshot.path("outer_state").asText())
            .put("cursor",cursor).put("observedAt",now).put("eventEpoch",eventEpoch);
    }
    private Violation latch(String pid,ObjectNode state,String integrity,String reason) {
        state.put("integrity",integrity).put("reason",reason).put("invalidatedAt",System.currentTimeMillis());
        runtime().setVariable(pid,STATE,state.toString());return new Violation(reason);
    }
    private ObjectNode current(String pid) {
        Object raw=runtime().getVariable(pid,STATE);
        if(raw==null)throw new IllegalStateException("流程没有实验系统状态模型");
        try{return (ObjectNode)json.readTree(raw.toString());}catch(Exception ex){throw new IllegalStateException("实验系统状态记录损坏",ex);}
    }
    private void requireValid(JsonNode state) {
        if(!"valid".equals(state.path("integrity").asText()))throw new IllegalStateException(state.path("reason").asText("实验系统状态无效"));
    }
    public record Violation(String reason) {}
}
