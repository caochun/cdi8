package cn.cdi8.workbench.integration.flowable;

import cn.cdi8.workbench.run.ComponentCommandTasks;
import cn.cdi8.workbench.facility.FacilityActionRuntime;
import cn.cdi8.workbench.facility.FacilityBpmnModel;
import cn.cdi8.workbench.facility.FacilityCompletionRuntime;

import org.flowable.bpmn.model.BaseElement;
import org.flowable.bpmn.model.ServiceTask;
import org.flowable.engine.impl.bpmn.parser.BpmnParse;
import org.flowable.engine.impl.bpmn.parser.handler.ServiceTaskParseHandler;
import org.springframework.stereotype.Component;

/** Parses facility actions into a triggerable activity and enforces service-task entry guards. */
@Component
public final class ComponentCommandBpmnParseHandler extends ServiceTaskParseHandler {
    private final cn.cdi8.workbench.facility.FacilityStateRuntime states;
    private final ComponentCommandTasks tasks;
    private final FacilityActionRuntime facilityActions;
    private final FacilityCompletionRuntime completions;

    public ComponentCommandBpmnParseHandler(ComponentCommandTasks tasks, FacilityActionRuntime facilityActions, cn.cdi8.workbench.facility.FacilityStateRuntime states, FacilityCompletionRuntime completions) {
        this.states=states;
        this.tasks = tasks;
        this.facilityActions = facilityActions;
        this.completions = completions;
    }

    @Override
    public Class<? extends BaseElement> getHandledType() {
        return ServiceTask.class;
    }

    @Override
    protected void executeParse(BpmnParse parse, ServiceTask task) {
        FacilityBpmnModel.validateDeployment(parse.getBpmnModel(),states);
        boolean hasFacilityActions=!FacilityBpmnModel.actions(task).isEmpty();
        if(hasFacilityActions || FacilityBpmnModel.completion(task)!=null) {
            task.setTriggerable(true);
            task.setBehavior(new ComponentCommandActivityBehavior(tasks,facilityActions,completions));
            return;
        }
        super.executeParse(parse, task);
        if(!task.getExtensionElements().getOrDefault("requires",java.util.List.of()).isEmpty()
            || !task.getExtensionElements().getOrDefault("stage",java.util.List.of()).isEmpty())
            task.setBehavior(new FacilityGuardedActivityBehavior(
                (org.flowable.engine.impl.delegate.ActivityBehavior)task.getBehavior(),facilityActions));
    }
}
