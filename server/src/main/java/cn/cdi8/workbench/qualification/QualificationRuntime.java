package cn.cdi8.workbench.qualification;

import cn.cdi8.workbench.integration.tango.TangoGateway;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import java.util.function.Supplier;
import org.flowable.bpmn.model.*;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.delegate.*;
import org.flowable.engine.runtime.Execution;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Durable, scope-owned qualifications. All event observation and dispatch share this monitor. */
@Component("qualifications")
public class QualificationRuntime {
    public static final String REGISTRY = "qualificationRegistry";
    private final ObjectMapper json;
    private final TangoGateway gateway;
    private final ObjectProvider<RuntimeService> engine;
    private final Deque<Observation> observations = new ArrayDeque<>();
    private final Map<String,Long> readAt = new HashMap<>();
    private long sequence;
    private final String observerId = UUID.randomUUID().toString();
    private record Observation(long sequence, JsonNode payload) {}
    public record Violation(String scopeExecution, String message, String reason) {}
    public QualificationRuntime(ObjectMapper json, TangoGateway gateway, ObjectProvider<RuntimeService> engine) {
        this.json=json; this.gateway=gateway; this.engine=engine;
    }
    private RuntimeService runtime() { return engine.getObject(); }
    private ObjectNode registry(String pid) {
        Object raw=runtime().getVariable(pid,REGISTRY);
        try { return raw==null ? json.createObjectNode() : (ObjectNode)json.readTree(raw.toString()); }
        catch(Exception ex) { throw new IllegalStateException("资格记录无法读取",ex); }
    }
    private void save(String pid,ObjectNode registry) { runtime().setVariable(pid,REGISTRY,registry.toString()); }
    private static String text(JsonNode node,String key) { return node.path(key).asText(); }

    public synchronized void observe(TangoGateway.Notice notice) {
        observations.addLast(new Observation(++sequence,notice.payload().deepCopy()));
        // Overflow is treated as an evidence gap, never silently as success.
        while(observations.size()>16384) observations.removeFirst();
    }
    public synchronized void open(DelegateExecution e) {
        var scope=(SubProcess)e.getCurrentFlowElement();
        var policy=QualificationModel.elements(scope,"qualificationPolicy").get(0);
        ObjectNode root=registry(e.getProcessInstanceId()), record=root.putObject(e.getId());
        record.put("scope",scope.getId()).put("message",QualificationModel.attr(policy,"message"))
            .put("evidenceMaxAgeMs",Long.parseLong(QualificationModel.attr(policy,"evidenceMaxAgeMs")));
        ObjectNode definitions=record.putObject("definitions");record.putObject("claims");
        for(var q:QualificationModel.elements(scope,"qualification")) {
            String id=QualificationModel.required(q,"id"), collection=QualificationModel.required(q,"targets");
            JsonNode targets=json.valueToTree(e.getVariable(collection));
            if(!targets.isArray() || targets.isEmpty()) fail(e,"资格目标集合为空："+collection);
            ObjectNode definition=definitions.putObject(id);definition.set("targets",targets);
            ObjectNode accept=definition.putObject("accept");
            for(var a:q.getChildElements().get("accept")) {
                var predicate=accept.putObject(QualificationModel.required(a,"component"));
                predicate.put("states",QualificationModel.attr(a,"states")).put("outerStates",QualificationModel.attr(a,"outerStates"));
            }
            Set<String> ids=new HashSet<>();
            for(var target:targets) if(text(target,"id").isBlank() || !ids.add(text(target,"id")) || !accept.has(text(target,"component")))
                fail(e,"资格目标缺少唯一 id 或组件谓词："+target);
        }
        e.setVariableLocal("qualificationScopeExecution",e.getId());
        save(e.getProcessInstanceId(),root);
    }
    public synchronized void close(DelegateExecution e) {
        var root=registry(e.getProcessInstanceId());
        var record=root.remove(e.getId());
        if(record!=null) {
            runtime().setVariable(e.getProcessInstanceId(),"qualificationAudit",record.toString());
            save(e.getProcessInstanceId(),root);
        }
        readAt.keySet().removeIf(key->key.startsWith(e.getProcessInstanceId()+":"));
    }
    public void bind(DelegateExecution e) {
        var bindings=QualificationModel.elements(e.getCurrentFlowElement(),"require");
        var refs=json.createArrayNode();
        for(var binding:bindings) if("dispatch".equals(QualificationModel.attr(binding,"at")))
            refs.add(QualificationModel.required(binding,"qualificationRef"));
        if(!refs.isEmpty()) e.setVariableLocal("qualificationRequirements",refs.toString());
    }
    /** Called under this monitor after both command-result and fresh-state verification. */
    public synchronized void establish(DelegateExecution e,JsonNode snapshot) {
        var bindings=QualificationModel.elements(e.getCurrentFlowElement(),"establish");
        if(bindings.isEmpty()) return;
        String pid=e.getProcessInstanceId(), scopeId=Objects.toString(e.getVariable("qualificationScopeExecution"),"");
        ObjectNode root=registry(pid), scope=(ObjectNode)root.get(scopeId);
        if(scope==null) fail(e,"资格作用域不存在");
        @SuppressWarnings("unchecked") var target=(Map<String,String>)e.getVariable("target");
        String device=target.get("id"), component=target.get("component");
        for(var binding:bindings) {
            String ref=QualificationModel.required(binding,"qualificationRef");
            if(!accepts(scope.path("definitions").path(ref).path("accept").path(component),snapshot))
                fail(e,device+" 无法建立资格 "+ref+"："+text(snapshot,"state_id"));
            ObjectNode claim=((ObjectNode)scope.get("claims")).putObject(ref+"|"+device);
            claim.put("qualification",ref).put("device",device).put("component",component)
                .put("cursor",sequence).put("observerId",observerId).put("establishedBy",Objects.toString(e.getVariableLocal("commandId"),""));
            update(claim,snapshot);
            readAt.put(pid+":"+scopeId+":"+ref+"|"+device,System.currentTimeMillis());
        }
        save(pid,root);
    }
    public synchronized void check(DelegateExecution e) {
        String pid=e.getProcessInstanceId();
        Violation issue=inspect(pid,true);
        if(issue!=null) fail(e,issue.reason());
        ObjectNode root=registry(pid);
        String scopeId=Objects.toString(e.getVariable("qualificationScopeExecution"),"");
        for(var binding:QualificationModel.elements(e.getCurrentFlowElement(),"require")) {
            String reason=missing(root.path(scopeId),QualificationModel.required(binding,"qualificationRef"));
            if(reason!=null) fail(e,reason);
        }
    }
    public synchronized JsonNode dispatch(Execution e,Supplier<JsonNode> send) {
        var issue=inspect(e.getProcessInstanceId(),true);
        if(issue!=null) { interrupt(e.getProcessInstanceId(),issue); return null; }
        Object raw=runtime().getVariableLocal(e.getId(),"qualificationRequirements");
        if(raw!=null) {
            String scopeId=Objects.toString(runtime().getVariable(e.getId(),"qualificationScopeExecution"),"");
            ObjectNode root=registry(e.getProcessInstanceId());
            try {
                for(var ref:json.readTree(raw.toString())) {
                    String reason=missing(root.path(scopeId),ref.asText());
                    if(reason!=null) {
                        var scope=(ObjectNode)root.get(scopeId);
                        if(scope==null) throw new IllegalStateException("资格作用域不存在");
                        scope.put("violation",reason);save(e.getProcessInstanceId(),root);
                        interrupt(e.getProcessInstanceId(),violation(scopeId,scope,reason));return null;
                    }
                }
            } catch(java.io.IOException ex) { throw new IllegalStateException(ex); }
        }
        return send.get();
    }
    private String missing(JsonNode scope,String ref) {
        JsonNode definition=scope.path("definitions").path(ref);
        if(!definition.path("targets").isArray() || definition.path("targets").isEmpty()) return "资格定义或目标集合缺失："+ref;
        for(var target:definition.path("targets")) {
            String key=ref+"|"+text(target,"id");
            if(!scope.path("claims").has(key)) return "尚未建立资格："+key;
        }
        return null;
    }
    public synchronized Violation inspect(String pid,boolean forceRead) {
        ObjectNode root=registry(pid);
        for(var entry:iterable(root.fields())) {
            String scopeId=entry.getKey();ObjectNode scope=(ObjectNode)entry.getValue();
            if(scope.has("violation")) return violation(scopeId,scope,text(scope,"violation"));
            for(var claimEntry:iterable(scope.path("claims").fields())) {
                ObjectNode claim=(ObjectNode)claimEntry.getValue();
                String reason=inspectClaim(pid,scopeId,scope,claimEntry.getKey(),claim,forceRead);
                if(reason!=null) {
                    scope.put("violation",reason);save(pid,root);
                    return violation(scopeId,scope,reason);
                }
            }
        }
        if(forceRead) for(var entry:iterable(root.fields())) {
            JsonNode scope=entry.getValue();
            for(var claim:iterable(scope.path("claims").fields())) {
                String readKey=pid+":"+entry.getKey()+":"+claim.getKey();
                if(System.currentTimeMillis()-readAt.getOrDefault(readKey,0L)>scope.path("evidenceMaxAgeMs").asLong()) {
                    String reason="资格证据已过期："+claim.getKey();
                    ((ObjectNode)scope).put("violation",reason);save(pid,root);
                    return violation(entry.getKey(),scope,reason);
                }
            }
        }
        if(!root.isEmpty()) save(pid,root);
        return null;
    }
    private String inspectClaim(String pid,String scopeId,JsonNode scope,String key,ObjectNode claim,boolean forceRead) {
        String device=text(claim,"device"), ref=text(claim,"qualification");
        JsonNode predicate=scope.path("definitions").path(ref).path("accept").path(text(claim,"component"));
        String prefix=device+" 资格 "+ref+" 失效：";
        if(!gateway.connected()) return prefix+"Tango 事件通道断开，当前资格未知";
        boolean recovering=!observerId.equals(text(claim,"observerId"));
        long recoveredRevision=claim.path("revision").asLong();
        long cursor=recovering ? 0 : claim.path("cursor").asLong();
        if(!observations.isEmpty() && cursor<observations.getFirst().sequence()-1) return prefix+"事件证据溢出";
        for(var observation:observations) if(observation.sequence()>cursor) {
            JsonNode event=observation.payload();
            if("connection_lost".equals(text(event,"type"))) return prefix+"事件通道曾断开";
            if(!device.equals(text(event,"device_id"))) continue;
            if(!event.path("connected").asBoolean()) return prefix+"设备断连";
            JsonNode snapshot=event.path("snapshot");
            // Only notifications can be obsolete; a regressing authoritative read is a violation.
            if(sameSource(claim,snapshot) && snapshot.path("state_revision").asLong(-1)<claim.path("revision").asLong()) continue;
            String problem=evidenceProblem(claim,snapshot);
            if(recovering && snapshot.path("state_revision").asLong()!=recoveredRevision) return prefix+"控制端恢复期间资格证据不连续";
            if(problem!=null || !accepts(predicate,snapshot)) return prefix+(problem==null?text(snapshot,"state_id"):problem);
            update(claim,snapshot);
        }
        String readKey=pid+":"+scopeId+":"+key;
        long age=scope.path("evidenceMaxAgeMs").asLong(), now=System.currentTimeMillis();
        if(forceRead || now-readAt.getOrDefault(readKey,0L)>=age/2) {
            try {
                JsonNode snapshot=gateway.snapshot(device);
                String problem=evidenceProblem(claim,snapshot);
                if(recovering && snapshot.path("state_revision").asLong()!=recoveredRevision) return prefix+"控制端恢复期间资格证据不连续";
                if(problem!=null || !accepts(predicate,snapshot)) return prefix+(problem==null?text(snapshot,"state_id"):problem);
                update(claim,snapshot);readAt.put(readKey,System.currentTimeMillis());
            } catch(Exception ex) { return prefix+"权威状态读取失败："+ex.getMessage(); }
        }
        claim.put("cursor",sequence).put("observerId",observerId);
        return null;
    }
    private String evidenceProblem(JsonNode claim,JsonNode snapshot) {
        if(snapshot==null || !snapshot.path("state_revision").isIntegralNumber() || snapshot.path("state_revision").asLong(-1)<0
                || text(snapshot,"source_id").isBlank()) return "缺少状态版本或设备实例标识";
        if(!sameSource(claim,snapshot)) return "设备实例已更换或重启";
        if(snapshot.path("state_revision").asLong()<claim.path("revision").asLong()) return "状态版本倒退";
        return null;
    }
    private boolean sameSource(JsonNode claim,JsonNode snapshot) { return text(claim,"source").equals(text(snapshot,"source_id")); }
    private void update(ObjectNode claim,JsonNode snapshot) {
        claim.put("revision",snapshot.path("state_revision").asLong()).put("source",text(snapshot,"source_id"))
            .put("state",text(snapshot,"state_id")).put("outerState",text(snapshot,"outer_state"));
    }
    private boolean accepts(JsonNode predicate,JsonNode snapshot) {
        return snapshot!=null && snapshot.path("state_revision").isIntegralNumber() && !text(snapshot,"source_id").isBlank() && !predicate.isMissingNode()
            && (contains(text(predicate,"states"),text(snapshot,"state_id")) || contains(text(predicate,"outerStates"),text(snapshot,"outer_state")));
    }
    private boolean contains(String choices,String value) { return !value.isBlank() && Arrays.asList(choices.split("\\s+" )).contains(value); }
    private Violation violation(String scopeId,JsonNode scope,String reason) { return new Violation(scopeId,text(scope,"message"),reason); }
    public synchronized void interrupt(String pid,Violation issue) {
        runtime().setVariable(pid,"failureReason",issue.reason());
        var subscriptions=runtime().createEventSubscriptionQuery().processInstanceId(pid).eventName(issue.message()).list();
        var subscription=subscriptions.stream().filter(s -> belongs(s.getExecutionId(),issue.scopeExecution())).findFirst()
            .orElseThrow(()->new IllegalStateException("资格作用域消息订阅不存在："+issue.message()));
        runtime().messageEventReceived(issue.message(),subscription.getExecutionId());
    }
    private boolean belongs(String executionId,String scopeId) {
        while(executionId!=null) {
            if(scopeId.equals(executionId)) return true;
            var execution=runtime().createExecutionQuery().executionId(executionId).singleResult();
            executionId=execution==null?null:execution.getParentId();
        }
        return false;
    }
    private void fail(DelegateExecution e,String reason) {
        e.setVariable("failureReason",reason);throw new BpmnError("DEVICE_ERROR",reason);
    }
    private static <T> Iterable<T> iterable(Iterator<T> iterator) { return ()->iterator; }
}
