package cn.cdi8.workbench.integration.facility;

import cn.cdi8.workbench.integration.facility.FacilityClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.springframework.stereotype.Component;

/** BPMN-side lifecycle adapter; dependency resolution, watches and latches belong to the supervisor. */
@Component
public final class FacilityDependencyClient {
    public static final String VARIABLE="facilityDependencies";
    private final ObjectMapper json;
    private final FacilityClient client;
    public FacilityDependencyClient(ObjectMapper json,FacilityClient client){this.json=json;this.client=client;}
    public ObjectNode plan(String id,String componentType,String capability,String device,Collection<?> beams,Map<String,String> selections,String source,Map<String,String> instances){return plan(id,componentType,capability,device,beams,selections,source,instances,null);}
    public ObjectNode plan(String id,String componentType,String capability,String device,Collection<?> beams,Map<String,String> selections,String source,Map<String,String> instances,String heldInput){
        Map<String,Object> body=new LinkedHashMap<>();
        body.put("id",id);body.put("componentType",componentType);body.put("capability",capability);body.put("device",device);
        body.put("beamlines",beams);body.put("selections",selections);body.put("defaultSource",source);body.put("instances",instances);body.put("heldInput",heldInput);
        var result=client.facility("POST","/dependencies/plan",body);return result==null||result.isNull()?null:(ObjectNode)result;
    }
    public ObjectNode read(Object raw){try{return cn.cdi8.facility.contract.FacilityNames.dependencyPlan((ObjectNode)json.readTree(raw.toString()));}catch(Exception ex){throw new IllegalStateException("能力依赖记录损坏",ex);}}
    public ObjectNode check(Object raw,boolean started,boolean refresh){
        var plan=read(raw);
        try{return (ObjectNode)client.facility("POST","/dependencies/check",Map.of("plan",plan,"started",started,"refresh",refresh));}
        catch(FacilityClient.GatewayException ex){return plan.put("status","unknown").put("latched",true).put("reason","监督服务不可用："+ex.getMessage());}
    }
    public void release(String id){try{client.facility("POST","/dependencies/release",Map.of("id",id));}catch(FacilityClient.GatewayException ignored){}}
    public void retain(Set<String> ids){try{client.facility("POST","/dependencies/retain",ids);}catch(FacilityClient.GatewayException ignored){}}
}
