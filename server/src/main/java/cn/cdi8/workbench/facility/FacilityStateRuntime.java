package cn.cdi8.workbench.facility;

import cn.cdi8.workbench.integration.tango.TangoGateway;
import cn.cdi8.workbench.systemdefinition.SystemDefinitionModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.context.event.EventListener;
import org.springframework.context.ApplicationEventPublisher;

/** Live projection of facility facts. It is intentionally independent from BPMN run state. */
@Component("facilityStates")
public final class FacilityStateRuntime {
    public record Changed(long sequence) {}
    private final ObjectMapper json;
    private final TangoGateway gateway;
    private final ApplicationEventPublisher events;
    private SystemDefinitionModel definition;
    private FacilityBindingModel binding;
    private FacilityStateProjectionModel projection;
    private final Map<String, JsonNode> snapshots = new ConcurrentHashMap<>();
    private final Map<String, String> componentBindings = new ConcurrentHashMap<>();
    private final Map<String,Long> observedAt=new HashMap<>();
    private final Map<String,String> invalidObservations=new HashMap<>();
    private long sequence;
    private volatile ObjectNode current;
    private FacilityComponentModels components;

    public FacilityStateRuntime(ObjectMapper json, TangoGateway gateway, ApplicationEventPublisher events) {
        this.json=json; this.gateway=gateway; this.events=events;
        try {
            String definitionXml=resource("system-models/gxlf-facility.system-definition.xml");
            String bindingXml=resource("system-models/gxlf-facility-binding.xml");
            String projectionXml=resource("system-models/gxlf-facility-state-projection.xml");
            this.definition=SystemDefinitionModel.parse(definitionXml);
            this.binding=FacilityBindingModel.parse(bindingXml,this.definition);
            this.projection=FacilityStateProjectionModel.parse(projectionXml);
            this.components=FacilityComponentModels.bundled(this.definition);
            this.projection.validateComponents(this.definition,this.components);
            if(!binding.id().equals(projection.bindingRef()))throw new IllegalArgumentException("设施投影 bindingRef 不一致");
        } catch(Exception ex) { throw new IllegalStateException("无法加载设施状态投影模型："+ex.getMessage(),ex); }
        this.current=json.createObjectNode().put("model",projection.id()).put("definition",projection.definitionRef())
            .put("binding",binding.id()).put("integrity","unknown").put("connected",false)
            .put("sequence",0).put("reason","尚未取得 Tango 设施观测").put("changedAt",System.currentTimeMillis());
        current.putObject("facts"); current.putObject("guards"); current.putObject("observations");current.putObject("deviceFacts");
    }

    public synchronized ObjectNode current() { return current.deepCopy(); }
    /** Refresh on reconciliation; re-evaluate age even when checking the latest event snapshot. */
    public synchronized ObjectNode assessment(boolean refresh) {
        if(refresh)refresh();else evaluate();
        return current.deepCopy();
    }
    public synchronized SystemDefinitionModel definition() { return definition; }
    public synchronized FacilityStateProjectionModel projection() { return projection; }
    public synchronized FacilityBindingModel binding() { return binding; }
    public synchronized FacilityComponentModels components() { return components; }

    /** The same XML is archived in the deployment and evaluated here. */
    public synchronized void activate(String definitionXml,String bindingXml,String projectionXml) {
        activate(definitionXml,bindingXml,projectionXml,FacilityComponentModels.bundled(SystemDefinitionModel.parse(definitionXml)));
    }
    public synchronized void activate(String definitionXml,String bindingXml,String projectionXml,FacilityComponentModels nextComponents) {
        var nextDefinition=SystemDefinitionModel.parse(definitionXml);
        var nextBinding=FacilityBindingModel.parse(bindingXml,nextDefinition);
        var nextProjection=FacilityStateProjectionModel.parse(projectionXml);
        nextProjection.validateComponents(nextDefinition,nextComponents);
        if(!nextDefinition.id().equals(nextBinding.definitionRef()) || !nextDefinition.id().equals(nextProjection.definitionRef())
            || !nextBinding.id().equals(nextProjection.bindingRef()))throw new IllegalArgumentException("设施模型引用不一致");
        definition=nextDefinition;binding=nextBinding;projection=nextProjection;components=nextComponents;
        snapshots.clear();componentBindings.clear();observedAt.clear();invalidObservations.clear();
        current.put("model",projection.id()).put("definition",definition.id()).put("binding",binding.id());
        refresh();
    }

    private void accept(String id,JsonNode snapshot) {
        JsonNode previous=snapshots.get(id);
        if(snapshot.path("source_id").asText().isBlank() || !snapshot.path("state_revision").isIntegralNumber()) {
            invalidObservations.put(id,"设备观测缺少 source_id/state_revision");return;
        }
        if(previous!=null && !previous.path("source_id").asText().equals(snapshot.path("source_id").asText())) {
            invalidObservations.put(id,"设备来源变化，需重新建立运行环境");return;
        }
        if(previous!=null && snapshot.path("state_revision").asLong()<previous.path("state_revision").asLong()) {
            invalidObservations.put(id,"设备状态修订号倒退");return;
        }
        snapshots.put(id,snapshot.deepCopy());observedAt.put(id,System.currentTimeMillis());
    }

    /** BPMN gate: only a current, valid facility guard can release a task. */
    public synchronized void requireGuard(String guard) {
        refresh();
        JsonNode value=current.path("guards").path(guard);
        if(!"valid".equals(value.path("status").asText()))
            throw new IllegalStateException("设施放行条件不满足："+guard+" / "+value.path("reason").asText("未知"));
    }

    @EventListener
    public synchronized void observe(TangoGateway.Notice notice) {
        JsonNode event=notice.payload();
        sequence++;
        if("connection_lost".equals(event.path("type").asText())) {
            observedAt.clear();
            current.put("connected",false).put("integrity","unknown").put("reason",event.path("reason").asText("Tango 事件通道断开"));
            current.put("sequence",sequence).put("changedAt",System.currentTimeMillis());
            evaluate();
            return;
        }
        if(event.has("device_id")) {
            String id=event.path("device_id").asText();
            if("state_revision_rollback".equals(event.path("type").asText()))invalidObservations.put(id,"设备状态修订号倒退");
            if(event.path("connected").asBoolean(false)&&event.has("snapshot"))accept(id,event.path("snapshot"));
            else observedAt.remove(id);
        }
        if("ready".equals(event.path("type").asText()))current.put("connected",true);
        evaluate();
    }

    @Scheduled(fixedDelay=2000)
    public synchronized void refresh() {
        if(!gateway.connected()) {
            observedAt.clear();
            current.put("connected",false).put("integrity","unknown").put("reason","Tango 连接不可用");
            current.put("sequence",++sequence).put("changedAt",System.currentTimeMillis());
            evaluate(); return;
        }
        try {
            JsonNode catalog=gateway.catalog();
            Set<String> seen = new HashSet<>();
            if(catalog.isArray())for(JsonNode item:catalog) {
                String id=item.path("id").asText(), runtimeKey=item.path("component").asText();
                if(!id.isBlank()) {
                    seen.add(id);
                    if("context".equals(runtimeKey)||binding.knowsRuntimeKey(runtimeKey))componentBindings.put(id,runtimeKey);
                    else componentBindings.remove(id);
                    if(!item.path("connected").asBoolean()) {observedAt.remove(id);continue;}
                    try { accept(id,gateway.snapshot(id)); }
                    catch(Exception ex) { observedAt.remove(id); }
                }
            }
            componentBindings.keySet().retainAll(seen);
            current.put("connected",true).put("reason","");
        } catch(Exception ex) {
            observedAt.clear();
            current.put("connected",false).put("integrity","unknown").put("reason","读取设施目录失败："+ex.getMessage());
        }
        current.put("sequence",++sequence).put("changedAt",System.currentTimeMillis());
        evaluate();
    }

    private void evaluate() {
        ObjectNode facts=json.createObjectNode();
        Map<String,Status> statuses=new LinkedHashMap<>();
        for(var fact:projection.evaluationOrder()) {
            Status result=evaluateCondition(fact.condition(),statuses);
            statuses.put(fact.id(),result);
            facts.putObject(fact.id()).put("status",result.status).put("reason",result.reason).put("label",fact.label());
        }
        ObjectNode guards=json.createObjectNode();
        for(var guard:projection.guards()) {
            Status result=evaluateCondition(guard.condition(),statuses);
            guards.putObject(guard.id()).put("status",result.status).put("reason",result.reason).put("label",guard.label());
        }
        String integrity="valid", reason="";
        for(Status result:statuses.values()) {
            if("unknown".equals(result.status)){integrity="unknown";reason=result.reason;break;}
            if("violated".equals(result.status)){integrity="violated";reason=result.reason;}
        }
        ObjectNode deviceFacts=json.createObjectNode();
        for(String device:componentBindings.keySet()) {
            ObjectNode values=deviceFacts.putObject(device);
            for(var fact:projection.deviceFacts())if(fact.component().equals(componentOf(device))) {
                var snapshot=snapshots.get(device);
                Status status=deviceStatus(device);
                if("valid".equals(status.status)&&!matches(snapshot,fact.states(),fact.outerStates()))
                    status=Status.violated("设备状态不满足："+device+" / "+snapshot.path("state_id").asText());
                ObjectNode value=values.putObject(fact.id()).put("status",status.status).put("reason",status.reason)
                    .put("label",fact.label()).put("component",fact.component());
                if(snapshot!=null)value.put("source_id",snapshot.path("source_id").asText()).put("state_revision",snapshot.path("state_revision").asLong())
                    .put("state",snapshot.path("state_id").asText());
            }
        }
        boolean changed=!facts.equals(current.path("facts")) || !guards.equals(current.path("guards")) || !deviceFacts.equals(current.path("deviceFacts"));
        if(changed)sequence++;
        current.put("sequence",sequence);
        current.set("facts",facts);current.set("guards",guards);
        current.set("deviceFacts",deviceFacts);
        current.put("integrity",integrity).put("reason",reason).put("connected",gateway.connected());
        ObjectNode observations=(ObjectNode)current.with("observations");observations.removeAll();
        for(var entry:snapshots.entrySet())observations.putObject(entry.getKey())
            .put("observed",observedAt.containsKey(entry.getKey())).put("observedAt",observedAt.getOrDefault(entry.getKey(),0L))
            .put("source_id",entry.getValue().path("source_id").asText()).put("state_revision",entry.getValue().path("state_revision").asLong())
            .put("reason",invalidObservations.getOrDefault(entry.getKey(),""));
        // Listeners only schedule reconciliation; never call Flowable while holding this monitor.
        if(changed)events.publishEvent(new Changed(sequence));
    }

    private Status evaluateCondition(FacilityStateProjectionModel.Condition condition,Map<String,Status> statuses) {
        return switch(condition.kind()) {
            case "factPredicate" -> statuses.getOrDefault(condition.ref(),Status.unknown("事实尚未计算："+condition.ref()));
            case "devicePredicate" -> deviceRequirement(Set.of(condition.ref()),condition.states(),condition.outerStates(),false);
            case "observationPredicate" -> observationStatus(condition.ref(),condition.expected());
            case "all", "any" -> {
                boolean any="any".equals(condition.kind());
                Status result=any?Status.violated("任一条件均未满足"):Status.ok();
                for(var term:condition.terms()) {
                    Status value=evaluateCondition(term,statuses);
                    result=any?either(result,value):combine(result,value);
                }
                yield result;
            }
            default -> throw new IllegalStateException("不支持的条件："+condition.kind());
        };
    }

    /** Quantifies the participating runtime devices bound to the declared instance set. */
    private Status deviceRequirement(Set<String> sets,String states,String outerStates,boolean connectivityOnly) {
        if(!gateway.connected())return Status.unknown("Tango 连接不可用");
        Status result=Status.ok();
        for(String set:sets) {
            var selected=componentBindings.keySet().stream().filter(id->set.equals(binding.instanceSetForRuntimeKey(componentBindings.get(id)))).toList();
            if(selected.isEmpty()) {result=combine(result,Status.unknown("缺少设施设备观测："+set));continue;}
            for(String id:selected) {
                Status item=deviceStatus(id);
                if("valid".equals(item.status)&&!connectivityOnly&&!matches(snapshots.get(id),states,outerStates))
                    item=Status.violated("设备状态不满足："+id+" / "+snapshots.get(id).path("state_id").asText());
                result=combine(result,item);
            }
        }
        return result;
    }
    private Status observationStatus(String id,String expected) {
        var observation=projection.observation(id);
        if("external".equals(observation.adapter()))return Status.unknown("外部观测尚未绑定："+id);
        var endpoint=definition.endpoint(observation.source(),true);
        var declared=definition.endpointInstanceSets(endpoint);
        // Connectivity describes the current deployed device catalog, including small prototype environments.
        if("device-connectivity".equals(observation.adapter())) {
            var participating=new LinkedHashSet<String>();
            for(String key:componentBindings.values()) {
                String set=binding.instanceSetForRuntimeKey(key);
                if(declared.contains(set))participating.add(set);
            }
            if(participating.isEmpty())return Status.unknown("尚未取得设备连接观测");
            return deviceRequirement(participating,"","",true);
        }
        return deviceRequirement(new LinkedHashSet<>(declared),expected,expected,false);
    }

    private String componentOf(String id) {
        String runtimeKey=componentBindings.get(id);
        return runtimeKey==null?"":binding.componentForRuntimeKey(runtimeKey);
    }
    private Status deviceStatus(String id) {
        if(!gateway.connected())return Status.unknown("Tango 连接不可用");
        if(invalidObservations.containsKey(id))return Status.unknown(id+" / "+invalidObservations.get(id));
        long maxAge=projection.evidenceMaxAgeMs();
        if(!snapshots.containsKey(id)||System.currentTimeMillis()-observedAt.getOrDefault(id,0L)>maxAge)
            return Status.unknown("设备观测缺失或过期："+id);
        return Status.ok();
    }
    private boolean matches(JsonNode snapshot,String states,String outerStates) {
        return words(states).contains(snapshot.path("state_id").asText())||words(outerStates).contains(snapshot.path("outer_state").asText());
    }
    private static Set<String> words(String value){if(value==null||value.isBlank())return Set.of();return new LinkedHashSet<>(Arrays.asList(value.trim().split("\\s+")));}
    private static Status combine(Status left,Status right){if("violated".equals(left.status)||"violated".equals(right.status))return "violated".equals(left.status)?left:right;if("unknown".equals(left.status)||"unknown".equals(right.status))return "unknown".equals(left.status)?left:right;return Status.ok();}
    private static Status either(Status left,Status right){if("valid".equals(left.status)||"valid".equals(right.status))return Status.ok();if("unknown".equals(left.status)||"unknown".equals(right.status))return "unknown".equals(left.status)?left:right;return left;}
    private record Status(String status,String reason){static Status ok(){return new Status("valid","");}static Status violated(String reason){return new Status("violated",reason);}static Status unknown(String reason){return new Status("unknown",reason);}}

    private static String resource(String path)throws Exception{var resource=new ClassPathResource(path);try(var input=resource.getInputStream()){return new String(input.readAllBytes(),StandardCharsets.UTF_8);}}
}
