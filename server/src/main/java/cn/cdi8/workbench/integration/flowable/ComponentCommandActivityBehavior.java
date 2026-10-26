package cn.cdi8.workbench.integration.flowable;

import cn.cdi8.workbench.run.ComponentCommandTasks;
import cn.cdi8.workbench.facility.FacilityActionRuntime;
import cn.cdi8.workbench.facility.FacilityCompletionRuntime;

import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.impl.bpmn.behavior.AbstractBpmnActivityBehavior;
import org.flowable.engine.impl.delegate.TriggerableActivityBehavior;

/**
 * A non-blocking BPMN activity for the common Tango command lifecycle.
 *
 * execute() durably registers the command and leaves the execution in this
 * activity.  WorkflowService later triggers it when Tango reports a terminal
 * result. After all commands succeed, an optional facility completion wait must
 * also pass before the activity can leave.
 */
public final class ComponentCommandActivityBehavior extends AbstractBpmnActivityBehavior
        implements TriggerableActivityBehavior {
    private final ComponentCommandTasks tasks;
    private final FacilityActionRuntime facilityActions;
    private final FacilityCompletionRuntime completions;
    private final cn.cdi8.workbench.facility.FacilityHoldRuntime holds;

    public ComponentCommandActivityBehavior(ComponentCommandTasks tasks, FacilityActionRuntime facilityActions, FacilityCompletionRuntime completions,cn.cdi8.workbench.facility.FacilityHoldRuntime holds) {
        this.holds=holds;
        this.tasks = tasks;
        this.facilityActions = facilityActions;
        this.completions = completions;
    }

    @Override
    public void execute(DelegateExecution execution) {
        if(!holds.guard(execution))return;
        facilityActions.enter(execution);
        facilityActions.start(execution);
        if(execution.getVariableLocal("facilityActionQueue")==null)completions.begin(execution);
    }

    @Override
    public void trigger(DelegateExecution execution, String signalName, Object signalData) {
        if(!holds.guard(execution))return;
        if(execution.getVariableLocal(FacilityCompletionRuntime.VARIABLE)!=null) {
            if(completions.resume(execution))leave(execution);
            return;
        }
        tasks.verify(execution);
        facilityActions.verifyDependencies(execution);
        if(facilityActions.advance(execution))return;
        if(completions.begin(execution))return;
        leave(execution);
    }
}
