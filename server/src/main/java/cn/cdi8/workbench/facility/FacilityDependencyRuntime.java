package cn.cdi8.workbench.facility;

import cn.cdi8.workbench.systemdefinition.CapabilityDependencies;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Resolves facility contracts against one invocation's selected resources. Never calls Flowable. */
@Component
public final class FacilityDependencyRuntime {
    public static final String VARIABLE="facilityDependencies";
    private final ObjectMapper json;
    private final FacilityStateRuntime states;
    private final Map<String,Watch> watches=new ConcurrentHashMap<>();
    private volatile long lastRefresh;
    private static final class Watch {
        final ObjectNode plan;
        ObjectNode failure;
        Watch(ObjectNode plan){this.plan=plan.deepCopy();}
    }
    public FacilityDependencyRuntime(ObjectMapper json,FacilityStateRuntime states){this.json=json;this.states=states;}

    public ObjectNode plan(String command,String component,String capability,String target,
                           Collection<?> beamlines,Map<String,String> selections,String defaultSource,Map<String,String> instances) {
        var contracts=states.definition().dependencies();
        var requirements=contracts.inputs(component,capability);
        if(requirements.isEmpty())return null;
        ObjectNode plan=json.createObjectNode().put("id",command).put("component",component).put("capability",capability)
            .put("device",target).put("status","unknown").put("reason","尚未核验能力输入依赖");
        ArrayNode leaves=plan.putArray("requirements");
        try {
            int matched=0;
            for(Object value:beamlines) {
                int beam=Integer.parseInt(value.toString());
                String source=selections.getOrDefault(Integer.toString(beam),defaultSource);
                String logical=states.binding().topology().logical(component,beam,source);
                if(!target.equals(instances.get(logical)))continue;
                matched++;
                for(var input:requirements)expand(component,input,beam,source,instances,leaves,input.maintain(),component+"/"+capability+"/"+input.id());
            }
            if(matched==0)throw new IllegalArgumentException("动作目标不属于本发所选束线："+target);
        } catch(RuntimeException ex) {
            plan.put("resolutionFailure",Objects.toString(ex.getMessage(),"无法解析能力输入依赖"));
        }
        return plan;
    }

    private void expand(String component,CapabilityDependencies.Input input,int beam,String source,Map<String,String> instances,
                        ArrayNode leaves,String maintain,String via) {
        var contracts=states.definition().dependencies();
        var candidates=contracts.incoming(component,input).stream().filter(edge->contracts.allocated(edge,beam))
            .filter(edge->!contracts.isSource(edge)||edge.source().equals(source)).toList();
        if(candidates.size()!=1)throw new IllegalArgumentException("输入必须唯一选路：束线 "+beam+" / "+via+" / "+source);
        var edge=candidates.get(0);
        String logical=states.binding().topology().logical(edge.source(),beam,source),device=instances.get(logical);
        if(device==null)throw new IllegalArgumentException("依赖设备未绑定："+logical+"（束线 "+beam+"）");
        var output=contracts.output(edge.source(),edge.sourcePort(),input.signal());
        String trace=via+" ← "+edge.id();
        leaves.addObject().put("device",device).put("logical",logical).put("component",edge.source())
            .put("beamline",beam).put("fact",output.fact()).put("signal",input.signal()).put("maintain",maintain).put("via",trace);
        for(var nested:output.inputs())expand(output.component(),nested,beam,source,instances,leaves,maintain,trace+"/"+nested.id());
    }
    public ObjectNode read(Object raw) {
        try {return (ObjectNode)json.readTree(raw.toString());}
        catch(Exception ex){throw new IllegalStateException("能力依赖记录损坏",ex);}
    }

    /** Register before dispatch. A transient observed failure remains latched for this command. */
    public ObjectNode check(Object raw,boolean started,boolean reconcile) {
        ObjectNode plan=read(raw);String id=plan.path("id").asText();
        Watch candidate=new Watch(plan);
        if(plan.path("latched").asBoolean())candidate.failure=plan.deepCopy();
        else if(started&&hasContinuous(plan))candidate.failure=failed(plan,"unknown","持续依赖监控中断，无法证明已下发动作期间输入一直有效");
        Watch watch=watches.putIfAbsent(id,candidate);
        if(watch==null)watch=candidate;
        long now=System.currentTimeMillis();
        boolean refresh=reconcile&&now-lastRefresh>=1000;
        if(refresh)lastRefresh=now;
        ObjectNode snapshot=states.assessment(refresh);
        synchronized(watch) {
            if(watch.failure!=null)return watch.failure.deepCopy();
            ObjectNode result=evaluate(plan,snapshot,started);
            if(!"valid".equals(result.path("status").asText())) {
                result.put("latched",true);watch.failure=result.deepCopy();
            }
            return result;
        }
    }

    @EventListener
    public void changed(FacilityStateRuntime.Changed event) {
        var snapshot=states.current();
        for(var watch:watches.values())synchronized(watch) {
            if(watch.failure!=null)continue;
            ObjectNode result=evaluate(watch.plan,snapshot,true);
            if(!"valid".equals(result.path("status").asText())) {
                result.put("latched",true);watch.failure=result;
            }
        }
    }

    private ObjectNode evaluate(ObjectNode plan,JsonNode snapshot,boolean started) {
        ObjectNode result=plan.deepCopy().put("status","valid").put("reason","").put("checkedAt",System.currentTimeMillis());
        result.put("observationSequence",snapshot.path("sequence").asLong());
        if(plan.has("resolutionFailure"))return failed(result,"unknown",plan.path("resolutionFailure").asText());
        for(JsonNode node:result.path("requirements")) {
            ObjectNode requirement=(ObjectNode)node;
            if(started&&"start".equals(requirement.path("maintain").asText())) {
                requirement.put("status","released");continue;
            }
            JsonNode fact=snapshot.path("deviceFacts").path(requirement.path("device").asText()).path(requirement.path("fact").asText());
            String status=fact.path("status").asText("unknown"),reason=fact.path("reason").asText("缺少设备事实观测");
            if(!requirement.path("component").asText().equals(fact.path("component").asText())) {status="unknown";reason="设备事实类型不匹配或缺失";}
            requirement.put("status",status).put("reason",reason);requirement.set("observation",fact.deepCopy());
            if(!"valid".equals(status)&&"valid".equals(result.path("status").asText())) {
                result.put("status",status).put("reason","束线 "+requirement.path("beamline").asInt()+" / "+requirement.path("logical").asText()
                    +" / "+requirement.path("fact").asText()+"："+reason);
            }
        }
        return result;
    }
    private ObjectNode failed(ObjectNode plan,String status,String reason) {
        return plan.deepCopy().put("status",status).put("reason",reason).put("latched",true).put("checkedAt",System.currentTimeMillis());
    }
    private boolean hasContinuous(ObjectNode plan) {
        for(var requirement:plan.path("requirements"))if("execution".equals(requirement.path("maintain").asText()))return true;
        return false;
    }
    public void release(String command){watches.remove(command);}
    public void retain(Set<String> active){watches.keySet().retainAll(active);}
}
