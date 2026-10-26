package cn.cdi8.workbench.facility;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import org.flowable.engine.delegate.DelegateExecution;
import org.springframework.stereotype.Component;

/** Durable activity-completion waits. Observations do not replace this activation's command results. */
@Component
public final class FacilityCompletionRuntime {
    public static final String VARIABLE="facilityCompletion";
    private final ObjectMapper json;
    private final FacilityStateRuntime states;

    public FacilityCompletionRuntime(ObjectMapper json,FacilityStateRuntime states) {
        this.json=json;this.states=states;
    }

    /** Called only after the task's entire command queue has been successfully verified. */
    public boolean begin(DelegateExecution execution) {
        var condition=FacilityBpmnModel.completion(execution.getCurrentFlowElement());
        if(condition==null)return false;
        if(execution.getVariableLocal(VARIABLE)==null) {
            long now=System.currentTimeMillis();
            ObjectNode wait=json.createObjectNode().put("id",UUID.randomUUID().toString())
                .put("activity",execution.getCurrentActivityId()).put("kind",condition.kind())
                .put("condition",condition.ref()).put("started",now)
                .put("deadline",Math.addExact(now,condition.timeout().toMillis()))
                .put("status","unknown").put("reason","等待设施条件核验").put("met",false);
            execution.setVariableLocal(VARIABLE,wait.toString());
        }
        return true;
    }

    public ObjectNode read(Object raw) {
        if(raw==null)throw new IllegalStateException("活动没有完成条件等待记录");
        try{return (ObjectNode)json.readTree(raw.toString());}
        catch(Exception ex){throw new IllegalStateException("完成条件等待记录损坏",ex);}
    }

    public boolean expired(ObjectNode wait) { return System.currentTimeMillis()>=wait.path("deadline").asLong(); }

    public ObjectNode evaluate(Object raw,boolean refresh) {
        ObjectNode wait=read(raw),snapshot=states.assessment(refresh);
        var value=snapshot.path("fact".equals(wait.path("kind").asText())?"facts":"guards").path(wait.path("condition").asText());
        wait.put("status",value.path("status").asText("unknown"))
            .put("label",value.path("label").asText(wait.path("condition").asText()))
            .put("reason",value.path("reason").asText("设施条件没有有效观测"))
            .put("met","valid".equals(value.path("status").asText()))
            .put("evaluatedAt",System.currentTimeMillis());
        wait.set("facilityState",snapshot);
        return wait;
    }

    /** Recheck at the actual Flowable callback, including expiry and changes since the wake-up. */
    public boolean resume(DelegateExecution execution) {
        ObjectNode wait=evaluate(execution.getVariableLocal(VARIABLE),false);
        execution.setVariableLocal(VARIABLE,wait.toString());
        if(expired(wait))throw new IllegalStateException("完成条件等待超时："+wait.path("condition").asText());
        if(!wait.path("met").asBoolean())return false;
        execution.removeVariableLocal(VARIABLE);
        return true;
    }
}
