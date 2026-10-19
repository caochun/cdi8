package cn.cdi8.control;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workbench")
public class WorkbenchApi {
    private final WorkbenchService workbench;private final ModelStore models;private final TangoGateway tango;private final DebugTrace trace;
    public WorkbenchApi(WorkbenchService workbench,ModelStore models,TangoGateway tango,DebugTrace trace){this.workbench=workbench;this.models=models;this.tango=tango;this.trace=trace;}
    @GetMapping("/models") public Object models(){return models.list();}
    @PostMapping("/models") public Object save(@RequestBody JsonNode body){return workbench.save(body);}
    @PostMapping("/validate") public Object validate(@RequestBody JsonNode body){return workbench.validate(body);}
    @PostMapping("/models/{id}/versions") public Object publish(@PathVariable String id){return workbench.publish(id);}
    @GetMapping("/configuration") public Object configuration(){return workbench.config();}
    @PostMapping("/configuration") public Object saveConfiguration(@RequestBody JsonNode body){return workbench.saveConfig(body);}
    @PostMapping("/environment") public Object apply(@RequestBody JsonNode body){return workbench.apply(body);}
    @GetMapping("/environment") public Object environment(){return tango.request("GET","/lab/environment",null);}
    @PostMapping("/runs") public Object start(@RequestBody JsonNode body){return workbench.start(body);}
    @GetMapping("/runs/{id}/trace") public Object trace(@PathVariable String id){return trace.events(id);}
    @PostMapping("/trials") public Object trial(@RequestBody JsonNode body){return tango.request("POST","/lab/trials",Map.of("xml",body.path("xml").asText()));}
    @GetMapping("/trials/{id}") public Object trial(@PathVariable String id){return tango.request("GET","/lab/trials/"+id,null);}
    @PostMapping("/trials/{id}/{operation}") public Object trialAction(@PathVariable String id,@PathVariable String operation,@RequestBody JsonNode body){
        if(!Set.of("commands","simulate","events").contains(operation))throw new IllegalArgumentException("未知操作");
        return tango.request("POST","/lab/trials/"+id+"/"+operation,body);
    }
    @GetMapping("/devices/{id}/history") public Object history(@PathVariable String id){return tango.request("GET","/devices/"+id+"/history",null);}
    @PostMapping("/devices/{id}/{operation}") public Object action(@PathVariable String id,@PathVariable String operation,@RequestBody JsonNode body){
        if(!Set.of("commands","simulate","events").contains(operation))throw new IllegalArgumentException("未知操作");
        return tango.request("POST","/devices/"+id+"/"+operation,body);
    }
    @ExceptionHandler({IllegalArgumentException.class,IllegalStateException.class,TangoGateway.GatewayException.class})
    public ResponseEntity<?> error(RuntimeException ex){return ResponseEntity.status(409).body(Map.of("error",Objects.toString(ex.getMessage(),ex.toString())));}
}
