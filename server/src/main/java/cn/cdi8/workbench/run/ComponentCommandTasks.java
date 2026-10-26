package cn.cdi8.workbench.run;

import cn.cdi8.workbench.integration.tango.TangoGateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.flowable.engine.delegate.BpmnError;
import org.flowable.engine.delegate.DelegateExecution;
import org.springframework.stereotype.Component;

/** BPMN declares bindings; task states belong to the component transport protocol. */
@Component("componentTasks")
public class ComponentCommandTasks {
    private final ObjectMapper json;
    private final TangoGateway gateway;
    public ComponentCommandTasks(ObjectMapper json, TangoGateway gateway) {
        this.json = json;
        this.gateway = gateway;
    }

    /** Enqueues an action resolved from the facility system model. */
    public void enqueueFacilityAction(DelegateExecution e, String action, String expectedState) {
        @SuppressWarnings("unchecked") Map<String,String> target = (Map<String,String>) e.getVariable("target");
        if (target == null || target.get("id") == null || target.get("component") == null)
            throw new BpmnError("DEVICE_ERROR", "组件命令缺少 target");
        if (action == null || action.isBlank())
            throw new BpmnError("DEVICE_ERROR", "未定义组件动作：" + target.get("component"));
        // Persist a fresh ID for each activation; loop re-entry must not replay an old result.
        String id = UUID.randomUUID().toString();
        for (String name : List.of("commandResult", "dispatchAttempted", "commandOutcomeUnknown", "commandParameters"))
            e.removeVariableLocal(name);
        e.setVariableLocal("commandId", id);
        e.setVariableLocal("commandDevice", target.get("id"));
        e.setVariableLocal("commandAction", action);
        e.setVariableLocal("commandNode", e.getCurrentActivityId());
        e.setVariableLocal("expectedState", expectedState == null ? "" : expectedState);
        e.setVariableLocal("sent", false);
        e.setVariableLocal("facilityAction", true);
        e.setVariableLocal("commandStartedAt", System.currentTimeMillis());
        e.setVariableLocal("commandSource", gateway.snapshot(target.get("id")).path("source_id").asText());
        e.setVariableLocal("commandParameters", json.valueToTree(Map.of(
            "shot_id", Objects.toString(e.getVariable("shotId"), ""),
            "recipe_ref", Objects.toString(e.getVariable("recipeRef"), ""),
            "version", Objects.toString(e.getVariable("recipeVersion"), ""))).toString());
    }

    public void verify(DelegateExecution execution) { validateResult(execution.getVariablesLocal()); }

    public void validateResult(Map<String,Object> variables) {
        try {
            JsonNode result=json.readTree(variables.get("commandResult").toString());
            if(!Objects.equals(variables.get("commandId"),result.path("command_id").asText()) ||
                !Objects.equals(variables.get("commandAction"),result.path("action").asText()))
                throw new IllegalStateException("动作结果或任务关联不匹配");
            if(!"succeeded".equals(result.path("status").asText()))
                throw new IllegalStateException(result.path("reason").asText("设备动作未成功："+result.path("status").asText()));
            JsonNode snapshot=result.path("snapshot");
            if(!Objects.equals(variables.get("commandSource"),snapshot.path("source_id").asText()))
                throw new IllegalStateException("设备来源已变化，动作结果不能用于本发");
            if(snapshot.hasNonNull("active_command_id"))throw new IllegalStateException("设备仍有执行中的任务");
            String expected=Objects.toString(variables.get("expectedState"),"");
            if(expected.isBlank()||Arrays.stream(expected.split("\\|")).noneMatch(state->state.equals(snapshot.path("state_id").asText())))
                throw new IllegalStateException("设备未到达目标状态："+expected);
        }catch(RuntimeException ex){throw ex;}catch(Exception ex){throw new IllegalStateException("动作结果损坏",ex);}
    }
}
