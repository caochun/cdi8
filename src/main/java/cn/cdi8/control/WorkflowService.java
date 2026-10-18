package cn.cdi8.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.flowable.engine.*;
import org.flowable.engine.runtime.Execution;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Small coordinator: Flowable owns execution and history; Tango owns devices. */
@Service
public class WorkflowService {
    private final RuntimeService runtime;
    private final RepositoryService repository;
    private final HistoryService history;
    private final TangoGateway gateway;
    private final ObjectMapper json;
    private final String commandTimeout;
    private final String workflowTimeout;
    private final Set<String> changed = ConcurrentHashMap.newKeySet();
    private final Map<String,Long> lastQuery = new HashMap<>();

    public WorkflowService(RuntimeService runtime, RepositoryService repository, HistoryService history,
                           TangoGateway gateway, ObjectMapper json,
                           @Value("${control.command-timeout}") String commandTimeout,
                           @Value("${control.workflow-timeout}") String workflowTimeout) {
        this.runtime = runtime; this.repository = repository; this.history = history;
        this.gateway = gateway; this.json = json;
        this.commandTimeout = commandTimeout; this.workflowTimeout = workflowTimeout;
    }

    @EventListener
    public void event(TangoGateway.Notice event) {
        changed.add(event.payload().path("device_id").asText());
    }

    public List<Map<String,Object>> definitions() {
        return repository.createProcessDefinitionQuery().latestVersion().list().stream()
            .map(d -> Map.<String,Object>of("key", d.getKey(), "name", d.getName())).toList();
    }

    public String xml(String key) {
        var definition = repository.createProcessDefinitionQuery().processDefinitionKey(key).latestVersion().singleResult();
        if (definition == null) throw new IllegalArgumentException("流程不存在");
        try (var input = repository.getResourceAsStream(definition.getDeploymentId(), definition.getResourceName())) {
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException ex) { throw new IllegalStateException(ex); }
    }

    public synchronized Map<String,Object> start(String key, List<String> sources) {
        if (!Set.of("component_initialization", "parameter_dispatch", "optical_frontend_preparation", "formal_emission", "post_emission_processing", "emission_task_lifecycle").contains(key))
            throw new IllegalArgumentException("未知流程");
        if (!gateway.connected()) throw new IllegalStateException("请先连接 Tango 事件适配服务");
        if (runtime.createProcessInstanceQuery().count() > 0) throw new IllegalStateException("已有流程正在运行");
        List<String> selectedSources = sources == null ? List.of() : sources;
        boolean requiresSources = Set.of("optical_frontend_preparation", "formal_emission", "post_emission_processing", "emission_task_lifecycle").contains(key);
        boolean parameterDispatch = key.equals("parameter_dispatch");
        boolean preparation = key.equals("optical_frontend_preparation");
        boolean formalEmission = key.equals("formal_emission");
        boolean postEmission = key.equals("post_emission_processing");
        if (!Set.of("seed", "shg").containsAll(selectedSources)
                || (requiresSources && selectedSources.isEmpty()))
            throw new IllegalArgumentException("请选择至少一类光源");
        List<Map<String,String>> all = new ArrayList<>(), light = new ArrayList<>(), seed = new ArrayList<>(), shg = new ArrayList<>(), preamp = new ArrayList<>(), multipass = new ArrayList<>(), context = new ArrayList<>();
        for (var device : gateway.catalog()) {
            String component = device.path("component").asText();
            String id = device.path("id").asText();
            if (!Set.of("seed","shg","preamp","multipass","context").contains(component)) continue;
            if (!id.matches("[A-Za-z0-9_-]{1,128}")) throw new IllegalArgumentException("非法设备编号");
            if (!device.path("connected").asBoolean() || gateway.cached(id) == null) throw new IllegalStateException("设备未连接：" + id);
            JsonNode state = gateway.snapshot(id);
            if (state.hasNonNull("active_command_id")) throw new IllegalStateException("设备仍有任务：" + id);
            var target = Map.of("id", id, "component", component);
            if (component.equals("context")) context.add(target);
            else {
                all.add(target);
                if (component.equals("seed")) seed.add(target);
                if (component.equals("shg")) shg.add(target);
                if (component.equals("preamp")) preamp.add(target);
                if (component.equals("multipass")) multipass.add(target);
                if (selectedSources.contains(component)) light.add(target);
                String model = state.path("model").asText();
                String expectedModel = Map.of("seed","seed-source-reference-v2","shg","shg-injection-hierarchical-v2",
                    "preamp","preamp-hierarchical-v3","multipass","multipass-hierarchical-v3").get(component);
                if (!model.equals(expectedModel)) throw new IllegalStateException("组件模型不匹配：" + id);
                if (preparation) {
                    Set<String> ready = component.equals("seed")
                        ? Set.of("configured","parameterOk","standbyReady")
                        : Set.of("parameterOk");
                    if (!state.path("outer_state").asText().equals("ready") || !ready.contains(state.path("state_id").asText()))
                        throw new IllegalStateException(id + " 尚未具备发射准备资格，请先完成参数下发");
                } else if (formalEmission) {
                    boolean valid = switch (component) {
                        case "seed", "shg" -> selectedSources.contains(component)
                            ? "emitting".equals(state.path("outer_state").asText())
                            : "ready".equals(state.path("outer_state").asText())
                                && Set.of("configured", "parameterOk", "standbyReady").contains(state.path("state_id").asText());
                        case "preamp" -> "wakeup".equals(state.path("outer_state").asText())
                            && "fineLoopOk".equals(state.path("state_id").asText());
                        case "multipass" -> "collimated".equals(state.path("outer_state").asText())
                            && "collimationReady".equals(state.path("state_id").asText());
                        default -> true;
                    };
                    if (!valid || state.hasNonNull("active_command_id"))
                        throw new IllegalStateException(id + " 尚未具备正式发射资格，请先完成发射准备");
                } else if (postEmission) {
                    boolean valid = switch (component) {
                        case "seed", "shg" -> selectedSources.contains(component)
                            ? "emitting".equals(state.path("outer_state").asText())
                            : "ready".equals(state.path("outer_state").asText());
                        case "preamp" -> "energyMeterCleared".equals(state.path("state_id").asText());
                        case "multipass" -> "collimated".equals(state.path("outer_state").asText())
                            && "collimationReady".equals(state.path("state_id").asText());
                        default -> true;
                    };
                    if (!valid || state.hasNonNull("active_command_id"))
                        throw new IllegalStateException(id + " 尚未具备发射后处理资格，请先完成正式发射");
                } else if (parameterDispatch) {
                    Set<String> ready = component.equals("seed")
                        ? Set.of("qualified","configured","standbyReady")
                        : Set.of("functionReady");
                    if (!state.path("outer_state").asText().equals("ready") || !ready.contains(state.path("state_id").asText()))
                        throw new IllegalStateException(id + " 尚未完成组件初始化，不能进行参数下发");
                } else if (!state.path("outer_state").asText().equals("notReady")) {
                    String outerLabel = state.path("outer_state_label").asText(state.path("outer_state").asText());
                    String stateId = state.path("state_id").asText();
                    throw new IllegalStateException(id + " 当前为 " + outerLabel + "/" + stateId
                        + "，初始化只能从未就绪状态开始；请先执行下电或复位");
                }
            }
        }
        if (all.isEmpty()) throw new IllegalStateException("没有可控组件");
        boolean requiresAllModeledComponents = Set.of("component_initialization", "parameter_dispatch", "formal_emission", "post_emission_processing", "emission_task_lifecycle").contains(key);
        if (requiresAllModeledComponents && (seed.isEmpty() || shg.isEmpty() || preamp.isEmpty() || multipass.isEmpty()))
            throw new IllegalStateException("初始化需要种子源、二倍频、再生与双程放大、多程放大四类组件");
        if (requiresSources && (preamp.isEmpty() || multipass.isEmpty() || light.isEmpty() || context.size()!=1))
            throw new IllegalStateException("准备流程需要所选光源、预放、多程及一个同步/环境条件设备");
        for (String source : selectedSources) if (light.stream().noneMatch(d -> d.get("component").equals(source)))
            throw new IllegalStateException("所选光源不存在：" + source);
        Map<String,Object> variables = new HashMap<>();
        // Initialization deliberately uses a dedicated target collection. It currently
        // contains exactly the four modeled component types; context devices are kept
        // separate for the preparation workflow and never enter initialization gates.
        variables.put("allComponents", all);
        variables.put("seedTargets", seed); variables.put("shgTargets", shg);
        variables.put("sourceTargets", light);
        variables.put("preampTargets", preamp); variables.put("multipassTargets", multipass); variables.put("contextTargets", context);
        variables.put("commandTimeout", commandTimeout); variables.put("workflowTimeout", workflowTimeout);
        variables.put("outcome", "RUNNING");
        var process = runtime.startProcessInstanceByKey(key, UUID.randomUUID().toString(), variables);
        return view(process.getId());
    }

    @Scheduled(fixedDelayString = "${control.pump-delay:200}")
    public synchronized void pump() {
        if (!gateway.connected()) return; // disconnected values cannot release a gate
        Set<String> dirty = new HashSet<>(changed); changed.removeAll(dirty);
        for (var process : runtime.createProcessInstanceQuery().list()) {
            String pid = process.getId();
            try {
                String lost = processIssue(pid);
                if (lost != null) { stop(pid, lost); continue; }
                for (var execution : runtime.createExecutionQuery().processInstanceId(pid).list()) {
                    try {
                        String activity = execution.getActivityId();
                        if (activity == null) continue;
                        String condition = WAIT_CONDITIONS.get(activity);
                        if (condition != null) {
                            if (condition(pid, condition)) {
                                if (condition.equals("safety_permit")) runtime.setVariable(pid, "safetyRequired", true);
                                if (condition.equals("collimation_environment_ready")) runtime.setVariable(pid, "environmentRequired", true);
                                runtime.trigger(execution.getId());
                            }
                        } else if (runtime.hasVariableLocal(execution.getId(), "commandId")
                                && (runtime.hasVariableLocal(execution.getId(), "componentCommand") || activity.endsWith("_wait"))) {
                            processCommand(execution, dirty);
                        }
                    } catch (RuntimeException ex) {
                        // Completing a parallel branch can remove executions that
                        // were present in this pump's initial query. They are
                        // harmless stale handles, not communication failures.
                        if (!executionStillExists(execution.getId()) && isMissingExecution(ex)) continue;
                        throw ex;
                    }
                }
            } catch (Exception ex) {
                // A failed read holds execution; persistent communication errors
                // are bounded by BPMN timers and shown in the view.
                if (runtime.createProcessInstanceQuery().processInstanceId(pid).count() > 0)
                    runtime.setVariable(pid, "communicationError", Objects.toString(ex.getMessage(), ex.toString()));
            }
        }
        lastQuery.keySet().removeIf(id -> runtime.createExecutionQuery().executionId(id).count() == 0);
    }

    private boolean executionStillExists(String id) {
        return runtime.createExecutionQuery().executionId(id).count() > 0;
    }

    private boolean isMissingExecution(RuntimeException ex) {
        for (Throwable current = ex; current != null; current = current.getCause()) {
            String type = current.getClass().getSimpleName();
            String message = current.getMessage();
            if (type.contains("ObjectNotFound")
                    || (message != null && (message.contains("doesn't exist") || message.contains("does not exist"))))
                return true;
        }
        return false;
    }

    private static final Map<String,String> WAIT_CONDITIONS = Map.ofEntries(
        Map.entry("wait_safety", "safety_permit"),
        Map.entry("wait_environment", "collimation_environment_ready"),
        Map.entry("wait_diagnosis", "diagnosis_acquisition_ready"),
        Map.entry("wait_crystal", "crystal_pose_ready"),
        Map.entry("wait_charge_prepare", "charge_preparation_ready"),
        Map.entry("wait_preamp_single", "preamp_single_recipe_ready"),
        Map.entry("wait_measure_single", "measurement_single_recipe_ready"),
        Map.entry("wait_target_measure", "target_measurement_ready"),
        Map.entry("wait_switch_charge", "switch_charge_ready"),
        Map.entry("wait_charge_complete", "charge_complete"),
        Map.entry("wait_music_stop", "warning_music_stopped"),
        Map.entry("wait_countdown", "trigger_countdown_started"),
        Map.entry("wait_pump_trigger", "pump_trigger_ready"),
        Map.entry("wait_switch_trigger", "switch_trigger_ready"),
        Map.entry("wait_sync_trigger", "synchronized_triggered"),
        Map.entry("wait_diagnostic_postprocess", "diagnostic_postprocess_ready"),
        Map.entry("wait_pump_data", "pump_data_collected"),
        Map.entry("wait_pump_standby", "pump_standby_ready"),
        Map.entry("wait_preionization_prepared", "preionization_prepared"),
        Map.entry("wait_preionization_music_on", "preionization_music_started"),
        Map.entry("wait_preionization_charge", "preionization_charge_complete"),
        Map.entry("wait_preionization_music", "preionization_music_stopped"),
        Map.entry("wait_preionization_countdown", "preionization_countdown_started"),
        Map.entry("wait_preionization_pump_trigger", "preionization_pump_trigger_ready"),
        Map.entry("wait_preionization_sync", "preionization_sync_triggered"),
        Map.entry("wait_preionization_data", "preionization_data_collected"),
        Map.entry("wait_preionization_standby", "preionization_standby_ready"),
        Map.entry("wait_warning_light_off", "warning_light_off"),
        Map.entry("wait_laser_parameter", "laser_parameter_collected"),
        Map.entry("wait_target_analysis", "target_analysis_ready"),
        Map.entry("wait_target_retracted", "target_retracted"),
        Map.entry("wait_switch_waveform", "switch_waveform_collected"),
        Map.entry("wait_switch_standby", "switch_standby_ready"),
        Map.entry("wait_slab_purge", "slab_purge_complete"),
        Map.entry("wait_shielding_door", "shielding_door_open")
    );

    private void processCommand(Execution execution, Set<String> dirty) throws Exception {
        String eid = execution.getId();
        var variables = runtime.getVariablesLocal(eid);
        String id = (String)variables.get("commandId"), device = (String)variables.get("commandDevice"), action = (String)variables.get("commandAction");
        long now = System.currentTimeMillis();
        boolean sent = Boolean.TRUE.equals(variables.get("sent"));
        long elapsed = now - lastQuery.getOrDefault(eid, 0L);
        boolean event = dirty.contains(device);
        if (elapsed < (sent ? 5000 : 1000) && !event) return;
        lastQuery.put(eid, now);
        JsonNode result;
        try {
            // Before sending recheck source, preamp and external guards. A
            // command ID is stable across a lost response or controller restart.
            String lost = processIssue(execution.getProcessInstanceId());
            if (lost != null) { stop(execution.getProcessInstanceId(), lost); return; }
            result = sent ? gateway.result(device, id) : gateway.execute(device, id, action, Map.of());
            if (!sent) runtime.setVariableLocal(eid, "sent", true);
        } catch (TangoGateway.GatewayException ex) {
            if (ex.status != 409) throw ex;
            result = json.valueToTree(Map.of("command_id", id, "action", action, "status", "rejected", "reason", ex.getMessage()));
        }
        String status = result.path("status").asText();
        if (Set.of("succeeded","failed","interrupted","rejected","cancelled").contains(status)) {
            runtime.setVariableLocal(eid, "commandResult", result.toString());
            runtime.setVariable(execution.getProcessInstanceId(), "communicationError", "");
            // The custom component-command activity verifies the terminal
            // result in its trigger callback and then leaves the activity.
            // Legacy receiveTask flows use the same trigger call and verify
            // in their following service task.
            try {
                runtime.trigger(eid);
            } catch (RuntimeException ex) {
                // A BpmnError raised by verification while a triggerable
                // activity is being resumed may escape the direct trigger
                // command before Flowable evaluates the enclosing boundary.
                // Route it through the workflow stop boundary so every
                // parallel branch is cancelled consistently.
                String pid = execution.getProcessInstanceId();
                if (runtime.createProcessInstanceQuery().processInstanceId(pid).count() > 0) {
                    String reason = Objects.toString(ex.getMessage(), "设备命令校验失败");
                    try { stop(pid, reason); } catch (RuntimeException ignored) { }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String,String>> targets(String pid, String name) {
        Object value = runtime.getVariable(pid, name);
        return value == null ? List.of() : (List<Map<String,String>>)value;
    }
    private boolean condition(String pid, String name) {
        var devices = targets(pid, "contextTargets");
        if (devices.size()!=1) return false;
        JsonNode snapshot = gateway.cached(devices.get(0).get("id"));
        return snapshot != null && snapshot.path("conditions").path(name).asBoolean();
    }
    private String processIssue(String pid) {
        for (var device : targets(pid, "allComponents")) {
            JsonNode snapshot = gateway.cached(device.get("id"));
            if (snapshot == null) return device.get("id") + " 设备连接中断";
            if ("fault".equals(snapshot.path("outer_state").asText()))
                return device.get("id") + " 设备异常，停止流程放行";
        }
        if (Boolean.TRUE.equals(runtime.getVariable(pid, "safetyRequired")) && !condition(pid, "safety_permit"))
            return "安全许可失效或无法确认";
        if (Boolean.TRUE.equals(runtime.getVariable(pid, "environmentRequired")) && !condition(pid, "collimation_environment_ready"))
            return "准直环境条件失效或无法确认";
        if (Boolean.TRUE.equals(runtime.getVariable(pid, "repeatRequired")) && !condition(pid, "preamp_repeat_ready"))
            return "同步预放重频模式失效或无法确认";
        if (Boolean.TRUE.equals(runtime.getVariable(pid, "sourcesRequired")))
            for (var device : targets(pid, "sourceTargets")) {
                JsonNode state = gateway.cached(device.get("id"));
                if (state == null || !"emitting".equals(state.path("outer_state").asText())) return device.get("id") + " 供光资格失效或无法确认";
            }
        if (Boolean.TRUE.equals(runtime.getVariable(pid, "wakeupRequired")))
            for (var device : targets(pid, "preampTargets")) {
                JsonNode state = gateway.cached(device.get("id"));
                if (state == null || !"wakeup".equals(state.path("outer_state").asText())) return device.get("id") + " 预放唤醒资格失效或无法确认";
            }
        return null;
    }

    public synchronized void stop(String pid, String reason) {
        String message = "StopWorkflow";
        var subscriptions = runtime.createEventSubscriptionQuery().processInstanceId(pid).eventName(message).list();
        if (subscriptions.isEmpty()) throw new IllegalStateException("流程已结束");
        runtime.setVariable(pid, "failureReason", reason);
        runtime.messageEventReceived(message, subscriptions.get(0).getExecutionId());
    }

    public List<Map<String,Object>> recent() {
        return history.createHistoricProcessInstanceQuery().orderByProcessInstanceStartTime().desc().listPage(0,20).stream()
            .map(p -> {
                Map<String,Object> value = new LinkedHashMap<>();
                value.put("id", p.getId()); value.put("key", p.getProcessDefinitionKey());
                value.put("name", p.getProcessDefinitionName());
                value.put("started", p.getStartTime()); value.put("ended", p.getEndTime()); return value;
            }).toList();
    }

    public synchronized Map<String,Object> view(String pid) {
        var process = history.createHistoricProcessInstanceQuery().processInstanceId(pid).singleResult();
        if (process == null) throw new IllegalArgumentException("流程不存在");
        Map<String,Object> value = new LinkedHashMap<>();
        value.put("id", pid); value.put("key", process.getProcessDefinitionKey());
        value.put("name", process.getProcessDefinitionName()); value.put("started", process.getStartTime());
        value.put("ended", process.getEndTime());
        Map<String,Object> vars = new HashMap<>();
        for (var variable : history.createHistoricVariableInstanceQuery().processInstanceId(pid).list())
            if (variable.getExecutionId().equals(pid)) vars.put(variable.getVariableName(), variable.getValue());
        value.put("outcome", vars.getOrDefault("outcome", "RUNNING"));
        value.put("failureReason", vars.get("failureReason")); value.put("communicationError", vars.get("communicationError"));
        var active = runtime.createExecutionQuery().processInstanceId(pid).list();
        value.put("activeActivities", active.stream().map(Execution::getActivityId).filter(Objects::nonNull).distinct().toList());
        List<Map<String,Object>> activities = new ArrayList<>();
        for (var item : history.createHistoricActivityInstanceQuery().processInstanceId(pid).orderByHistoricActivityInstanceStartTime().asc().list()) {
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("id", item.getActivityId()); row.put("name", item.getActivityName()); row.put("type", item.getActivityType());
            row.put("started", item.getStartTime()); row.put("ended", item.getEndTime()); row.put("cancelled", item.getDeleteReason()!=null);
            activities.add(row);
        }
        value.put("activities", activities);
        Map<String,Map<String,Object>> byExecution = new LinkedHashMap<>();
        for (var variable : history.createHistoricVariableInstanceQuery().processInstanceId(pid).list()) {
            if (Set.of("commandId","commandDevice","commandAction","commandNode","sent","commandResult","expectedState").contains(variable.getVariableName()))
                byExecution.computeIfAbsent(variable.getExecutionId(), ignored -> new LinkedHashMap<>()).put(variable.getVariableName(), variable.getValue());
        }
        value.put("commands", new ArrayList<>(byExecution.values().stream().filter(row -> row.containsKey("commandId")).toList()));
        return value;
    }
}
