package cn.cdi8.facility.runtime;

import cn.cdi8.facility.integration.TangoGateway;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import java.util.function.Supplier;

/** The only production dispatch path. Resolve contracts from server-owned models and device bindings. */
@Component
public final class FacilityCommandAdmission {
    private final ObjectMapper json;
    private final FacilityStateRuntime states;
    private final TangoGateway gateway;
    private final FacilityRuntimeStore store;
    private final FacilityDependencyRuntime dependencies;
    private final ObjectNode commands;
    private boolean journalDirty;
    public FacilityCommandAdmission(ObjectMapper json,FacilityStateRuntime states,TangoGateway gateway,FacilityRuntimeStore store) {
        this.json=json;this.states=states;this.gateway=gateway;this.store=store;
        this.dependencies=new FacilityDependencyRuntime(json,states);
        this.commands=(ObjectNode)store.read("commands");
    }
    // Always acquire the state monitor before the command journal monitor. State
    // notifications are synchronous and already hold the former.
    private <T> T locked(Supplier<T> action) {
        synchronized(states) { synchronized(this) {
            // A failed write must be retried before cached results or replacement
            // decisions can escape the authoritative boundary.
            if(journalDirty)save();
            return action.get();
        } }
    }
    private void save() {
        journalDirty=true;
        store.write("commands",commands);
        journalDirty=false;
    }
    @EventListener public void changed(FacilityStateRuntime.Changed event){
        locked(()->{
            dependencies.changed(event);
            boolean dirty=false;
            for(var entry:commands)if(entry.has("plan")&&!entry.has("result")) {
                var evidence=dependencies.check(entry.path("evidence").toString(),true,false);
                if(evidence.path("latched").asBoolean()&&!evidence.equals(entry.path("evidence"))) {
                    ((ObjectNode)entry).set("evidence",evidence);dirty=true;
                }
            }
            if(dirty)save();
            return null;
        });
    }

    /** A model/environment change must not erase evidence for unresolved commands. */
    public <T> T replaceContext(Supplier<T> replacement) {
        return locked(()->{
            states.assessment(true);
            if(states.hasActiveDuty())throw new IllegalStateException("监督工作状态尚未取得安全退出证据，禁止替换运行环境或模型");
            for(var entry:commands)if(!entry.has("result"))throw new IllegalStateException("存在结果未决的设备命令，禁止替换运行环境或模型");
            return replacement.get();
        });
    }

    /** Reconcile even when no workbench is connected. Never retries Execute. */
    @Scheduled(fixedDelay=2000) public void reconcile() {
        locked(()->{
            for(var entry:commands)if(!entry.has("result")) {
                var intent=entry.path("intent");String id=intent.path("request").path("command_id").asText();
                try {resultLocked(intent.path("device").asText(),id);}
                catch(TangoGateway.GatewayException ignored) { /* Outcome remains unresolved. */ }
            }
            return null;
        });
    }

    public JsonNode execute(String device,JsonNode body) {return locked(()->executeLocked(device,body));}
    private JsonNode executeLocked(String device,JsonNode body) {
        String id=body.path("command_id").asText(),action=body.path("action").asText();
        if(!id.matches("[A-Za-z0-9_.:-]{1,128}")||action.isBlank())throw new IllegalArgumentException("命令缺少有效 command_id/action");
        ObjectNode intent=json.createObjectNode().put("device",device);intent.set("request",body);
        if(commands.has(id)) {
            if(!intent.equals(commands.path(id).path("intent")))throw new IllegalArgumentException("命令编号已用于其他请求");
            return resultLocked(device,id);
        }
        var assessment=states.assessment(true);
        if(!gateway.connected())throw new IllegalStateException("设备观测通道不可用，禁止动作放行");
        for(var constraint:assessment.path("constraints"))
            if(constraint.path("active").asBoolean()&&!"valid".equals(constraint.path("status").asText()))
                throw new IllegalStateException("系统工作状态约束未满足："+constraint.path("label").asText()+" / "+constraint.path("reason").asText());
        JsonNode target=null;
        Map<String,Map<String,String>> catalog=new LinkedHashMap<>();
        for(var item:gateway.catalog()) {
            catalog.put(item.path("id").asText(),Map.of("deviceBindingKey",item.path("deviceBindingKey").asText()));
            if(device.equals(item.path("id").asText()))target=item;
        }
        if(target==null||!target.path("connected").asBoolean())throw new IllegalStateException("动作目标不存在或未连接："+device);
        String componentType=states.binding().componentTypeForBindingKey(target.path("deviceBindingKey").asText());
        var capability=states.definition().actionRefs(componentType).entrySet().stream().filter(e->action.equals(e.getValue())).map(Map.Entry::getKey).toList();
        if(capability.size()!=1)throw new IllegalArgumentException("动作必须唯一对应设施模型中的能力："+componentType+" / "+action);
        JsonNode context=body.path("admission");
        Map<String,String> explicit=context.path("instances").isObject()?json.convertValue(context.path("instances"),new com.fasterxml.jackson.core.type.TypeReference<Map<String,String>>(){}):Map.of();
        var instances=states.binding().topology().bind(catalog,explicit);
        List<Integer> beams=new ArrayList<>();Set<Integer> seen=new HashSet<>();
        for(var beam:context.path("beamlines")) {
            if(!beam.isIntegralNumber()||beam.asInt()<1||beam.asInt()>states.binding().topology().beamlineCount()||!seen.add(beam.asInt()))
                throw new IllegalArgumentException("动作束线选择无效");
            beams.add(beam.asInt());
        }
        Map<String,String> selections=context.path("selections").isObject()?json.convertValue(context.path("selections"),new com.fasterxml.jackson.core.type.TypeReference<Map<String,String>>(){}):Map.of();
        for(var key:selections.keySet())if(!beams.stream().anyMatch(b->b.toString().equals(key)))throw new IllegalArgumentException("动作选源编号必须属于所选束线");
        String source=context.path("defaultSource").asText(states.binding().topology().defaultSource());
        for(int beam:beams)if(!states.definition().dependencies().sourceAllowed(selections.getOrDefault(Integer.toString(beam),source),beam))throw new IllegalArgumentException("束线不允许所选光源");
        var plan=dependencies.plan(id,componentType,capability.get(0),device,beams,selections,source,instances);
        ObjectNode evidence=plan==null?json.createObjectNode().put("status","valid"):dependencies.check(plan.toString(),false,true);
        if(!"valid".equals(evidence.path("status").asText()))throw new IllegalStateException("设备动作准入失败："+evidence.path("reason").asText());
        var entry=commands.putObject(id);entry.set("intent",intent);entry.set("evidence",evidence);
        if(plan!=null)entry.set("plan",plan);
        entry.put("attemptedAt",System.currentTimeMillis());
        // Persist before any network I/O. A lost response is reconciled; Execute is never repeated.
        save();
        return recordResult(id,gateway.execute(device,id,action,body.path("parameters").isMissingNode()?Map.of():body.path("parameters")));
    }
    public JsonNode result(String device,String id) {return locked(()->resultLocked(device,id));}
    private JsonNode resultLocked(String device,String id) {
        if(commands.has(id)&&!device.equals(commands.path(id).path("intent").path("device").asText()))throw new IllegalArgumentException("命令设备不匹配");
        if(commands.path(id).has("result"))return commands.path(id).path("result").deepCopy();
        return recordResult(id,gateway.result(device,id));
    }
    private JsonNode recordResult(String id,JsonNode result) {
        var entry=commands.path(id);
        if(!entry.isObject())return result;
        var request=entry.path("intent").path("request");
        if(!id.equals(result.path("command_id").asText())||!request.path("action").asText().equals(result.path("action").asText()))
            throw new IllegalStateException("设备命令结果关联不匹配，保留未决记录");
        result=result.deepCopy();
        if(entry.has("plan")) {
            var evidence=dependencies.check(entry.path("evidence").toString(),true,false);
            ((ObjectNode)entry).set("evidence",evidence);
            if(result instanceof ObjectNode object)object.set("admissionEvidence",evidence);
        }
        if(entry instanceof ObjectNode object && Set.of("succeeded","failed","interrupted","rejected","cancelled").contains(result.path("status").asText())) {
            object.set("result",result.deepCopy());
        }
        save();
        if(entry.has("result"))dependencies.release(id);
        return result;
    }
}
