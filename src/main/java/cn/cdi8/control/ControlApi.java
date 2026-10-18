package cn.cdi8.control;

import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
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
    @PostMapping("/devices/{id}/conditions")
    public Object conditions(@PathVariable String id, @RequestBody Map<String, Boolean> values) {
        if (values == null || values.isEmpty()) throw new IllegalArgumentException("至少提供一个演示条件");
        if (values.keySet().stream().anyMatch(Objects::isNull)
                || values.values().stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("演示条件必须是布尔值");
        JsonNode snapshot = tango.snapshot(id);
        if (!snapshot.has("conditions")) throw new IllegalArgumentException("该设备不支持演示条件控制");
        return tango.setConditions(id, values);
    }
    @GetMapping("/runs") public Object recent() { return workflows.recent(); }
    @GetMapping("/runs/{id}") public Object view(@PathVariable String id) { return workflows.view(id); }
    @PostMapping("/runs") public Object start(@RequestBody Start request) {
        List<String> sources = request.sources();
        if (sources == null)
            sources = Set.of("component_initialization", "parameter_dispatch").contains(request.processKey())
                ? List.of() : List.of("seed");
        return workflows.start(request.processKey(), sources);
    }
    @PostMapping("/runs/{id}/stop") public Object stop(@PathVariable String id) {
        workflows.stop(id, "操作员停止流程；设备状态请在组件端确认"); return workflows.view(id);
    }
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class, TangoGateway.GatewayException.class})
    public ResponseEntity<?> error(RuntimeException ex) {
        return ResponseEntity.status(409).body(Map.of("error", Objects.toString(ex.getMessage(), ex.toString())));
    }
}
