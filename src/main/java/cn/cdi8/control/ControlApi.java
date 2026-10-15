package cn.cdi8.control;

import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ControlApi {
    private final WorkflowService workflows;
    private final TangoGateway tango;
    public ControlApi(WorkflowService workflows, TangoGateway tango) { this.workflows = workflows; this.tango = tango; }
    public record Start(String processKey, List<String> sources) {}
    @GetMapping("/processes") public Object processes() { return workflows.definitions(); }
    @GetMapping(value="/processes/{key}/xml", produces="application/xml")
    public String xml(@PathVariable String key) { return workflows.xml(key); }
    @GetMapping("/connection") public Object connection() { return tango.status(); }
    @GetMapping("/devices") public Object devices() { return tango.catalog(); }
    @GetMapping("/runs") public Object recent() { return workflows.recent(); }
    @GetMapping("/runs/{id}") public Object view(@PathVariable String id) { return workflows.view(id); }
    @PostMapping("/runs") public Object start(@RequestBody Start request) {
        return workflows.start(request.processKey(), request.sources()==null ? List.of("seed") : request.sources());
    }
    @PostMapping("/runs/{id}/stop") public Object stop(@PathVariable String id) {
        workflows.stop(id, "操作员停止流程；设备状态请在组件端确认"); return workflows.view(id);
    }
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class, TangoGateway.GatewayException.class})
    public ResponseEntity<?> error(RuntimeException ex) {
        return ResponseEntity.status(409).body(Map.of("error", Objects.toString(ex.getMessage(), ex.toString())));
    }
}
