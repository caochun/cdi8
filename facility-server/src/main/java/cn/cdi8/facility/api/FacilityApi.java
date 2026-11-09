package cn.cdi8.facility.api;

import cn.cdi8.facility.runtime.*;
import cn.cdi8.facility.contract.*;
import cn.cdi8.facility.integration.TangoGateway;
import cn.cdi8.facility.contract.SystemDefinitionModel;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping({"/api/facility","/api/supervision"})
public final class FacilityApi {
    private final ObjectMapper json;
    private final FacilityStateRuntime states;
    private final FacilityDependencyRuntime dependencies;
    private final TangoGateway gateway;
    private final FacilityCommandAdmission commands;
    public FacilityApi(ObjectMapper json,FacilityStateRuntime states,FacilityDependencyRuntime dependencies,TangoGateway gateway,FacilityCommandAdmission commands) {
        this.json=json;this.states=states;this.dependencies=dependencies;this.gateway=gateway;this.commands=commands;
    }
    @GetMapping("/health") public Object health(){return Map.of("service","facility-server","status","up");}
    @GetMapping("/state") public Object state(@RequestParam(defaultValue="false") boolean refresh){return states.assessment(refresh);}
    @GetMapping("/facts") public Object facts(@RequestParam(defaultValue="false") boolean refresh){return states.factAssessment(refresh);}
    @GetMapping("/models") public Object models(){return states.models();}
    @PostMapping("/admissions/check") public Object admission(@RequestBody JsonNode body){return states.admission(body.path("operationRef").asText());}
    @PostMapping("/models") public Object activate(@RequestBody JsonNode body){
        var definition=SystemDefinitionModel.parse(body.path("definitionXml").asText());
        var components=FacilityComponentModels.archived(definition,strings(body.path("components")));
        // Identical activation is safe during workbench recovery and keeps duties intact.
        if(states.models().equals(body))return states.models();
        return commands.replaceContext(()->{
            states.activate(body.path("definitionXml").asText(),body.path("bindingXml").asText(),body.path("projectionXml").asText(),components);
            return states.models();
        });
    }
    @GetMapping("/connection") public Object connection(){return gateway.status();}
    /** Facility-owned environment lifecycle. Workbench is only an optional client. */
    @GetMapping("/environment") public Object environment(){return gateway.request("GET","/lab/environment",null);}
    @PostMapping("/environment") public Object replaceEnvironment(@RequestBody JsonNode body){return replaceEnvironmentInternal(body);}
    @GetMapping("/devices") public Object devices(){return gateway.catalog();}
    @GetMapping("/devices/{device}") public Object snapshot(@PathVariable String device){return gateway.snapshot(device);}
    @GetMapping("/devices/{device}/history") public Object history(@PathVariable String device){return gateway.request("GET","/devices/"+device+"/history",null);}
    @PostMapping("/validate") public Object validate(@RequestBody JsonNode body){return gateway.request("POST","/lab/validate",body);}
    @PostMapping("/reconnect") public Object reconnect(){gateway.reconnect();return gateway.status();}
    @PostMapping("/devices/{device}/commands") public Object execute(@PathVariable String device,@RequestBody JsonNode body){return commands.execute(device,body);}
    @GetMapping("/devices/{device}/commands/{id}") public Object result(@PathVariable String device,@PathVariable String id){return commands.result(device,id);}
    @PostMapping("/dependencies/plan") public Object plan(@RequestBody JsonNode b){
        if(b instanceof com.fasterxml.jackson.databind.node.ObjectNode object)FacilityNames.rename(object,"componentType","component");
        var plan=dependencies.plan(b.path("id").asText(),b.path("componentType").asText(),b.path("capability").asText(),b.path("device").asText(),
            json.convertValue(b.path("beamlines"),List.class),strings(b.path("selections")),b.path("defaultSource").asText(),strings(b.path("instances")),b.path("heldInput").isTextual()?b.path("heldInput").asText():null);
        return plan==null?json.nullNode():plan;
    }
    @PostMapping("/dependencies/check") public Object check(@RequestBody JsonNode b){return dependencies.check(b.path("plan").toString(),b.path("started").asBoolean(),b.path("refresh").asBoolean());}
    @PostMapping("/dependencies/release") public Object release(@RequestBody JsonNode b){dependencies.release(b.path("id").asText());return Map.of("released",true);}
    @PostMapping("/dependencies/retain") public Object retain(@RequestBody Set<String> ids){dependencies.retain(ids);return Map.of("retained",ids.size());}
    /** Restricted adapter operations. Production commands cannot bypass admission through a raw proxy. */
    @PostMapping("/adapter") public Object adapter(@RequestBody JsonNode b){
        String method=b.path("method").asText(),path=b.path("path").asText();
        boolean read="GET".equals(method)&&(path.matches("/devices(?:/[A-Za-z0-9_.:-]+(?:/history)?)?")||path.equals("/lab/environment"));
        boolean write="POST".equals(method)&&(Set.of("/lab/environment","/lab/validate").contains(path)||path.matches("/devices/[A-Za-z0-9_.:-]+/(conditions|simulate|events)"));
        if(!read&&!write)throw new IllegalArgumentException("监督接口不允许该适配操作："+method+" "+path);
        if(write&&path.equals("/lab/environment"))return replaceEnvironmentInternal(b.path("body"));
        return gateway.request(method,path,b.hasNonNull("body")?b.path("body"):null);
    }
    private Object replaceEnvironmentInternal(JsonNode body){return commands.replaceContext(()->{
        // Active adapter actions can also exist outside a BPMN invocation.
        for(var device:gateway.catalog()) {
            var snapshot=gateway.snapshot(device.path("id").asText());
            if(!snapshot.path("active_command_id").asText("").isBlank())
                throw new IllegalStateException("设备仍有活动命令，禁止重建模拟环境");
        }
        var result=gateway.request("POST","/lab/environment",body);
        gateway.reconnect();return result;
    });}
    private Map<String,String> strings(JsonNode node){return node.isObject()?json.convertValue(node,new com.fasterxml.jackson.core.type.TypeReference<Map<String,String>>(){}):Map.of();}
    @ExceptionHandler({IllegalArgumentException.class,IllegalStateException.class,TangoGateway.GatewayException.class})
    public ResponseEntity<?> error(RuntimeException ex){return ResponseEntity.status(ex instanceof TangoGateway.GatewayException g?g.status():409).body(Map.of("error",Objects.toString(ex.getMessage(),ex.toString())));}
}
