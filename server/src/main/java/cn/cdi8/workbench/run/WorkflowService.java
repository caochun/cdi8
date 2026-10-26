package cn.cdi8.workbench.run;

import cn.cdi8.workbench.facility.FacilityStateRuntime;
import cn.cdi8.workbench.facility.FacilityCompletionRuntime;
import cn.cdi8.workbench.facility.FacilityDependencyRuntime;
import cn.cdi8.workbench.integration.tango.TangoGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.flowable.engine.*;
import org.flowable.engine.runtime.Execution;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** New-model coordinator: Flowable owns the operational process and the facility runtime owns facts. */
@Service
public class WorkflowService {
    private static final Set<String> TERMINAL = Set.of("succeeded", "failed", "interrupted", "rejected", "cancelled");
    private final RuntimeService runtime;
    private final ManagementService management;
    private final RepositoryService repository;
    private final HistoryService history;
    private final TangoGateway gateway;
    private final ObjectMapper json;
    private final FacilityStateRuntime facilityStates;
    private final ShotRunRuntime shots;
    private final ComponentCommandTasks tasks;
    private final FacilityCompletionRuntime completions;
    private final FacilityDependencyRuntime dependencies;
    private final String commandTimeout;
    private final String workflowTimeout;
    private final Set<String> changed = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> lastQuery = new HashMap<>();
    private final AtomicBoolean facilityChanged=new AtomicBoolean();
    private final Map<String,Long> lastCompletionQuery=new HashMap<>();
    private volatile String observationFailure;
    private String activeFacilityRun;

    public WorkflowService(RuntimeService runtime, RepositoryService repository, HistoryService history,
                           ManagementService management, TangoGateway gateway, ObjectMapper json,
                           FacilityStateRuntime facilityStates, ShotRunRuntime shots, ComponentCommandTasks tasks, FacilityCompletionRuntime completions, FacilityDependencyRuntime dependencies,
                           @Value("${control.command-timeout}") String commandTimeout,
                           @Value("${control.workflow-timeout}") String workflowTimeout) {
        this.runtime = runtime;
        this.repository = repository;
        this.history = history;
        this.management = management;
        this.gateway = gateway;
        this.json = json;
        this.facilityStates = facilityStates;
        this.shots = shots;
        this.tasks = tasks;
        this.completions = completions;
        this.dependencies = dependencies;
        this.commandTimeout = commandTimeout;
        this.workflowTimeout = workflowTimeout;
    }

    @EventListener
    public void event(TangoGateway.Notice event) {
        String type=event.payload().path("type").asText();
        if(Set.of("connection_lost","state_revision_rollback").contains(type)
            || (event.payload().has("device_id")&&!event.payload().path("connected").asBoolean()))
            observationFailure=event.payload().path("reason").asText("设备观测失效："+type);
        String device = event.payload().path("device_id").asText("");
        if (!device.isBlank()) changed.add(device);
    }

    @EventListener
    public void facilityChanged(FacilityStateRuntime.Changed event) {
        facilityChanged.set(true);
    }

    public List<Map<String, Object>> definitions() {
        return repository.createProcessDefinitionQuery().latestVersion().list().stream()
            .map(d -> Map.<String, Object>of("key", d.getKey(), "name", d.getName(), "version", d.getVersion()))
            .toList();
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
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Scheduled(fixedDelayString = "${control.pump-delay:200}")
    public synchronized void pump() {
        boolean changedFacility=facilityChanged.getAndSet(false);
        Set<String> dirty = new HashSet<>(changed);
        changed.removeAll(dirty);
        for (var process : runtime.createProcessInstanceQuery().list()) {
            String pid = process.getId();
            try {
                if(!"facility-v1".equals(runtime.getVariable(pid,"runtimeChain"))) {
                    stop(pid,"该运行属于已删除的旧链路，请使用新设施模型重新创建运行");continue;
                }
                if(!pid.equals(activeFacilityRun)) {
                    String deployment=repository.getProcessDefinition(process.getProcessDefinitionId()).getDeploymentId();
                    String definitionXml=deployedResource(deployment,"gxlf-facility");
                    var definition=cn.cdi8.workbench.systemdefinition.SystemDefinitionModel.parse(definitionXml);
                    Map<String,String> components=new LinkedHashMap<>();
                    for(String type:definition.componentTypes()) {
                        try(var input=repository.getResourceAsStream(deployment,"facility-component-"+type+".scxml")) {
                            if(input==null)throw new IllegalStateException("运行缺少组件 SCXML："+type);
                            components.put(type,new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
                        }
                    }
                    facilityStates.activate(definitionXml,deployedResource(deployment,"gxlf-facility-binding"),
                        deployedResource(deployment,"gxlf-facility-state-projection"),
                        cn.cdi8.workbench.facility.FacilityComponentModels.archived(definition,components));
                    activeFacilityRun=pid;
                }
                String issue = processIssue(pid);
                if (issue != null) {
                    stop(pid, issue);
                    continue;
                }
                if (!gateway.connected()) {
                    runtime.setVariable(pid, "communicationError", "Tango 事件通道不可用");
                    continue;
                }
                for (var execution : runtime.createExecutionQuery().processInstanceId(pid).list()) {
                    try {
                        if (!executionStillExists(execution.getId())) continue;
                        if(runtime.hasVariableLocal(execution.getId(),FacilityCompletionRuntime.VARIABLE)) {
                            processCompletion(execution,changedFacility);
                        } else if (runtime.hasVariableLocal(execution.getId(), "commandId")
                                && runtime.hasVariableLocal(execution.getId(), "facilityAction")) {
                            processCommand(execution, dirty);
                        }
                    } catch (RuntimeException ex) {
                        if (!executionStillExists(execution.getId()) && isMissingExecution(ex)) continue;
                        throw ex;
                    }
                }
            } catch (Exception ex) {
                if (runtime.createProcessInstanceQuery().processInstanceId(pid).count() > 0)
                    runtime.setVariable(pid, "communicationError", Objects.toString(ex.getMessage(), ex.toString()));
            }
        }
        lastQuery.keySet().removeIf(id -> runtime.createExecutionQuery().executionId(id).count() == 0);
        lastCompletionQuery.keySet().removeIf(id -> !executionStillExists(id)
            || !runtime.hasVariableLocal(id,FacilityCompletionRuntime.VARIABLE));
        Set<String> watched=new HashSet<>();
        for(var execution:runtime.createExecutionQuery().list()) {
            Object command=runtime.getVariableLocal(execution.getId(),"commandId");
            if(command!=null)watched.add(command.toString());
        }
        dependencies.retain(watched);
    }

    private boolean executionStillExists(String id) {
        return runtime.createExecutionQuery().executionId(id).count() > 0;
    }

    private boolean isMissingExecution(RuntimeException ex) {
        for (Throwable current = ex; current != null; current = current.getCause()) {
            String type = current.getClass().getSimpleName();
            String message = current.getMessage();
            if (type.contains("ObjectNotFound") || (message != null &&
                    (message.contains("doesn't exist") || message.contains("does not exist")))) return true;
        }
        return false;
    }

    public synchronized Map<String, Object> startDefinition(String definitionId, Map<String, Object> variables) {
        if (runtime.createProcessInstanceQuery().count() > 0) throw new IllegalStateException("已有流程正在运行");
        if (!gateway.connected()) throw new IllegalStateException("请先连接 Tango 事件适配服务");
        Map<String, Object> initial = new HashMap<>(variables);
        Map<String, Object> snapshots = new LinkedHashMap<>();
        for (var device : gateway.catalog()) snapshots.put(device.path("id").asText(), json.convertValue(device, Map.class));
        initial.put("initialDeviceSnapshots", snapshots);
        initial.putIfAbsent("commandTimeout", commandTimeout);
        initial.putIfAbsent("workflowTimeout", workflowTimeout);
        initial.putIfAbsent("outcome", "RUNNING");
        initial.put("runStartedAt", System.currentTimeMillis());
        initial.put("runtimeChain","facility-v1");
        observationFailure=null;
        var process = runtime.startProcessInstanceById(definitionId, UUID.randomUUID().toString(), initial);
        activeFacilityRun=process.getId();
        return view(process.getId());
    }

    private String processIssue(String pid) {
        if(observationFailure!=null)return observationFailure;
        long start=((Number)runtime.getVariable(pid,"runStartedAt")).longValue();
        if(System.currentTimeMillis()-start>java.time.Duration.parse(runtime.getVariable(pid,"workflowTimeout").toString()).toMillis())
            return "流程等待超时";
        if(!gateway.connected())return "Tango 事件通道中断，设施观测失效";
        Object raw = runtime.getVariable(pid, "allComponents");
        if (raw instanceof List<?> values) for (Object item : values) {
            if (!(item instanceof Map<?, ?> device)) continue;
            String id = Objects.toString(device.get("id"), "");
            if (!id.isBlank() && gateway.cached(id) == null) return id + " 设备连接中断";
            JsonNode initial=json.valueToTree(runtime.getVariable(pid,"initialDeviceSnapshots"));
            if(gateway.cached(id)!=null && !initial.path(id).path("snapshot").path("source_id").asText().equals(gateway.cached(id).path("source_id").asText()))
                return id+" 设备已重启或被替换";
        }
        return null;
    }

    private void processCommand(Execution execution, Set<String> dirty) throws Exception {
        String eid = execution.getId();
        var variables = runtime.getVariablesLocal(eid);
        String id = Objects.toString(variables.get("commandId"), "");
        String device = Objects.toString(variables.get("commandDevice"), "");
        String action = Objects.toString(variables.get("commandAction"), "");
        long now = System.currentTimeMillis();
        boolean sent = Boolean.TRUE.equals(variables.get("sent"));
        boolean attempted = Boolean.TRUE.equals(variables.get("dispatchAttempted"));
        if(!checkDependencies(execution,sent||attempted,true))return;
        if(now-((Number)variables.get("commandStartedAt")).longValue()>
            java.time.Duration.parse(runtime.getVariable(execution.getProcessInstanceId(),"commandTimeout").toString()).toMillis()) {
            stop(execution.getProcessInstanceId(),"设备动作超时："+device+" / "+action); return;
        }
        long elapsed = now - lastQuery.getOrDefault(eid, 0L);
        if (elapsed < (sent || attempted ? 1000 : 500) && !dirty.contains(device)) return;
        lastQuery.put(eid, now);
        JsonNode result;
        try {
            Object parameters = variables.containsKey("commandParameters")
                ? json.readTree(variables.get("commandParameters").toString()) : Map.of();
            // Persist before crossing the network: a lost Execute response must never
            // cause a new Execute attempt. Reconcile through GetResult using the same id.
            if(!sent&&!attempted)runtime.setVariableLocal(eid,"dispatchAttempted",true);
            result = sent || attempted ? gateway.result(device, id) : gateway.execute(device, id, action, parameters);
            if (!sent && !attempted) runtime.setVariableLocal(eid, "sent", true);
        } catch (TangoGateway.GatewayException ex) {
            if (sent || attempted) {
                runtime.setVariableLocal(eid, "commandOutcomeUnknown", true);
                runtime.setVariable(pid(execution), "communicationError", ex.getMessage());
                return;
            }
            if (ex.status() == 409) {
                result = json.createObjectNode().put("command_id", id).put("action", action)
                    .put("status", "rejected").put("reason", ex.getMessage());
            } else {
                runtime.setVariableLocal(eid,"commandOutcomeUnknown",true);
                runtime.setVariable(pid(execution),"communicationError",ex.getMessage());
                return;
            }
        }
        if (result == null || !TERMINAL.contains(result.path("status").asText())) return;
        if(!id.equals(result.path("command_id").asText())||!action.equals(result.path("action").asText())) {
            stop(execution.getProcessInstanceId(),"命令结果关联不匹配"); return;
        }
        runtime.setVariableLocal(eid, "commandResult", result.toString());
        runtime.setVariableLocal(eid, "commandOutcomeUnknown", false);
        if(!checkDependencies(execution,true,false))return;
        variables=runtime.getVariablesLocal(eid);
        try { tasks.validateResult(variables); }
        catch(RuntimeException ex) {
            remember(execution.getProcessInstanceId(),variables,"failed",ex.getMessage());
            stop(execution.getProcessInstanceId(),ex.getMessage());return;
        }
        remember(execution.getProcessInstanceId(),variables,"succeeded","");
        Object dependency=variables.get(FacilityDependencyRuntime.VARIABLE);
        if(dependency!=null)rememberDependency(pid(execution),dependencies.read(dependency),"succeeded");
        runtime.setVariable(execution.getProcessInstanceId(), "communicationError", "");
        try {
            runtime.trigger(eid);
        } catch (RuntimeException ex) {
            if (runtime.createProcessInstanceQuery().processInstanceId(execution.getProcessInstanceId()).count() > 0)
                {
                    stop(execution.getProcessInstanceId(), Objects.toString(ex.getMessage(), "设备动作校验失败"));
                }
        }
    }

    private String pid(Execution execution) {
        return execution.getProcessInstanceId();
    }

    private boolean checkDependencies(Execution execution,boolean started,boolean reconcile) {
        Object raw=runtime.getVariableLocal(execution.getId(),FacilityDependencyRuntime.VARIABLE);
        if(raw==null)return true;
        var assessment=dependencies.check(raw,started,reconcile);
        runtime.setVariableLocal(execution.getId(),FacilityDependencyRuntime.VARIABLE,assessment.toString());
        if("valid".equals(assessment.path("status").asText()))return true;
        rememberDependency(pid(execution),assessment,"failed");
        stop(pid(execution),"能力输入依赖失效："+assessment.path("component").asText()+" / "+assessment.path("capability").asText()+" / "+assessment.path("reason").asText());
        return false;
    }

    private void rememberDependency(String pid,com.fasterxml.jackson.databind.node.ObjectNode assessment,String outcome) {
        Object raw=runtime.getVariable(pid,"dependencyHistory");
        Map<String,Object> ledger=raw instanceof Map<?,?>?new LinkedHashMap<>((Map<String,Object>)raw):new LinkedHashMap<>();
        assessment.put("outcome",outcome);
        ledger.put(assessment.path("id").asText(),json.convertValue(assessment,Map.class));
        runtime.setVariable(pid,"dependencyHistory",ledger);
        Object shot=runtime.getVariable(pid,"shotRun");
        if(shot!=null)runtime.setVariable(pid,"shotRun",shots.dependencyEvidence(shot,assessment));
    }

    private void processCompletion(Execution execution,boolean changedFacility) {
        String eid=execution.getId(),pid=execution.getProcessInstanceId();
        var wait=completions.read(runtime.getVariableLocal(eid,FacilityCompletionRuntime.VARIABLE));
        if(completions.expired(wait)) {
            stop(pid,"完成条件等待超时："+wait.path("condition").asText()+" / "+wait.path("reason").asText());return;
        }
        // Initial evaluation and periodic reconciliation cover already-true facts,
        // lost notifications and process-engine restarts. Events only shorten the wait.
        long now=System.currentTimeMillis();
        if(!changedFacility && now-lastCompletionQuery.getOrDefault(eid,0L)<1000)return;
        lastCompletionQuery.put(eid,now);
        wait=completions.evaluate(wait.toString(),true);
        runtime.setVariableLocal(eid,FacilityCompletionRuntime.VARIABLE,wait.toString());
        if(completions.expired(wait)) {
            stop(pid,"完成条件等待超时："+wait.path("condition").asText());return;
        }
        String issue=processIssue(pid);
        if(issue!=null){stop(pid,issue);return;}
        if(!wait.path("met").asBoolean())return;
        // This is evidence of a checked observation, not a claim of continuous safety.
        rememberCompletion(pid,wait,"succeeded","");
        try {
            runtime.trigger(eid);
            // A condition may become invalid between reconciliation and the callback.
            // Keep this activation waiting and invalidate its previous decision evidence.
            if(executionStillExists(eid) && runtime.hasVariableLocal(eid,FacilityCompletionRuntime.VARIABLE)) {
                var remaining=completions.read(runtime.getVariableLocal(eid,FacilityCompletionRuntime.VARIABLE));
                if(wait.path("id").equals(remaining.path("id")))
                    rememberCompletion(pid,remaining,"invalidated",remaining.path("reason").asText());
            }
        }catch(RuntimeException ex) {
            if(runtime.createProcessInstanceQuery().processInstanceId(pid).count()>0)
                stop(pid,Objects.toString(ex.getMessage(),"完成条件推进失败"));
        }
    }

    private void rememberCompletion(String pid,com.fasterxml.jackson.databind.node.ObjectNode wait,String status,String reason) {
        Object shot=runtime.getVariable(pid,"shotRun");
        if(shot!=null)runtime.setVariable(pid,"shotRun",shots.completionEvidence(shot,wait,status,reason));
    }

    private String deployedResource(String deployment,String model) {
        try(var input=repository.getResourceAsStream(deployment,"facility-model-"+model+".xml")) {
            if(input==null)throw new IllegalStateException("运行缺少设施模型："+model);
            return new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        }catch(java.io.IOException ex){throw new IllegalStateException(ex);}
    }

    private void remember(String pid, Map<String,Object> local, String status, String reason) {
        Map<String,Object> command=new LinkedHashMap<>();
        for(String name:List.of("commandId","commandDevice","commandAction","commandNode","expectedState",
            "sent","dispatchAttempted","commandOutcomeUnknown","commandResult",FacilityDependencyRuntime.VARIABLE))
            if(local.get(name)!=null)command.put(name,local.get(name));
        Object raw=runtime.getVariable(pid,"commandHistory");
        Map<String,Object> ledger=raw instanceof Map<?,?>?new LinkedHashMap<>((Map<String,Object>)raw):new LinkedHashMap<>();
        command.put("verificationStatus",status);
        ledger.put(command.get("commandId").toString(),command);
        runtime.setVariable(pid,"commandHistory",ledger);
        Object shot=runtime.getVariable(pid,"shotRun");
        if(shot!=null)runtime.setVariable(pid,"shotRun",shots.commandEvidence(shot,command,status,reason));
    }

    public synchronized void stop(String pid, String reason) { finish(pid, reason, false); }
    public synchronized void cancel(String pid, String reason) { finish(pid, reason, true); }

    private void finish(String pid, String reason, boolean cancelled) {
        if (runtime.createProcessInstanceQuery().processInstanceId(pid).count() == 0) return;
        List<Object> waits=new ArrayList<>();
        for(var execution:runtime.createExecutionQuery().processInstanceId(pid).list()) {
            var local=runtime.getVariablesLocal(execution.getId());
            if(local.get(FacilityDependencyRuntime.VARIABLE)!=null) {
                var assessment=dependencies.check(local.get(FacilityDependencyRuntime.VARIABLE),Boolean.TRUE.equals(local.get("dispatchAttempted")),false);
                rememberDependency(pid,assessment,cancelled?"cancelled":"failed");
                dependencies.release(assessment.path("id").asText());
                if(local.get("commandResult")!=null) {
                    local.put(FacilityDependencyRuntime.VARIABLE,assessment.toString());
                    try {tasks.validateResult(local);remember(pid,local,"succeeded","");}
                    catch(RuntimeException ex){remember(pid,local,"failed",ex.getMessage());}
                } else if(!Boolean.TRUE.equals(local.get("dispatchAttempted")))remember(pid,local,"blocked",reason);
            }
            if(local.get(FacilityCompletionRuntime.VARIABLE)!=null) {
                var wait=completions.read(local.get(FacilityCompletionRuntime.VARIABLE));
                wait.put("outcome",cancelled?"cancelled":"failed").put("ended",System.currentTimeMillis()).put("endReason",reason);
                waits.add(json.convertValue(wait,Map.class));
                rememberCompletion(pid,wait,"unknown".equals(wait.path("status").asText())?"unknown":"failed",reason);
            }
            if(local.get("commandId")!=null && local.get("commandResult")==null
                && (Boolean.TRUE.equals(local.get("dispatchAttempted"))||Boolean.TRUE.equals(local.get("sent")))) {
                local.put("commandOutcomeUnknown",true);
                remember(pid,local,"unknown",reason);
            }
        }
        runtime.setVariable(pid,"failureReason",reason);
        runtime.setVariable(pid,"finalCompletionWaits",waits);
        runtime.setVariable(pid,"outcome",cancelled?"CANCELLED":"FAILED");
        Object raw=runtime.getVariable(pid,"shotRun");
        if(raw!=null) {
            try {
                var run=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(raw.toString());
                run.put("stage",cancelled?"cancelled":"failed");
                run.putObject("failure").put("code",cancelled?"RUN_CANCELLED":"RUN_FAILED").put("reason",reason)
                    .put("detected_at",java.time.Instant.now().toString()).put("source",cancelled?"operator":"workbench");
                runtime.setVariable(pid,"shotRun",run.toString());
            }catch(java.io.IOException ex){throw new IllegalStateException("发次记录损坏",ex);}
        }
        runtime.setVariable(pid,"finalFacilityState",facilityStates.current().toString());
        runtime.deleteProcessInstance(pid,reason);
    }

    private Object parseJson(Object value) {
        if(value==null)return null;
        try{return json.readTree(value.toString());}catch(Exception ex){throw new IllegalStateException("运行记录损坏",ex);}
    }

    public List<Map<String, Object>> recent() {
        return history.createHistoricProcessInstanceQuery().excludeSubprocesses(true)
            .orderByProcessInstanceStartTime().desc().listPage(0, 20).stream().map(p -> {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("id", p.getId()); value.put("key", p.getProcessDefinitionKey());
                value.put("name", p.getProcessDefinitionName()); value.put("started", p.getStartTime());
                value.put("ended", p.getEndTime()); return value;
            }).toList();
    }

    public synchronized Map<String, Object> view(String pid) {
        var process = history.createHistoricProcessInstanceQuery().processInstanceId(pid).singleResult();
        if (process == null) throw new IllegalArgumentException("流程不存在");
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", pid); value.put("key", process.getProcessDefinitionKey());
        value.put("name", process.getProcessDefinitionName()); value.put("started", process.getStartTime());
        value.put("ended", process.getEndTime());
        Map<String, Object> vars = new HashMap<>();
        for (var variable : history.createHistoricVariableInstanceQuery().processInstanceId(pid).list())
            if (variable.getExecutionId().equals(pid)) vars.put(variable.getVariableName(), variable.getValue());
        value.put("outcome", process.getEndTime() != null && "RUNNING".equals(vars.getOrDefault("outcome", "RUNNING"))
            ? (process.getDeleteReason() == null ? "SUCCEEDED" : "FAILED") : vars.getOrDefault("outcome", "RUNNING"));
        value.put("configuration", vars.get("workbenchConfiguration"));
        value.put("modelVersions", vars.get("modelVersions"));
        value.put("initialDeviceSnapshots", vars.get("initialDeviceSnapshots"));
        value.put("shotRun", parseJson(vars.get("shotRun")));
        value.put("facilityState", process.getEndTime()==null ? facilityStates.current() : parseJson(vars.get("finalFacilityState")));
        value.put("failureReason", vars.get("failureReason"));
        value.put("communicationError", vars.get("communicationError"));
        var active = runtime.createExecutionQuery().processInstanceId(pid).list();
        value.put("activeActivities", active.stream().map(Execution::getActivityId).filter(Objects::nonNull).distinct().toList());
        List<Map<String, Object>> activities = new ArrayList<>();
        for (var item : history.createHistoricActivityInstanceQuery().processInstanceId(pid)
                .orderByHistoricActivityInstanceStartTime().asc().list()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", item.getActivityId()); row.put("name", item.getActivityName()); row.put("type", item.getActivityType());
            row.put("started", item.getStartTime()); row.put("ended", item.getEndTime());
            row.put("cancelled", item.getDeleteReason() != null); activities.add(row);
        }
        value.put("activities", activities);
        List<Object> waits=new ArrayList<>();
        for(var execution:active) {
            Object wait=runtime.getVariableLocal(execution.getId(),FacilityCompletionRuntime.VARIABLE);
            if(wait!=null)waits.add(parseJson(wait));
        }
        value.put("waits",process.getEndTime()==null?waits:vars.getOrDefault("finalCompletionWaits",List.of()));
        Map<String,Object> dependencyLedger=new LinkedHashMap<>();
        if(vars.get("dependencyHistory") instanceof Map<?,?> saved)saved.forEach((id,row)->dependencyLedger.put(id.toString(),row));
        for(var execution:active) {
            Object raw=runtime.getVariableLocal(execution.getId(),FacilityDependencyRuntime.VARIABLE);
            if(raw!=null){var item=dependencies.read(raw);dependencyLedger.put(item.path("id").asText(),item);}
        }
        value.put("dependencies",new ArrayList<>(dependencyLedger.values()));
        value.put("timers", management.createTimerJobQuery().processInstanceId(pid).list().stream()
            .map(t -> Map.of("due", t.getDuedate(), "id", t.getId())).toList());
        Map<String,Map<String,Object>> ledger=new LinkedHashMap<>();
        if(vars.get("commandHistory") instanceof Map<?,?> saved)
            saved.forEach((id,row)->ledger.put(id.toString(),(Map<String,Object>)row));
        for(var execution:active) {
            var local=runtime.getVariablesLocal(execution.getId());
            if(local.get("commandId")!=null)ledger.put(local.get("commandId").toString(),local);
        }
        value.put("commands",new ArrayList<>(ledger.values()));
        List<Map<String,Object>> unresolved=new ArrayList<>();
        for(var command:ledger.values())if(Boolean.TRUE.equals(command.get("commandOutcomeUnknown")) ||
            (process.getEndTime()!=null && command.get("commandResult")==null && Boolean.TRUE.equals(command.get("dispatchAttempted"))))
            unresolved.add(Map.of("device",command.get("commandDevice"),"commandId",command.get("commandId"),"action",command.get("commandAction")));
        value.put("unresolvedDeviceActions",unresolved);
        Map<String, Object> participants = new LinkedHashMap<>();
        for (Object entry : vars.values()) if (entry instanceof List<?> targets) for (Object target : targets)
            if (target instanceof Map<?, ?> item && item.get("id") != null && item.get("component") != null)
                participants.put(item.get("id").toString(), target);
        value.put("participants", new ArrayList<>(participants.values()));
        value.put("children", history.createHistoricProcessInstanceQuery().superProcessInstanceId(pid)
            .orderByProcessInstanceStartTime().asc().list().stream().map(child -> view(child.getId())).toList());
        return value;
    }
}
