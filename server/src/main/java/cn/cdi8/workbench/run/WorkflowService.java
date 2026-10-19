package cn.cdi8.workbench.run;

import cn.cdi8.workbench.integration.tango.TangoGateway;

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
    private final ManagementService management;
    private final RepositoryService repository;
    private final HistoryService history;
    private final TangoGateway gateway;
    private final ObjectMapper json;
    private final RunPolicies runPolicies;
    private final List<ExecutionPolicy> executionPolicies;
    private final String commandTimeout;
    private final String workflowTimeout;
    private final Set<String> changed = ConcurrentHashMap.newKeySet();
    private final Map<String,Long> lastQuery = new HashMap<>();

    public WorkflowService(RuntimeService runtime, RepositoryService repository, HistoryService history, ManagementService management,
                           TangoGateway gateway, ObjectMapper json, RunPolicies runPolicies, List<ExecutionPolicy> executionPolicies,
                           @Value("${control.command-timeout}") String commandTimeout,
                           @Value("${control.workflow-timeout}") String workflowTimeout) {
        this.management=management; this.runtime = runtime; this.repository = repository; this.history = history;
        this.gateway = gateway; this.json = json;
        this.runPolicies = runPolicies; this.executionPolicies = List.copyOf(executionPolicies);
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
        return definitionXml(definition);
    }

    public String runXml(String id) {
        var process = history.createHistoricProcessInstanceQuery().processInstanceId(id).singleResult();
        if (process == null) throw new IllegalArgumentException("流程不存在");
        return definitionXml(repository.getProcessDefinition(process.getProcessDefinitionId()));
    }

    private String definitionXml(org.flowable.engine.repository.ProcessDefinition definition) {
        try (var input = repository.getResourceAsStream(definition.getDeploymentId(), definition.getResourceName())) {
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException ex) { throw new IllegalStateException(ex); }
    }

    public synchronized Map<String,Object> start(String key, List<String> sources) {
        RunPolicy policy = runPolicies.requireBuiltin(key);
        if (!gateway.connected()) throw new IllegalStateException("请先连接 Tango 事件适配服务");
        if (runtime.createProcessInstanceQuery().count() > 0) throw new IllegalStateException("已有流程正在运行");
        Map<String,Object> variables = policy.variables(key, sources == null ? policy.defaultSources(key) : sources);
        variables.put("commandTimeout", commandTimeout);
        variables.put("workflowTimeout", workflowTimeout);
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
                        String condition = attribute(execution,"condition");
                        if (condition != null) {
                            if (declaredCondition(pid,attribute(execution,"collection"),condition)) {
                                for (var policy : executionPolicies) {
                                    var updates = policy.conditionSatisfied(process.getProcessDefinitionKey(), runtime.getVariables(pid), condition);
                                    if (!updates.isEmpty()) runtime.setVariables(pid, updates);
                                }
                                if("true".equals(attribute(execution,"maintain"))) {
                                    @SuppressWarnings("unchecked") var previous=(List<Map<String,String>>)runtime.getVariable(pid,"conditionGuards");
                                    var guards=new ArrayList<Map<String,String>>(previous==null?List.of():previous);
                                    guards.add(Map.of("condition",condition,"collection",Objects.toString(attribute(execution,"collection"),"contextTargets")));
                                    runtime.setVariable(pid,"conditionGuards",guards);
                                }
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

    public synchronized Map<String,Object> startDefinition(String definitionId,Map<String,Object> variables) {
        if(runtime.createProcessInstanceQuery().count()>0)throw new IllegalStateException("已有流程正在运行");
        Map<String,Object> snapshots=new LinkedHashMap<>();
        for(var device:gateway.catalog())snapshots.put(device.path("id").asText(),json.convertValue(device,Map.class));
        variables.put("initialDeviceSnapshots",snapshots);
        var process=runtime.startProcessInstanceById(definitionId,UUID.randomUUID().toString(),variables);
        return view(process.getId());
    }

    private String attribute(Execution execution,String name) {
        var instance=runtime.createProcessInstanceQuery().processInstanceId(execution.getProcessInstanceId()).singleResult();
        if(instance==null || execution.getActivityId()==null)return null;
        var element=repository.getBpmnModel(instance.getProcessDefinitionId()).getFlowElement(execution.getActivityId());
        return element==null?null:element.getAttributeValue("https://cdi8.cn/workbench",name);
    }

    private boolean declaredCondition(String pid,String collection,String condition) {
        var devices=targets(pid,collection==null?"contextTargets":collection);
        return !devices.isEmpty() && devices.stream().allMatch(d->{
            var snapshot=gateway.cached(d.get("id"));
            return snapshot!=null && snapshot.path("conditions").path(condition).asBoolean();
        });
    }

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
            // Before sending recheck registered policies and declared guards. A
            // command ID is stable across a lost response or controller restart.
            String lost = processIssue(execution.getProcessInstanceId());
            if (lost != null) { stop(execution.getProcessInstanceId(), lost); return; }
            result = sent ? gateway.result(device, id) : gateway.execute(device, id, action, Map.of());
            if (!sent) runtime.setVariableLocal(eid, "sent", true);
        } catch (TangoGateway.GatewayException ex) {
            if (ex.status() != 409) throw ex;
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
    private String processIssue(String pid) {
        for (var device : targets(pid, "allComponents")) {
            if (gateway.cached(device.get("id")) == null) return device.get("id") + " 设备连接中断";
        }
        var process = runtime.createProcessInstanceQuery().processInstanceId(pid).singleResult();
        if (process == null) return null;
        Map<String,Object> variables = runtime.getVariables(pid);
        for (var policy : executionPolicies) {
            String issue = policy.issue(process.getProcessDefinitionKey(), variables);
            if (issue != null) return issue;
        }
        @SuppressWarnings("unchecked") var guards = (List<Map<String,String>>)variables.get("conditionGuards");
        if (guards != null) for (var guard : guards) {
            if (!declaredCondition(pid, guard.get("collection"), guard.get("condition")))
                return "持续条件失效：" + guard.get("condition");
        }
        return null;
    }

    public synchronized void stop(String pid, String reason) {
        String message = "StopWorkflow";
        var subscriptions = runtime.createEventSubscriptionQuery().processInstanceId(pid).eventName(message).list();
        if (subscriptions.isEmpty()) {
            if(runtime.createProcessInstanceQuery().processInstanceId(pid).count()==0)throw new IllegalStateException("流程已结束");
            runtime.setVariable(pid,"failureReason",reason);runtime.setVariable(pid,"outcome","FAILED");
            runtime.deleteProcessInstance(pid,reason);return;
        }
        runtime.setVariable(pid, "failureReason", reason);
        runtime.messageEventReceived(message, subscriptions.get(0).getExecutionId());
    }

    public List<Map<String,Object>> recent() {
        return history.createHistoricProcessInstanceQuery().excludeSubprocesses(true).orderByProcessInstanceStartTime().desc().listPage(0,20).stream()
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
        value.put("outcome", process.getEndTime()!=null && "RUNNING".equals(vars.getOrDefault("outcome","RUNNING"))
            ? (process.getDeleteReason()==null?"SUCCEEDED":"FAILED") : vars.getOrDefault("outcome","RUNNING"));
        value.put("configuration",vars.get("workbenchConfiguration"));
        value.put("modelVersions",vars.get("modelVersions"));
        value.put("initialDeviceSnapshots",vars.get("initialDeviceSnapshots"));
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
        List<Map<String,Object>> waits=new ArrayList<>();
        for(var execution:active) {
            String condition=attribute(execution,"condition");
            if(condition!=null) {
                String collection=Objects.toString(attribute(execution,"collection"),"contextTargets");
                Map<String,Object> wait=new LinkedHashMap<>();wait.put("activity",execution.getActivityId());wait.put("condition",condition);
                wait.put("collection",collection);wait.put("met",declaredCondition(pid,collection,condition));
                wait.put("devices",targets(pid,collection));
                var times=activities.stream().filter(a->Objects.equals(a.get("id"),execution.getActivityId())&&a.get("ended")==null).findFirst();
                wait.put("started",times.map(a->a.get("started")).orElse(null));waits.add(wait);
            }
        }
        value.put("waits",waits);
        value.put("timers",management.createTimerJobQuery().processInstanceId(pid).list().stream().map(t->Map.of("due",t.getDuedate(),"id",t.getId())).toList());
        Map<String,Map<String,Object>> byExecution = new LinkedHashMap<>();
        for (var variable : history.createHistoricVariableInstanceQuery().processInstanceId(pid).list()) {
            if (Set.of("commandId","commandDevice","commandAction","commandNode","sent","commandResult","expectedState").contains(variable.getVariableName()))
                byExecution.computeIfAbsent(variable.getExecutionId(), ignored -> new LinkedHashMap<>()).put(variable.getVariableName(), variable.getValue());
        }
        value.put("commands", new ArrayList<>(byExecution.values().stream().filter(row -> row.containsKey("commandId")).toList()));
        Map<String, Object> participants = new LinkedHashMap<>();
        var participantPolicy = runPolicies.find(process.getProcessDefinitionKey());
        if (participantPolicy.isPresent()) {
            for (String collection : participantPolicy.get().participantCollections(process.getProcessDefinitionKey())) {
                if (vars.get(collection) instanceof List<?> targets) for (Object target : targets)
                    if (target instanceof Map<?,?> item && item.get("id") != null)
                        participants.put(item.get("id").toString(), target);
            }
        }
        if (participantPolicy.isEmpty() || Boolean.TRUE.equals(vars.get("workbench"))) {
            for (Object entry : vars.values()) if (entry instanceof List<?> targets) for (Object target : targets)
                if (target instanceof Map<?,?> item && item.get("id") != null && item.get("component") != null)
                    participants.put(item.get("id").toString(), target);
        }
        value.put("participants", new ArrayList<>(participants.values()));
        value.put("children", history.createHistoricProcessInstanceQuery().superProcessInstanceId(pid)
            .orderByProcessInstanceStartTime().asc().list().stream().map(child -> view(child.getId())).toList());
        return value;
    }
}
