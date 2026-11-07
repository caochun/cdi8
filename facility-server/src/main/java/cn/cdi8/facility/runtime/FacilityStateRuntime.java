package cn.cdi8.facility.runtime;

import cn.cdi8.facility.contract.FacilityBindingModel;
import cn.cdi8.facility.contract.FacilityComponentModels;
import cn.cdi8.facility.contract.FacilityStateProjectionModel;

import cn.cdi8.facility.integration.TangoGateway;
import cn.cdi8.facility.contract.SystemDefinitionModel;
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
    public record ModelsChanged() {}
    private final ObjectMapper json;
    private final TangoGateway gateway;
    private final ApplicationEventPublisher events;
    private SystemDefinitionModel definition;
    private FacilityBindingModel binding;
    private FacilityStateProjectionModel projection;
    private final Map<String, JsonNode> snapshots = new ConcurrentHashMap<>();
    private final Map<String, String> deviceBindingKeys = new ConcurrentHashMap<>();
    private final Map<String,Long> observedAt=new HashMap<>();
    private final Map<String,String> invalidObservations=new HashMap<>();
    private long sequence;
    private volatile ObjectNode current;
    private FacilityComponentModels components;
    private final FacilitySupervision supervision;
    private final FacilityFactProjection factProjection;
    private final FacilityRuntimeStore store;
    private ObjectNode models;
    private JsonNode lastCheckpoint;

    public FacilityStateRuntime(ObjectMapper json, TangoGateway gateway, ApplicationEventPublisher events) {
        this(json,gateway,events,null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public FacilityStateRuntime(ObjectMapper json,TangoGateway gateway,ApplicationEventPublisher events,FacilityRuntimeStore store) {
        this.json=json; this.gateway=gateway; this.events=events;
        this.store=store;
        this.supervision=new FacilitySupervision(json);
        this.factProjection=new FacilityFactProjection(json,gateway::connected);
        try {
            JsonNode saved=store==null?json.createObjectNode():store.read("models");
            String definitionXml=saved.path("definitionXml").asText(resource("system-models/gxlf-facility.system-definition.xml"));
            String bindingXml=saved.path("bindingXml").asText(resource("system-models/gxlf-facility-binding.xml"));
            String projectionXml=saved.path("projectionXml").asText(resource("system-models/gxlf-facility-state-projection.xml"));
            this.definition=SystemDefinitionModel.parse(definitionXml);
            this.binding=FacilityBindingModel.parse(bindingXml,this.definition);
            this.projection=FacilityStateProjectionModel.parse(projectionXml);
            this.components=saved.path("components").isObject()?FacilityComponentModels.archived(definition,json.convertValue(saved.path("components"),new com.fasterxml.jackson.core.type.TypeReference<Map<String,String>>(){})):FacilityComponentModels.bundled(this.definition);
            this.models=bundle(definitionXml,bindingXml,projectionXml,components);
            this.projection.validateComponents(this.definition,this.components);
            if(!binding.id().equals(projection.bindingRef()))throw new IllegalArgumentException("设施投影 bindingRef 不一致");
            if(store!=null) {
                supervision.restore(definition.operatingConstraints(),store.read("duties"));
                store.write("models",models);
            }
        } catch(Exception ex) { throw new IllegalStateException("无法加载设施状态投影模型："+ex.getMessage(),ex); }
        this.current=json.createObjectNode().put("model",projection.id()).put("definition",projection.definitionRef())
            .put("binding",binding.id()).put("integrity","unknown").put("connected",false)
            .put("sequence",0).put("reason","尚未取得 Tango 设施观测").put("changedAt",System.currentTimeMillis());
        current.putObject("facts"); current.putObject("guards"); current.putObject("observations");current.putObject("deviceFacts");
        current.putObject("constraints");
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
    public synchronized ObjectNode models(){return models.deepCopy();}
    public synchronized boolean hasActiveDuty(){return supervision.active();}
    private ObjectNode bundle(String definition,String binding,String projection,FacilityComponentModels components) {
        ObjectNode value=json.createObjectNode().put("definitionXml",definition).put("bindingXml",binding).put("projectionXml",projection);
        value.set("components",json.valueToTree(components.xml()));return value;
    }

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
        supervision.validateReplacement(nextDefinition.operatingConstraints());
        var nextModels=bundle(definitionXml,bindingXml,projectionXml,nextComponents);
        if(nextModels.equals(models))return;
        if(supervision.active())throw new IllegalStateException("存在尚未取得安全退出证据的工作状态，禁止切换设施模型");
        if(store!=null) {
            // A crash during replacement leaves an unknown initial duty, never a false safe baseline.
            store.write("duties",json.createObjectNode());
            store.write("models",nextModels);
        }
        models=nextModels;
        definition=nextDefinition;binding=nextBinding;projection=nextProjection;components=nextComponents;
        snapshots.clear();deviceBindingKeys.clear();observedAt.clear();invalidObservations.clear();
        current.put("model",projection.id()).put("definition",definition.id()).put("binding",binding.id());
        refresh();
        events.publishEvent(new ModelsChanged());
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
        if(!gateway.accepts(notice))return;
        JsonNode event=notice.payload();
        sequence++;
        if("connection_lost".equals(event.path("type").asText())) {
            // A reconnect may represent a newly created simulator generation.
            // Do not compare its source ids with evidence from the old generation.
            snapshots.clear();
            deviceBindingKeys.clear();
            invalidObservations.clear();
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
        collectSnapshots();
        evaluate();
    }

    /** Refreshes only the adapter/snapshot boundary for the phase-one fact API. */
    private void collectSnapshots() {
        if(!gateway.connected()) {
            observedAt.clear();
            current.put("connected",false).put("integrity","unknown").put("reason","Tango 连接不可用");
            current.put("sequence",++sequence).put("changedAt",System.currentTimeMillis());
            return;
        }
        try {
            JsonNode catalog=gateway.catalog();
            Set<String> seen = new HashSet<>();
            if(catalog.isArray())for(JsonNode item:catalog) {
                String id=item.path("id").asText(), deviceBindingKey=item.path("deviceBindingKey").asText();
                if(!id.isBlank()) {
                    seen.add(id);
                    if("context".equals(deviceBindingKey)||binding.knowsDeviceBindingKey(deviceBindingKey))deviceBindingKeys.put(id,deviceBindingKey);
                    else deviceBindingKeys.remove(id);
                    if(!item.path("connected").asBoolean()) {observedAt.remove(id);continue;}
                    try { accept(id,gateway.snapshot(id)); }
                    catch(Exception ex) { observedAt.remove(id); }
                }
            }
            deviceBindingKeys.keySet().retainAll(seen);
            current.put("connected",true).put("reason","");
        } catch(Exception ex) {
            observedAt.clear();
            current.put("connected",false).put("integrity","unknown").put("reason","读取设施目录失败："+ex.getMessage());
        }
        current.put("sequence",++sequence).put("changedAt",System.currentTimeMillis());
    }

    /**
     * Phase-one view: device evidence, device facts, facts and guards only.
     *
     * The live runtime still publishes constraints for the existing supervisor,
     * but callers validating the first phase can deliberately stop at this
     * boundary and avoid depending on supervision state.
     */
    public synchronized ObjectNode factAssessment(boolean refresh) {
        if (refresh) collectSnapshots();
        var assessment=factProjection.evaluate(definition,binding,projection,snapshots,deviceBindingKeys,observedAt,invalidObservations);
        ObjectNode result=current.deepCopy();
        result.set("facts",assessment.facts());
        result.set("guards",assessment.guards());
        result.set("deviceFacts",assessment.deviceFacts());
        result.set("observations",assessment.observations());
        result.put("integrity",assessment.integrity()).put("reason",assessment.reason())
            .put("connected",gateway.connected());
        result.remove("constraints");
        return result;
    }

    private void evaluate() {
        var assessment=factProjection.evaluate(definition,binding,projection,snapshots,deviceBindingKeys,observedAt,invalidObservations);
        ObjectNode constraints=supervision.assess(definition.operatingConstraints(),assessment.facts());
        if(store!=null) {
            var checkpoint=supervision.checkpoint();
            if(!checkpoint.equals(lastCheckpoint)){store.write("duties",checkpoint);lastCheckpoint=checkpoint;}
        }
        boolean changed=!assessment.facts().equals(current.path("facts"))
            || !assessment.guards().equals(current.path("guards"))
            || !assessment.deviceFacts().equals(current.path("deviceFacts"))
            || !constraints.equals(current.path("constraints"));
        if(changed)sequence++;
        current.put("sequence",sequence);
        current.set("facts",assessment.facts());
        current.set("guards",assessment.guards());
        current.set("deviceFacts",assessment.deviceFacts());
        current.set("constraints",constraints);
        current.put("integrity",assessment.integrity()).put("reason",assessment.reason()).put("connected",gateway.connected());
        current.set("observations",assessment.observations());
        // Listeners only schedule reconciliation; never call Flowable while holding this monitor.
        if(changed)events.publishEvent(new Changed(sequence));
    }

    private static String resource(String path)throws Exception{var resource=new ClassPathResource(path);try(var input=resource.getInputStream()){return new String(input.readAllBytes(),StandardCharsets.UTF_8);}}
}
