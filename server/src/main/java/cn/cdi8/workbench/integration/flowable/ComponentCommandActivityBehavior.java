package cn.cdi8.workbench.integration.flowable;

import cn.cdi8.workbench.run.ComponentCommandTasks;
import cn.cdi8.workbench.run.WorkflowService;
import cn.cdi8.workbench.facility.FacilityActionRuntime;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.impl.bpmn.behavior.AbstractBpmnActivityBehavior;
import org.flowable.engine.impl.delegate.TriggerableActivityBehavior;

/**
 * A non-blocking BPMN activity for the common Tango command lifecycle.
 *
 * execute() durably registers the command and leaves the execution in this
 * activity.  WorkflowService later triggers it when Tango reports a terminal
 * result; trigger() verifies the result and leaves the activity.
 */
public final class ComponentCommandActivityBehavior extends AbstractBpmnActivityBehavior
        implements TriggerableActivityBehavior {
    private final ComponentCommandTasks tasks;
    private final ObjectMapper json;
    private final String actionDefinition;
    private final String expectedStateDefinition;
    private final FacilityActionRuntime facilityActions;

    public ComponentCommandActivityBehavior(ComponentCommandTasks tasks, ObjectMapper json, String action, String expectedState) {
        this(tasks,json,action,expectedState,null);
    }
    public ComponentCommandActivityBehavior(ComponentCommandTasks tasks, ObjectMapper json, String action, String expectedState, FacilityActionRuntime facilityActions) {
        this.tasks = tasks;
        this.json = json;
        this.actionDefinition = action;
        this.expectedStateDefinition = expectedState;
        this.facilityActions = facilityActions;
    }

    @Override
    public void execute(DelegateExecution execution) {
        if(facilityActions!=null){facilityActions.start(execution);return;}
        @SuppressWarnings("unchecked") Map<String, String> target = (Map<String, String>) execution.getVariable("target");
        String component = target == null ? "" : target.get("component");
        tasks.enqueue(execution, value(actionDefinition, component), value(expectedStateDefinition, component));
    }

    @Override
    public void trigger(DelegateExecution execution, String signalName, Object signalData) {
        tasks.verify(execution);
        if(facilityActions!=null&&facilityActions.advance(execution))return;
        leave(execution);
    }

    private String value(String definition, String component) {
        if (definition == null || definition.isBlank()) return "";
        try {
            var node = json.readTree(definition);
            if (node.isObject()) return node.has(component) ? node.get(component).asText() : "";
            return definition;
        } catch (Exception ignored) {
            return definition;
        }
    }
}
