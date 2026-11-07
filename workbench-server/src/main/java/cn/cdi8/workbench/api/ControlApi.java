package cn.cdi8.workbench.api;

import cn.cdi8.workbench.integration.facility.FacilityClient;
import cn.cdi8.workbench.model.ModelStore;
import cn.cdi8.workbench.workflow.WorkflowService;
import cn.cdi8.workbench.integration.facility.FacilityStateClient;

import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ControlApi {
    private final WorkflowService workflows;
    private final FacilityClient facility;
    private final ModelStore models;
    private final FacilityStateClient facilityStates;
    public ControlApi(WorkflowService workflows, FacilityClient facility, ModelStore models, FacilityStateClient facilityStates) { this.workflows = workflows; this.facility = facility; this.models=models; this.facilityStates=facilityStates; }
    @GetMapping("/processes") public Object processes() { return workflows.definitions(); }
    @GetMapping(value="/processes/{key}/xml", produces="application/xml")
    public String xml(@PathVariable String key) { return workflows.xml(key); }
    @GetMapping("/connection") public Object connection() { return facility.status(); }
    @GetMapping("/facility/state") public Object facilityState() { return facilityStates.assessment(false); }
    @GetMapping("/facility/facts") public Object facilityFacts() { return facilityStates.factAssessment(false); }
    @GetMapping("/devices") public Object devices() { return facility.catalog(); }
    @GetMapping("/models") public Map<String, String> models() throws java.io.IOException {
        var resources = new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
            .getResources("classpath*:component-models/*.scxml");
        Map<String, String> models = new TreeMap<>();
        for (var resource : resources) {
            try (var input = resource.getInputStream()) {
                models.put(resource.getFilename(), new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        for(var model:this.models.list())if("scxml".equals(model.path("kind").asText()))
            for(var revision:model.path("revisions"))models.put(revision.path("id").asText(),revision.path("xml").asText());
        return models;
    }
    @GetMapping(value="/runs/{id}/xml", produces="application/xml")
    public String runXml(@PathVariable String id) { return workflows.runXml(id); }
    @PostMapping("/devices/{id}/conditions")
    public Object conditions(@PathVariable String id, @RequestBody Map<String, Boolean> values) {
        if (values == null || values.isEmpty()) throw new IllegalArgumentException("至少提供一个演示条件");
        if (values.keySet().stream().anyMatch(Objects::isNull)
                || values.values().stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("演示条件必须是布尔值");
        JsonNode snapshot = facility.snapshot(id);
        if (!snapshot.has("conditions")) throw new IllegalArgumentException("该设备不支持演示条件控制");
        return facility.setConditions(id, values);
    }
    @GetMapping("/runs") public Object recent() { return workflows.recent(); }
    @GetMapping("/runs/{id}") public Object view(@PathVariable String id) { return workflows.view(id); }
    @PostMapping("/runs/{id}/stop") public Object stop(@PathVariable String id) {
        workflows.cancel(id, "操作员停止流程；设备状态请在组件端确认"); return workflows.view(id);
    }
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class, FacilityClient.GatewayException.class})
    public ResponseEntity<?> error(RuntimeException ex) {
        return ResponseEntity.status(409).body(Map.of("error", Objects.toString(ex.getMessage(), ex.toString())));
    }
}
