package cn.cdi8.workbench.integration.facility;

import cn.cdi8.facility.contract.FacilityBindingModel;
import cn.cdi8.facility.contract.FacilityComponentModels;
import cn.cdi8.facility.contract.FacilityStateProjectionModel;

import cn.cdi8.workbench.integration.facility.FacilityClient;
import cn.cdi8.facility.contract.SystemDefinitionModel;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Remote view only. The workbench never projects observations or evaluates system duties. */
@Component("facilityStates")
public final class FacilityStateClient {
    public record Changed(long sequence) {}
    private final FacilityClient client;
    private final ObjectMapper json;
    private final ApplicationEventPublisher events;
    private volatile JsonNode models;
    public FacilityStateClient(ObjectMapper json,FacilityClient client,ApplicationEventPublisher events){this.json=json;this.client=client;this.events=events;}
    public ObjectNode current(){return assessment(false);}
    public ObjectNode assessment(boolean refresh) {
        try{return (ObjectNode)client.facility("GET","/state?refresh="+refresh,null);}
        catch(FacilityClient.GatewayException ex){
            ObjectNode value=json.createObjectNode().put("connected",false).put("integrity","unknown").put("reason",ex.getMessage());
            for(String field:List.of("facts","guards","constraints","deviceFacts","observations"))value.putObject(field);
            return value;
        }
    }
    public ObjectNode factAssessment(boolean refresh){return (ObjectNode)client.facility("GET","/facts?refresh="+refresh,null);}
    public void refresh(){assessment(true);}
    private JsonNode models(){
        if(models==null)models=client.facility("GET","/models",null);
        return models;
    }
    public SystemDefinitionModel definition(){return SystemDefinitionModel.parse(models().path("definitionXml").asText());}
    public FacilityBindingModel binding(){return FacilityBindingModel.parse(models().path("bindingXml").asText(),definition());}
    public FacilityStateProjectionModel projection(){return FacilityStateProjectionModel.parse(models().path("projectionXml").asText());}
    public FacilityComponentModels components(){return FacilityComponentModels.archived(definition(),json.convertValue(models().path("components"),new com.fasterxml.jackson.core.type.TypeReference<Map<String,String>>(){}));}
    public void activate(String definition,String binding,String projection){activate(definition,binding,projection,FacilityComponentModels.bundled(SystemDefinitionModel.parse(definition)));}
    public void activate(String definition,String binding,String projection,FacilityComponentModels components){
        models=client.facility("POST","/models",Map.of("definitionXml",definition,"bindingXml",binding,"projectionXml",projection,"components",components.xml()));
    }
    public void requireGuard(String guard){
        var value=assessment(true).path("guards").path(guard);
        if(!"valid".equals(value.path("status").asText()))throw new IllegalStateException("设施放行条件不满足："+guard+" / "+value.path("reason").asText("监督观测不可用"));
    }
    @EventListener public void notice(FacilityClient.Notice notice){
        String type=notice.payload().path("type").asText();
        if("models_changed".equals(type)||"connection_lost".equals(type))models=null;
        if("facility_changed".equals(type)||"connection_lost".equals(type))events.publishEvent(new Changed(notice.payload().path("sequence").asLong()));
    }
}
