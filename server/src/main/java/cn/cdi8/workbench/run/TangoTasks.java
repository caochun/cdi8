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
@Component("tango")
public class TangoTasks {
    private final ObjectMapper json;
    private final TangoGateway gateway;
    public TangoTasks(ObjectMapper json, TangoGateway gateway) { this.json = json; this.gateway = gateway; }

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

    public void initializationFinish(DelegateExecution e) {
        try {
            @SuppressWarnings("unchecked") var seeds = (List<Map<String,String>>)e.getVariable("seedTargets");
            @SuppressWarnings("unchecked") var shgs = (List<Map<String,String>>)e.getVariable("shgTargets");
            @SuppressWarnings("unchecked") var preamps = (List<Map<String,String>>)e.getVariable("preampTargets");
            @SuppressWarnings("unchecked") var multipasses = (List<Map<String,String>>)e.getVariable("multipassTargets");
            requireInitializationState(seeds, "qualified");
            requireInitializationState(shgs, "functionReady");
            requireInitializationState(preamps, "functionReady");
            requireInitializationState(multipasses, "functionReady");
            e.setVariable("outcome", "SUCCEEDED");
        } catch (Exception ex) {
            e.setVariable("failureReason", ex.getMessage());
            throw new BpmnError("DEVICE_ERROR", ex.getMessage());
        }
    }

    public void parameterDispatchFinish(DelegateExecution e) {
        try {
            @SuppressWarnings("unchecked") var targets = (List<Map<String,String>>)e.getVariable("allComponents");
            if (targets == null || targets.isEmpty()) throw new IllegalStateException("参数下发目标集合为空");
            for (var target : targets) {
                JsonNode snapshot = gateway.snapshot(target.get("id"));
                String state = snapshot.path("state_id").asText();
                boolean accepted = "seed".equals(target.get("component"))
                    ? Set.of("configured", "parameterOk").contains(state)
                    : "parameterOk".equals(state);
                if (!"ready".equals(snapshot.path("outer_state").asText())
                        || !accepted || snapshot.hasNonNull("active_command_id"))
                    throw new IllegalStateException(target.get("id") + " 未达到参数下发目标状态");
            }
            e.setVariable("outcome", "SUCCEEDED");
        } catch (Exception ex) {
            e.setVariable("failureReason", ex.getMessage());
            throw new BpmnError("DEVICE_ERROR", ex.getMessage());
        }
    }

    public void formalEmissionFinish(DelegateExecution e) {
        try {
            @SuppressWarnings("unchecked") var contexts = (List<Map<String,String>>)e.getVariable("contextTargets");
            @SuppressWarnings("unchecked") var sources = (List<Map<String,String>>)e.getVariable("sourceTargets");
            @SuppressWarnings("unchecked") var preamps = (List<Map<String,String>>)e.getVariable("preampTargets");
            @SuppressWarnings("unchecked") var multipasses = (List<Map<String,String>>)e.getVariable("multipassTargets");
            if (contexts == null || contexts.size() != 1) throw new IllegalStateException("正式发射条件设备集合无效");
            JsonNode conditions = gateway.snapshot(contexts.get(0).get("id")).path("conditions");
            for (String key : List.of("safety_permit", "diagnosis_acquisition_ready", "crystal_pose_ready",
                    "charge_preparation_ready", "preamp_single_recipe_ready", "measurement_single_recipe_ready",
                    "target_measurement_ready", "switch_charge_ready", "charge_complete", "warning_music_stopped",
                    "trigger_countdown_started", "pump_trigger_ready", "switch_trigger_ready", "synchronized_triggered"))
                if (!conditions.path(key).asBoolean()) throw new IllegalStateException("正式发射条件未满足：" + key);
            for (var target : sources) requireState(gateway.snapshot(target.get("id")), "outer_state", "emitting");
            for (var target : preamps) requireState(gateway.snapshot(target.get("id")), "state_id", "energyMeterCleared");
            for (var target : multipasses) requireState(gateway.snapshot(target.get("id")), "state_id", "collimationReady");
            e.setVariable("outcome", "SUCCEEDED");
        } catch (Exception ex) {
            e.setVariable("failureReason", ex.getMessage());
            throw new BpmnError("DEVICE_ERROR", ex.getMessage());
        }
    }

    public void postEmissionFinish(DelegateExecution e) {
        try {
            @SuppressWarnings("unchecked") var contexts = (List<Map<String,String>>)e.getVariable("contextTargets");
            @SuppressWarnings("unchecked") var sources = (List<Map<String,String>>)e.getVariable("sourceTargets");
            @SuppressWarnings("unchecked") var preamps = (List<Map<String,String>>)e.getVariable("preampTargets");
            @SuppressWarnings("unchecked") var multipasses = (List<Map<String,String>>)e.getVariable("multipassTargets");
            if (contexts == null || contexts.size() != 1) throw new IllegalStateException("发射后处理条件设备集合无效");
            JsonNode conditions = gateway.snapshot(contexts.get(0).get("id")).path("conditions");
            for (String key : List.of("diagnostic_postprocess_ready", "pump_data_collected", "pump_standby_ready",
                    "preionization_prepared", "preionization_music_started", "preionization_charge_complete", "preionization_music_stopped",
                    "preionization_countdown_started", "preionization_pump_trigger_ready", "preionization_sync_triggered",
                    "preionization_data_collected", "preionization_standby_ready", "warning_light_off",
                    "laser_parameter_collected", "target_analysis_ready", "target_retracted",
                    "switch_waveform_collected", "switch_standby_ready", "slab_purge_complete", "shielding_door_open"))
                if (!conditions.path(key).asBoolean()) throw new IllegalStateException("发射后处理条件未满足：" + key);
            for (var target : sources) requireState(gateway.snapshot(target.get("id")), "state_id", "collectionOk");
            for (var target : preamps) requireState(gateway.snapshot(target.get("id")), "state_id", "standbyReady");
            for (var target : multipasses) {
                requireState(gateway.snapshot(target.get("id")), "state_id", "collimationReady");
            }
            e.setVariable("outcome", "SUCCEEDED");
        } catch (Exception ex) {
            e.setVariable("failureReason", ex.getMessage());
            throw new BpmnError("DEVICE_ERROR", ex.getMessage());
        }
    }

    private void requireInitializationState(List<Map<String,String>> targets, String expected) {
        if (targets == null || targets.isEmpty()) throw new IllegalStateException("初始化目标集合为空");
        for (var target : targets) {
            JsonNode snapshot = gateway.snapshot(target.get("id"));
            if (!"ready".equals(snapshot.path("outer_state").asText())
                    || !expected.equals(snapshot.path("state_id").asText())
                    || snapshot.hasNonNull("active_command_id"))
                throw new IllegalStateException(target.get("id") + " 未达到初始化目标状态：ready/" + expected);
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
