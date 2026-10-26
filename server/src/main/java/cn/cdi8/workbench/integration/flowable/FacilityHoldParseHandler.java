package cn.cdi8.workbench.integration.flowable;

import cn.cdi8.workbench.facility.*;
import java.util.Set;
import org.flowable.bpmn.model.*;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.event.*;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType;
import org.flowable.engine.impl.bpmn.behavior.SubProcessActivityBehavior;
import org.flowable.engine.impl.bpmn.parser.BpmnParse;
import org.flowable.engine.impl.bpmn.parser.handler.SubProcessParseHandler;
import org.flowable.engine.impl.bpmn.parser.handler.EndEventParseHandler;
import org.flowable.engine.impl.bpmn.parser.handler.ProcessParseHandler;
import org.flowable.engine.impl.delegate.ActivityBehavior;
import org.springframework.stereotype.Component;

@Component
public final class FacilityHoldParseHandler extends SubProcessParseHandler {
    private final FacilityHoldRuntime holds;
    private final FacilityStateRuntime states;
    public FacilityHoldParseHandler(FacilityHoldRuntime holds,FacilityStateRuntime states){this.holds=holds;this.states=states;}

    @Override protected void executeParse(BpmnParse parse,SubProcess sub) {
        super.executeParse(parse,sub);
        if(FacilityBpmnModel.hold(sub)!=null)sub.setBehavior(new SubProcessActivityBehavior(){
            @Override public void execute(DelegateExecution execution){if(holds.begin(execution))super.execute(execution);}
        });
    }

    public ProcessParseHandler processHandler() {
        return new ProcessParseHandler(){
            @Override protected void executeParse(BpmnParse parse,org.flowable.bpmn.model.Process process) {
                FacilityBpmnModel.validateDeployment(parse.getBpmnModel(),states);
                super.executeParse(parse,process);
            }
        };
    }

    public EndEventParseHandler endHandler() {
        return new EndEventParseHandler(){
            @Override protected void executeParse(BpmnParse parse,EndEvent end) {
                super.executeParse(parse,end);
                ActivityBehavior delegate=(ActivityBehavior)end.getBehavior();
                end.setBehavior((ActivityBehavior)execution->{if(holds.guard(execution))delegate.execute(execution);});
            }
        };
    }

    public AbstractFlowableEngineEventListener lifecycleListener() {
        return new AbstractFlowableEngineEventListener(Set.of(FlowableEngineEventType.ACTIVITY_COMPLETED,FlowableEngineEventType.ACTIVITY_CANCELLED)) {
            @Override protected void activityCompleted(FlowableActivityEvent event){holds.ended(event.getProcessInstanceId(),event.getExecutionId(),"released");}
            @Override protected void activityCancelled(FlowableActivityCancelledEvent event){holds.ended(event.getProcessInstanceId(),event.getExecutionId(),"interrupted");}
            @Override public boolean isFailOnException(){return true;}
        };
    }
}
