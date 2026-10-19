package cn.cdi8.workbench.run;

import cn.cdi8.workbench.integration.tango.TangoGateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.flowable.engine.delegate.BpmnError;
import org.flowable.engine.delegate.DelegateExecution;
import org.springframework.stereotype.Component;

/** BPMN declares bindings; task states belong to the component transport protocol. */
@Component("componentTasks")
public class ComponentCommandTasks {
    private final ObjectMapper json;
    private final TangoGateway gateway;
    private final List<ExecutionPolicy> executionPolicies;
    public ComponentCommandTasks(ObjectMapper json, TangoGateway gateway, List<ExecutionPolicy> executionPolicies) {
        this.json = json;
        this.gateway = gateway;
        this.executionPolicies = List.copyOf(executionPolicies);
    }

    /**
     * Enqueue a command declared directly by the component-command BPMN task.
     * The action and expected state are part of the process definition rather
     * than hidden in a subprocess documentation block.
     */
    public void enqueue(DelegateExecution e, String action, String expectedState) {
        @SuppressWarnings("unchecked") Map<String,String> target = (Map<String,String>) e.getVariable("target");
        if (target == null || target.get("id") == null || target.get("component") == null)
            throw new BpmnError("DEVICE_ERROR", "组件命令缺少 target");
        if (action == null || action.isBlank())
            throw new BpmnError("DEVICE_ERROR", "未定义组件动作：" + target.get("component"));
        String id = UUID.nameUUIDFromBytes((e.getProcessInstanceId() + ":" + e.getId() + ":" + e.getCurrentActivityId())
            .getBytes(StandardCharsets.UTF_8)).toString();
        e.setVariableLocal("componentCommand", true);
        e.setVariableLocal("commandId", id);
        e.setVariableLocal("commandDevice", target.get("id"));
        e.setVariableLocal("commandAction", action);
        e.setVariableLocal("commandNode", e.getCurrentActivityId());
        e.setVariableLocal("expectedState", expectedState == null ? "" : expectedState);
        e.setVariableLocal("sent", false);
    }

    public void verify(DelegateExecution e) {
        try {
            JsonNode result = json.readTree((String)e.getVariableLocal("commandResult"));
            String expected = (String)e.getVariableLocal("expectedState");
            if (!Objects.equals(e.getVariableLocal("commandId"), result.path("command_id").asText())
                    || !Objects.equals(e.getVariableLocal("commandAction"), result.path("action").asText())
                    || !"succeeded".equals(result.path("status").asText()))
                throw new IllegalStateException("动作结果或任务关联不匹配");
            JsonNode current = gateway.snapshot((String)e.getVariableLocal("commandDevice"));
            if (!current.path("active_command_id").isNull() && current.hasNonNull("active_command_id"))
                throw new IllegalStateException("设备仍有执行中的任务");
            if (!expected.isEmpty() && Arrays.stream(expected.split("\\|"))
                    .noneMatch(value -> value.equals(current.path("state_id").asText())))
                throw new IllegalStateException("设备未到达目标状态：" + expected);
            for (var policy : executionPolicies) policy.verifyCommand(e, current);
        } catch (Exception ex) {
            e.setVariable("failureReason", ex.getMessage());
            throw new BpmnError("DEVICE_ERROR", ex.getMessage());
        }
    }

    public void mark(DelegateExecution e, String name) { e.setVariable(name, true); }

    public void succeeded(DelegateExecution e) { e.setVariable("outcome", "SUCCEEDED"); }
    public void failed(DelegateExecution e) {
        e.setVariable("outcome", "FAILED");
        if (e.getVariable("failureReason") == null) e.setVariable("failureReason", "设备失败或等待超时");
    }
}
