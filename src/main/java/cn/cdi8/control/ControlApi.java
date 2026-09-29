package cn.cdi8.control;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/runs")
public class ControlApi {
    private final RunService runs;
    public ControlApi(RunService runs) { this.runs=runs; }
    public record StartRequest(String requestId,Map<String,Map<String,Object>> parameters) {}
    @GetMapping public java.util.List<Map<String,Object>> recent() { return runs.recent(); }
    @PostMapping public Map<String,Object> start(@RequestBody StartRequest request) {
        return runs.start(request.requestId(),request.parameters()==null?Map.of():request.parameters());
    }
    @GetMapping("/{id}") public Map<String,Object> view(@PathVariable String id) { return runs.view(id); }
    @PostMapping("/{id}/interlock") public Map<String,Boolean> interlock(@PathVariable String id) { runs.interlock(id);return Map.of("accepted",true); }
    @PostMapping("/{id}/compensate") public Map<String,Boolean> compensate(@PathVariable String id) { runs.compensate(id);return Map.of("accepted",true); }
    @PostMapping("/{id}/commands/{task}/simulate") public Map<String,Boolean> simulate(@PathVariable String id,@PathVariable String task,@RequestBody Map<String,String> request) {
        runs.simulate(id,task,request.get("outcome"));return Map.of("accepted",true);
    }
    @ExceptionHandler({IllegalStateException.class,IllegalArgumentException.class})
    public ResponseEntity<Map<String,String>> invalid(RuntimeException ex) { return ResponseEntity.status(409).body(Map.of("error",ex.getMessage())); }
}
