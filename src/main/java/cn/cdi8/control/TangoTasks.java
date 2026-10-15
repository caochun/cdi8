package cn.cdi8.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.flowable.bpmn.model.SubProcess;
import org.flowable.engine.delegate.BpmnError;
import org.flowable.engine.delegate.DelegateExecution;
import org.springframework.stereotype.Component;

/** BPMN declares bindings; task states belong to the component transport protocol. */
@Component("tango")
public class TangoTasks {
    private final ObjectMapper json;
    private final TangoGateway gateway;
    public TangoTasks(ObjectMapper json, TangoGateway gateway) { this.json = json; this.gateway = gateway; }

    public JsonNode binding(DelegateExecution e) {
        var parent = (SubProcess)e.getCurrentFlowElement().getParentContainer();
        try { return json.readTree(parent.getDocumentation()); }
        catch (Exception ex) { throw new BpmnError("DEVICE_ERROR", "BPMN 动作绑定无效"); }
    }

    public void enqueue(DelegateExecution e) {
        JsonNode binding = binding(e);
        @SuppressWarnings("unchecked") Map<String,String> target = (Map<String,String>)e.getVariable("target");
        String component = target.get("component");
        String action = binding.path("actions").path(component).asText();
        if (action.isEmpty()) throw new BpmnError("DEVICE_ERROR", "未定义组件动作：" + component);
        String id = UUID.nameUUIDFromBytes((e.getProcessInstanceId() + ":" + e.getId() + ":" + e.getCurrentActivityId())
            .getBytes(StandardCharsets.UTF_8)).toString();
        // Register durably before any Tango call. The pump sends only after the
        // receiveTask exists in the committed Flowable transaction.
        e.setVariableLocal("commandId", id);
        e.setVariableLocal("commandDevice", target.get("id"));
        e.setVariableLocal("commandAction", action);
        e.setVariableLocal("commandNode", ((SubProcess)e.getCurrentFlowElement().getParentContainer()).getId());
        e.setVariableLocal("expectedState", binding.path("states").path(component).asText());
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
            if (!expected.isEmpty() && !expected.equals(current.path("state_id").asText()))
                throw new IllegalStateException("设备未到达目标状态：" + expected);
            if ("preamp_repeat".equals(e.getVariableLocal("commandAction"))
                    && !current.path("conditions").path("preamp_repeat_ready").asBoolean())
                throw new IllegalStateException("同步预放重频模式尚未生效");
        } catch (Exception ex) {
            e.setVariable("failureReason", ex.getMessage());
            throw new BpmnError("DEVICE_ERROR", ex.getMessage());
        }
    }

    public void mark(DelegateExecution e, String name) { e.setVariable(name, true); }

    public void finish(DelegateExecution e) {
        @SuppressWarnings("unchecked") var sources = (List<Map<String,String>>)e.getVariable("sourceTargets");
        @SuppressWarnings("unchecked") var preamps = (List<Map<String,String>>)e.getVariable("preampTargets");
        @SuppressWarnings("unchecked") var multipass = (List<Map<String,String>>)e.getVariable("multipassTargets");
        try {
            @SuppressWarnings("unchecked") var contexts = (List<Map<String,String>>)e.getVariable("contextTargets");
            JsonNode conditions = gateway.snapshot(contexts.get(0).get("id")).path("conditions");
            for (String key : List.of("safety_permit", "preamp_repeat_ready", "collimation_environment_ready"))
                if (!conditions.path(key).asBoolean()) throw new IllegalStateException("外部条件未满足：" + key);
            for (var target : sources) requireState(gateway.snapshot(target.get("id")), "outer_state", "emitting");
            for (var target : preamps) requireState(gateway.snapshot(target.get("id")), "state_id", "fineLoopOk");
            for (var target : multipass) requireState(gateway.snapshot(target.get("id")), "state_id", "collimationReady");
            e.setVariable("outcome", "SUCCEEDED");
        } catch (Exception ex) {
            e.setVariable("failureReason", ex.getMessage());
            throw new BpmnError("DEVICE_ERROR", ex.getMessage());
        }
    }

    private void requireState(JsonNode snapshot, String key, String expected) {
        if (!expected.equals(snapshot.path(key).asText()) || snapshot.hasNonNull("active_command_id"))
            throw new IllegalStateException("当前组件资格已失效：要求 " + key + "=" + expected);
    }
    public void succeeded(DelegateExecution e) { e.setVariable("outcome", "SUCCEEDED"); }
    public void failed(DelegateExecution e) {
        e.setVariable("outcome", "FAILED");
        if (e.getVariable("failureReason") == null) e.setVariable("failureReason", "设备失败或等待超时");
    }
}
