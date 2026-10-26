package cn.cdi8.workbench.facility;

import cn.cdi8.workbench.run.ComponentCommandTasks;
import cn.cdi8.workbench.run.ShotRunRuntime;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.impl.bpmn.helper.ErrorPropagation;
import org.flowable.engine.impl.persistence.entity.ExecutionEntity;
import org.flowable.engine.impl.util.CommandContextUtil;
import org.springframework.stereotype.Component;

/** BPMN scope lifecycle. Only engine/coordinator callbacks route failures; observation listeners never do. */
@Component
public final class FacilityHoldRuntime {
    public static final String VARIABLE="facilityHold", HISTORY="holdHistory";
    private final ObjectMapper json;
    private final FacilityDependencyRuntime dependencies;
    private final FacilityStateRuntime states;
    private final ShotRunRuntime shots;
    private final ComponentCommandTasks tasks;
    private final FacilityCompletionRuntime completions;

    public FacilityHoldRuntime(ObjectMapper json,FacilityDependencyRuntime dependencies,FacilityStateRuntime states,
                               ShotRunRuntime shots,ComponentCommandTasks tasks,FacilityCompletionRuntime completions) {
        this.json=json;this.dependencies=dependencies;this.states=states;this.shots=shots;this.tasks=tasks;this.completions=completions;
    }

    public boolean begin(DelegateExecution delegate) {
        ExecutionEntity scope=(ExecutionEntity)delegate;
        var declaration=FacilityBpmnModel.hold(scope.getCurrentFlowElement());
        if(declaration==null)return true;
        ObjectNode plan=json.createObjectNode().put("id",UUID.randomUUID().toString()).put("purpose","stage-hold")
            .put("activity",scope.getActivityId()).put("execution",scope.getId()).put("label",scope.getCurrentFlowElement().getName())
            .put("errorRef",declaration.errorRef()).put("started",System.currentTimeMillis()).put("outcome","active");
        var requirements=plan.putArray("requirements");
        try {
            for(var input:declaration.inputs()) {
                Object raw=scope.getVariable(input.collection());
                if(!(raw instanceof Collection<?> targets))throw new IllegalArgumentException("保持范围的设备集合未绑定："+input.collection());
                int matched=0;
                for(var value:targets)if(value instanceof Map<?,?> target&&input.type().equals(target.get("component"))) {
                    matched++;
                    var resolved=dependencies.plan(plan.path("id").asText(),input.type(),input.capability(),Objects.toString(target.get("id"),""),
                        (Collection<?>)scope.getVariable("beamlines"),(Map<String,String>)scope.getVariable("sourceSelections"),
                        Objects.toString(scope.getVariable("defaultSource"),states.binding().topology().defaultSource()),
                        (Map<String,String>)scope.getVariable("facilityInstances"),input.requirement());
                    requirements.addAll((com.fasterxml.jackson.databind.node.ArrayNode)resolved.path("requirements"));
                    if(resolved.has("resolutionFailure"))throw new IllegalArgumentException(resolved.path("resolutionFailure").asText());
                }
                if(matched==0)throw new IllegalArgumentException("保持范围没有匹配设备："+input.type()+" / "+input.collection());
            }
            for(var condition:declaration.conditions())requirements.addObject().put("kind",condition.kind()).put("ref",condition.ref()).put("maintain","execution");
        }catch(RuntimeException ex){plan.put("resolutionFailure",Objects.toString(ex.getMessage(),"无法解析阶段保持范围"));}
        scope.setVariableLocal(VARIABLE,plan.toString());
        return check(scope,false,true);
    }

    /** Recheck before dispatch/progress, at task callbacks and before taking a scope's normal exit. */
    public boolean guard(DelegateExecution delegate) {
        var scopes=new ArrayList<ExecutionEntity>();
        for(var execution=(ExecutionEntity)delegate;execution!=null;execution=execution.getParent())
            if(execution.hasVariableLocal(VARIABLE))scopes.add(execution);
        Collections.reverse(scopes);
        for(var scope:scopes)if(!check(scope,true,false))return false;
        return true;
    }

    public boolean check(ExecutionEntity scope,boolean started,boolean reconcile) {
        var assessment=dependencies.check(scope.getVariableLocal(VARIABLE),started,reconcile);
        scope.setVariableLocal(VARIABLE,assessment.toString());
        remember(scope,assessment);
        if("valid".equals(assessment.path("status").asText()))return true;
        interrupt(scope,assessment);
        return false;
    }

    private ExecutionEntity root(ExecutionEntity execution) {
        return CommandContextUtil.getExecutionEntityManager().findById(execution.getProcessInstanceId());
    }

    private void remember(ExecutionEntity execution,ObjectNode assessment) {
        var root=root(execution);
        var history=ledger(root,HISTORY);
        history.put(assessment.path("id").asText(),json.convertValue(assessment,Map.class));
        root.setVariableLocal(HISTORY,history);
        Object shot=root.getVariableLocal("shotRun");
        if(shot!=null)root.setVariableLocal("shotRun",shots.holdEvidence(shot,assessment));
    }

    private Map<String,Object> ledger(ExecutionEntity root,String name) {
        Object raw=root.getVariableLocal(name);
        return raw instanceof Map<?,?>?new LinkedHashMap<>((Map<String,Object>)raw):new LinkedHashMap<>();
    }

    /** Save pending physical operations before Flowable removes the interrupted subtree. */
    private void interrupt(ExecutionEntity scope,ObjectNode assessment) {
        String reason="阶段持续保持失效："+assessment.path("activity").asText()+" / "+assessment.path("reason").asText();
        var root=root(scope);
        assessment.put("outcome","failed").put("ended",System.currentTimeMillis());remember(scope,assessment);
        root.setVariableLocal("holdFailure",assessment.toString());root.setVariableLocal("holdFailureReason",reason);
        preserve(scope,reason);
        // Remove before error dispatch: the boundary handler is outside this holding scope.
        scope.removeVariableLocal(VARIABLE);releaseAfterCommit(assessment.path("id").asText());
        ErrorPropagation.propagateError(assessment.path("errorRef").asText(),scope);
    }

    private void preserve(ExecutionEntity execution,String reason) {
        var root=root(execution);var local=execution.getVariablesLocal();
        if(local.containsKey("commandId")) {
            var command=new LinkedHashMap<>(local);
            String status;
            if(local.get("commandResult")!=null) {
                try{tasks.validateResult(local);status="succeeded";}catch(RuntimeException ex){status="failed";}
            } else if(Boolean.TRUE.equals(local.get("dispatchAttempted"))||Boolean.TRUE.equals(local.get("sent"))) {
                status="unknown";command.put("commandOutcomeUnknown",true);
            } else status="blocked";
            command.put("verificationStatus",status);
            var commands=ledger(root,"commandHistory");commands.put(local.get("commandId").toString(),command);root.setVariableLocal("commandHistory",commands);
            if(root.getVariableLocal("shotRun")!=null)root.setVariableLocal("shotRun",shots.commandEvidence(root.getVariableLocal("shotRun"),command,status,reason));
        }
        if(local.containsKey(FacilityDependencyRuntime.VARIABLE)) {
            var dependency=dependencies.check(local.get(FacilityDependencyRuntime.VARIABLE),Boolean.TRUE.equals(local.get("dispatchAttempted")),false);
            dependency.put("outcome","interrupted");var history=ledger(root,"dependencyHistory");
            history.put(dependency.path("id").asText(),json.convertValue(dependency,Map.class));root.setVariableLocal("dependencyHistory",history);
            if(root.getVariableLocal("shotRun")!=null)root.setVariableLocal("shotRun",shots.dependencyEvidence(root.getVariableLocal("shotRun"),dependency));
            releaseAfterCommit(dependency.path("id").asText());
        }
        if(local.containsKey(FacilityCompletionRuntime.VARIABLE)) {
            var wait=completions.read(local.get(FacilityCompletionRuntime.VARIABLE));wait.put("outcome","interrupted").put("ended",System.currentTimeMillis()).put("endReason",reason);
            var waits=ledger(root,"interruptedCompletionWaits");waits.put(wait.path("id").asText(),json.convertValue(wait,Map.class));root.setVariableLocal("interruptedCompletionWaits",waits);
            if(root.getVariableLocal("shotRun")!=null)root.setVariableLocal("shotRun",shots.completionEvidence(root.getVariableLocal("shotRun"),wait,"invalidated",reason));
        }
        for(var child:new ArrayList<>(execution.getExecutions()))preserve(child,reason);
    }

    /** Engine completion fires once after all parallel tokens end, before the outer continuation. */
    public void ended(String process,String execution,String outcome) {
        var root=CommandContextUtil.getExecutionEntityManager().findById(process);
        if(root==null)return;
        for(var value:ledger(root,HISTORY).values()) {
            var assessment=(ObjectNode)json.valueToTree(value);
            if(execution.equals(assessment.path("execution").asText())&&"active".equals(assessment.path("outcome").asText())) {
                if("released".equals(outcome)) {
                    assessment=dependencies.check(assessment.toString(),true,false);
                    // The engine has just removed the scope. Roll back its normal exit if
                    // an observation failed after the end-event guard; the coordinator can
                    // then route the still-latched failure through the restored boundary.
                    if(!"valid".equals(assessment.path("status").asText()))
                        throw new IllegalStateException("阶段退出前保持条件发生变化："+assessment.path("reason").asText());
                }
                assessment.put("outcome",outcome).put("ended",System.currentTimeMillis());
                remember(root,assessment);releaseAfterCommit(assessment.path("id").asText());
            }
        }
    }

    public void finish(ExecutionEntity scope,String outcome) {
        var assessment=dependencies.check(scope.getVariableLocal(VARIABLE),true,false);
        assessment.put("outcome",outcome).put("ended",System.currentTimeMillis());remember(scope,assessment);
        scope.removeVariableLocal(VARIABLE);releaseAfterCommit(assessment.path("id").asText());
    }

    private void releaseAfterCommit(String id) {
        var transaction=org.flowable.common.engine.impl.context.Context.getTransactionContext();
        if(transaction==null)throw new IllegalStateException("保持范围必须在 Flowable 事务中结束");
        transaction.addTransactionListener(org.flowable.common.engine.impl.cfg.TransactionState.COMMITTED,context->dependencies.release(id));
    }
}
