package cn.cdi8.workbench.scenarios.gxlf;

import cn.cdi8.workbench.integration.tango.TangoGateway;
import cn.cdi8.workbench.run.ExecutionPolicy;
import cn.cdi8.workbench.run.RunPolicies;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.flowable.bpmn.model.FlowElementsContainer;
import org.flowable.bpmn.model.SubProcess;
import org.flowable.engine.delegate.DelegateExecution;
import org.springframework.stereotype.Component;

/** GXLF-specific continuous qualifications and synchronization evidence. */
@Component
public class GxlfExecutionPolicy implements ExecutionPolicy {
    private final TangoGateway gateway;

    public GxlfExecutionPolicy(TangoGateway gateway) { this.gateway = gateway; }

    private boolean applies(String key, Map<String, Object> variables) {
        return GxlfRunPolicy.PROCESS_KEYS.contains(RunPolicies.originalKey(key))
            || "gxlf".equals(variables.get("scenarioId"));
    }

    @Override
    public Map<String, Object> conditionSatisfied(String key, Map<String, Object> variables, String condition) {
        if (!applies(key, variables)) return Map.of();
        return switch (condition) {
            case "safety_permit" -> Map.of("safetyRequired", true);
            case "collimation_environment_ready" -> Map.of("environmentRequired", true);
            default -> Map.of();
        };
    }

    @Override
    public void verifyCommand(DelegateExecution execution, JsonNode snapshot) {
        FlowElementsContainer container = execution.getCurrentFlowElement().getParentContainer();
        while (container instanceof SubProcess sub) container = sub.getParentContainer();
        String key = container instanceof org.flowable.bpmn.model.Process process ? process.getId() : "";
        if (applies(key, execution.getVariables())
                && "preamp_repeat".equals(execution.getVariableLocal("commandAction"))
                && !snapshot.path("conditions").path("preamp_repeat_ready").asBoolean())
            throw new IllegalStateException("同步预放重频模式尚未生效");
    }

    @Override
    public String issue(String key, Map<String, Object> variables) {
        if (!applies(key, variables)) return null;
        for (var device : targets(variables, "allComponents")) {
            JsonNode snapshot = gateway.cached(device.get("id"));
            if (snapshot != null && "fault".equals(snapshot.path("outer_state").asText()))
                return device.get("id") + " 设备异常，停止流程放行";
        }
        if (Boolean.TRUE.equals(variables.get("safetyRequired")) && !condition(variables, "safety_permit"))
            return "安全许可失效或无法确认";
        if (Boolean.TRUE.equals(variables.get("environmentRequired")) && !condition(variables, "collimation_environment_ready"))
            return "准直环境条件失效或无法确认";
        if (Boolean.TRUE.equals(variables.get("repeatRequired")) && !condition(variables, "preamp_repeat_ready"))
            return "同步预放重频模式失效或无法确认";
        if (Boolean.TRUE.equals(variables.get("sourcesRequired")))
            for (var device : targets(variables, "sourceTargets")) {
                JsonNode snapshot = gateway.cached(device.get("id"));
                if (snapshot == null || !"emitting".equals(snapshot.path("outer_state").asText()))
                    return device.get("id") + " 供光资格失效或无法确认";
            }
        if (Boolean.TRUE.equals(variables.get("wakeupRequired")))
            for (var device : targets(variables, "preampTargets")) {
                JsonNode snapshot = gateway.cached(device.get("id"));
                if (snapshot == null || !"wakeup".equals(snapshot.path("outer_state").asText()))
                    return device.get("id") + " 预放唤醒资格失效或无法确认";
            }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> targets(Map<String, Object> variables, String name) {
        Object value = variables.get(name);
        return value == null ? List.of() : (List<Map<String, String>>)value;
    }

    private boolean condition(Map<String, Object> variables, String name) {
        var devices = targets(variables, "contextTargets");
        if (devices.size() != 1) return false;
        JsonNode snapshot = gateway.cached(devices.get(0).get("id"));
        return snapshot != null && snapshot.path("conditions").path(name).asBoolean();
    }
}
