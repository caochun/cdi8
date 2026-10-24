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
        state.put("anomalyStatus","clear").putArray("incidents").addAll(json.createArrayNode());
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
        String anomaly=captureObserved(execution.getProcessInstanceId(),definition,state);
        if(anomaly!=null) {
            String integrity="evidenceUnknown".equals(anomaly)?"unknown":"violated";
            Violation issue=latch(execution.getProcessInstanceId(),state,integrity,"系统异常已锁存："+anomaly);
            throw new IllegalStateException(issue.reason());
        }
        requireValid(state);
        if(!transition.from().equals(state.path("state").asText()))
            throw new IllegalStateException("系统状态转换来源不匹配：要求 "+transition.from()+"，当前为 "+state.path("state").asText());
        Map<String,JsonNode> snapshots=new HashMap<>();
        String sourceStatus=checkState(execution.getProcessInstanceId(),definition,
            definition.state(transition.from()).predicates(),state,true,true,snapshots);
        if(sourceStatus!=null) {
            Violation issue=latch(execution.getProcessInstanceId(),state,
                sourceStatus.startsWith("未知：")?"unknown":"violated",
                sourceStatus.replaceFirst("^(未知：|违反：)",""));
            throw new IllegalStateException(issue.reason());
        }
        if(!transition.blockedReason().isBlank()) {
            runtime().setVariable(execution.getProcessInstanceId(),STATE,state.toString());
            throw new IllegalStateException("系统状态转换尚未放行："+transition.blockedReason());
        }
        // Target conditions start at this boundary. Intermediate observations made
        // while the source state was active must not be judged by target conditions.
        String targetStatus=checkState(execution.getProcessInstanceId(),definition,
            definition.state(transition.to()).allPredicates(),state,true,false,snapshots);
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
        if(hasPendingCommandOutcome(execution.getProcessInstanceId()))return rejected(execution,"存在结果未知的设备命令；核对原命令前禁止下发新动作");
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
    public synchronized void commandOutcomeUnknown(String pid,String device,String commandId,String action,String reason) {
        if(!runtime().hasVariable(pid,STATE))return;
        ObjectNode state=current(pid);var process=runtime().createProcessInstanceQuery().processInstanceId(pid).singleResult();
        if(process==null)return;
        SystemStateModel definition=model(process.getProcessDefinitionId(),state.path("model").asText());
        var declaration=definition.anomaly("commandOutcomeUnknown");ArrayNode incidents=(ArrayNode)state.withArray("incidents");
        ObjectNode incident=null;
        for(JsonNode existing:incidents)if("commandOutcomeUnknown".equals(existing.path("type").asText())
                &&commandId.equals(existing.path("commandId").asText()))incident=(ObjectNode)existing;
        if(incident==null) {
            incident=json.createObjectNode().put("id","commandOutcomeUnknown:"+commandId).put("type",declaration.id())
                .put("label",declaration.label()).put("category",declaration.category()).put("status","awaitingReconciliation")
                .put("device",device).put("component",component(pid,device)).put("stage",state.path("state").asText())
                .put("commandId",commandId).put("action",action).put("detectedAt",System.currentTimeMillis());
            incidents.add(incident);
        }
        incident.put("reason",reason).put("lastSeenAt",System.currentTimeMillis());state.put("anomalyStatus","latched");
        runtime().setVariable(pid,STATE,state.toString());
    }
    public synchronized void commandOutcomeResolved(String pid,String commandId,String outcome) {
        if(!runtime().hasVariable(pid,STATE))return;
        ObjectNode state=current(pid);boolean changed=false;
        for(JsonNode item:state.path("incidents"))if(item instanceof ObjectNode incident
                &&"commandOutcomeUnknown".equals(incident.path("type").asText())
                &&commandId.equals(incident.path("commandId").asText())
                &&"awaitingReconciliation".equals(incident.path("status").asText())) {
            incident.put("status","reconciled").put("observedOutcome",outcome).put("reconciledAt",System.currentTimeMillis());changed=true;
        }
        if(changed)runtime().setVariable(pid,STATE,state.toString());
    }
    private boolean hasPendingCommandOutcome(String pid) {
        if(!runtime().hasVariable(pid,STATE))return false;
        try {
            JsonNode state=json.readTree(runtime().getVariable(pid,STATE).toString());
            for(JsonNode incident:state.path("incidents"))if("commandOutcomeUnknown".equals(incident.path("type").asText())
                    &&"awaitingReconciliation".equals(incident.path("status").asText()))return true;
        } catch(Exception ignored) { return true; }
        return false;
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
        String modelId=state.path("model").asText();
        var process=runtime().createProcessInstanceQuery().processInstanceId(pid).singleResult();
        if(process==null)return null;
        SystemStateModel model;
        try { model=model(process.getProcessDefinitionId(),modelId); }
        catch(Exception ex){return latch(pid,state,"unknown","系统状态声明无法恢复："+ex.getMessage());}
        String anomaly=captureObserved(pid,model,state);
        if(anomaly!=null)return latch(pid,state,"evidenceUnknown".equals(anomaly)?"unknown":"violated","系统异常已锁存："+anomaly);
        if(!state.path("integrity").asText().equals("valid"))return new Violation(state.path("reason").asText("实验系统状态已失效"));
        if(!gateway.connected())return latch(pid,state,"unknown","Tango 连接已断开，无法确认实验系统状态");
        JsonNode evidence=state.path("evidence");
        if(evidence.has("eventEpoch")&&!eventEpoch.equals(evidence.path("eventEpoch").asText())
                &&evidence.fields().hasNext())return latch(pid,state,"unknown","控制服务重启后系统状态事件连续性无法确认");
        String status=checkState(pid,model,model.state(state.path("state").asText()).predicates(),state,forceRead,true,new HashMap<>());
        if(status!=null)return latch(pid,state,status.startsWith("未知：")?"unknown":"violated",status.replaceFirst("^(未知：|违反：)",""));
        runtime().setVariable(pid,STATE,state.toString());
        return null;
    }
    private String checkState(String pid,SystemStateModel model,List<SystemStateModel.Predicate> predicates,
                              ObjectNode state,boolean forceRead,boolean replayEvents,Map<String,JsonNode> snapshots) {
        ObjectNode evidence=(ObjectNode)state.path("evidence");
        long initialCursor=evidence.path("eventCursor").asLong(0);
        if(!gateway.connected())return "未知：Tango 连接已断开，无法确认实验系统状态";
        if(!eventEpoch.equals(evidence.path("eventEpoch").asText()))return "未知：控制服务重启后系统状态事件连续性无法确认";
        if(initialCursor<lostBefore)return "未知：系统状态事件证据出现缺口";
        for(var observation:observations)if(observation.sequence()>initialCursor
                &&"connection_lost".equals(observation.value().path("type").asText()))return "未知：Tango 事件通道曾断开";
        Map<String,List<SystemStateModel.Predicate>> byDevice=new LinkedHashMap<>();
        for(var predicate:predicates) {
            Object raw=runtime().getVariable(pid,predicate.collection());
            if(!(raw instanceof List<?> targets)||targets.isEmpty())return "违反：系统状态目标集合为空："+predicate.collection();
            boolean matched=false;
            for(Object item:targets) {
                if(!(item instanceof Map<?,?> targetDevice))return "违反：系统状态目标格式无效";
                String device=Objects.toString(targetDevice.get("id"),""),component=Objects.toString(targetDevice.get("component"),"");
                if(device.isBlank()||component.isBlank())return "违反：系统状态目标缺少设备编号或组件类型";
                if(!predicate.matchesComponent(component))continue;
                matched=true;
                byDevice.computeIfAbsent(device,key->new ArrayList<>()).add(predicate);
            }
            if(!matched)return "违反：系统状态条件未匹配任何 "+predicate.component()+" 设备："+predicate.collection();
        }
        long now=System.currentTimeMillis();
        for(var entry:byDevice.entrySet()) {
            String device=entry.getKey();var conditions=entry.getValue();
            ObjectNode prior=evidence.has(device)?(ObjectNode)evidence.get(device):json.createObjectNode();
            if(!prior.has("cursor"))prior.put("cursor",initialCursor);
            evidence.set(device,prior);
            String reason=replayEvents?observeEvents(pid,model,state,device,conditions,prior):null;
            if(reason!=null)return reason;
            if(forceRead||now-prior.path("observedAt").asLong(0)>=model.evidenceMaxAgeMs()/2) {
                try {
                    JsonNode snapshot=snapshots.computeIfAbsent(device,key->gateway.snapshot(key).deepCopy());
                    reason=evidenceProblem(prior,snapshot);
                    if(reason!=null) {
                        recordIfDeclared(state,model,"evidenceUnknown",device,component(pid,device),snapshot,reason,sequence);
                        return reason;
                    }
                    String anomaly=classifySnapshot(model,snapshot);
                    if(anomaly!=null)recordIncident(state,model,anomaly,device,component(pid,device),snapshot,
                        model.anomaly(anomaly).label(),sequence);
                    if(conditions.stream().anyMatch(predicate->!accepts(predicate,snapshot))) {
                        recordIfDeclared(state,model,"stateConditionLost",device,component(pid,device),snapshot,
                            "当前设备状态不满足系统阶段条件",sequence);
                        return "违反："+device+" 当前状态不满足系统状态条件："+snapshot.path("state_id").asText();
                    }
                    update(prior,snapshot,sequence,now);
                }catch(Exception ex){return "未知：读取设备 "+device+" 状态失败："+ex.getMessage();}
            }
        }
        evidence.put("eventCursor",sequence);
        return null;
    }
    private String observeEvents(String pid,SystemStateModel model,ObjectNode state,String device,
                                 List<SystemStateModel.Predicate> predicates,ObjectNode evidence) {
        long cursor=evidence.path("cursor").asLong(0);
        if(cursor<lostBefore)return "未知：系统状态事件证据出现缺口";
        for(Observation observation:observations)if(observation.sequence()>cursor) {
            JsonNode event=observation.value();
            if("connection_lost".equals(event.path("type").asText()))return "未知：Tango 事件通道曾断开";
            if(!device.equals(event.path("device_id").asText()))continue;
            if(!event.path("connected").asBoolean(true))return "未知：设备断连："+device;
            JsonNode snapshot=event.path("snapshot");String reason=evidenceProblem(evidence,snapshot);
            if(reason!=null) {
                recordIfDeclared(state,model,"evidenceUnknown",device,component(pid,device),snapshot,reason,observation.sequence());
                return reason;
            }
            if(predicates.stream().anyMatch(predicate->!accepts(predicate,snapshot))) {
                recordIfDeclared(state,model,"stateConditionLost",device,component(pid,device),snapshot,
                    "设备事件显示系统阶段条件失效",observation.sequence());
                return "违反：设备事件显示系统状态条件失效："+device+" / "+snapshot.path("state_id").asText();
            }
            update(evidence,snapshot,observation.sequence(),System.currentTimeMillis());
        }
        evidence.put("cursor",sequence);
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
    private String captureObserved(String pid,SystemStateModel model,ObjectNode state) {
        long cursor=state.path("evidence").path("eventCursor").asLong(0);
        String primary=null;
        for(Observation observation:observations)if(observation.sequence()>cursor) {
            JsonNode event=observation.value();String device=event.path("device_id").asText("");
            if(!device.isBlank()&&!participant(pid,device))continue;
            JsonNode snapshot=event.path("snapshot");String anomaly=null,reason="";
            if("connection_lost".equals(event.path("type").asText())) {
                anomaly="evidenceUnknown";reason="Tango 事件通道断开";
            } else if(event.has("connected")&&!event.path("connected").asBoolean()) {
                anomaly="evidenceUnknown";reason="设备连接中断";
            } else if(!device.isBlank()&&snapshot.isObject()) {
                anomaly=classifySnapshot(model,snapshot);
                if(anomaly!=null)reason=model.anomaly(anomaly).label();
            }
            if(anomaly==null)continue;
            String component=component(pid,device);
            if(declaredAnomaly(model,anomaly))
                recordIncident(state,model,anomaly,device,component,snapshot,reason,observation.sequence());
            int candidatePriority=priority(model,anomaly),primaryPriority=primary==null?Integer.MAX_VALUE:priority(model,primary);
            if(primary==null||candidatePriority<primaryPriority)primary=anomaly;
            ((ObjectNode)state.path("evidence")).put("eventCursor",observation.sequence());
        }
        if(primary!=null)runtime().setVariable(pid,STATE,state.toString());
        return primary;
    }
    private boolean participant(String pid,String id) {
        Object raw=runtime().getVariable(pid,"allComponents");
        if(!(raw instanceof List<?> values))return true;
        return values.stream().anyMatch(item->item instanceof Map<?,?> device&&id.equals(Objects.toString(device.get("id"),"")));
    }
    private String component(String pid,String id) {
        if(id.isBlank())return "system";
        Object raw=runtime().getVariable(pid,"allComponents");
        if(raw instanceof List<?> values)for(Object item:values)if(item instanceof Map<?,?> device&&id.equals(Objects.toString(device.get("id"),"")))
            return Objects.toString(device.get("component"),"unknown");
        return "unknown";
    }
    private int priority(String category) {
        return switch(category) { case "safetyInterlock" -> 0;case "deviceFault" -> 1;case "stateConditionLost" -> 2;default -> 3; };
    }
    private int priority(SystemStateModel model,String id) {
        for(var anomaly:model.anomalies())if(anomaly.id().equals(id))return priority(anomaly.category());
        return 3;
    }
    private boolean declaredAnomaly(SystemStateModel model,String id) {
        for(var anomaly:model.anomalies())if(anomaly.id().equals(id))return true;
        return false;
    }
    private void recordIfDeclared(ObjectNode state,SystemStateModel model,String type,String device,String component,
                                  JsonNode snapshot,String reason,long observation) {
        if(declaredAnomaly(model,type))recordIncident(state,model,type,device,component,snapshot,reason,observation);
    }
    private String classifySnapshot(SystemStateModel model,JsonNode snapshot) {
        String leaf=snapshot.path("state_id").asText(),outer=snapshot.path("outer_state").asText();
        for(var declaration:model.anomalies())if(declaration.states().contains(leaf)||declaration.outerStates().contains(outer))return declaration.id();
        if(snapshot.path("faults").isArray()&&!snapshot.path("faults").isEmpty()
                &&model.anomalies().stream().anyMatch(item->item.id().equals("deviceFault")))return "deviceFault";
        return null;
    }
    private String recordIncident(ObjectNode state,SystemStateModel model,String type,String device,String component,
                                  JsonNode snapshot,String reason,long observation) {
        ArrayNode incidents=(ArrayNode)state.withArray("incidents");
        String incidentId=type+":"+(device.isBlank()?"system":device)+":"+snapshot.path("source_id").asText()+":"+
            snapshot.path("state_revision").asText(Long.toString(observation));
        for(JsonNode existing:incidents)if(incidentId.equals(existing.path("id").asText()))return incidentId;
        var incident=json.createObjectNode().put("id",incidentId).put("type",type)
            .put("label",model.anomaly(type).label()).put("category",model.anomaly(type).category())
            .put("status","latched").put("device",device).put("component",component)
            .put("stage",state.path("state").asText()).put("reason",reason).put("detectedAt",System.currentTimeMillis());
        if(snapshot!=null&&snapshot.isObject()) {
            incident.put("sourceId",snapshot.path("source_id").asText()).put("revision",snapshot.path("state_revision").asLong())
                .put("stateId",snapshot.path("state_id").asText()).put("outerState",snapshot.path("outer_state").asText());
            if(snapshot.hasNonNull("fault_origin"))incident.set("faultOrigin",snapshot.get("fault_origin").deepCopy());
            if(snapshot.path("faults").isArray())incident.set("faults",snapshot.path("faults").deepCopy());
        }
        incidents.add(incident);state.put("anomalyStatus","latched");
        return incidentId;
    }
    private Violation latch(String pid,ObjectNode state,String integrity,String reason) {
        state.put("integrity",integrity).put("reason",reason).put("invalidatedAt",System.currentTimeMillis());
        try {
            var process=runtime().createProcessInstanceQuery().processInstanceId(pid).singleResult();
            if(process!=null) {
                var definition=model(process.getProcessDefinitionId(),state.path("model").asText());
                String type="unknown".equals(integrity)?"evidenceUnknown":"stateConditionLost";
                boolean existing=false;
                for(JsonNode incident:state.path("incidents"))if(type.equals(incident.path("type").asText()))existing=true;
                if(!existing&&definition.anomalies().stream().anyMatch(item->item.id().equals(type)))
                    recordIncident(state,definition,type,"","system",null,reason,sequence);
            }
        } catch(RuntimeException ignored) { }
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
