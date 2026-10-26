package cn.cdi8.workbench.integration.flowable;

import cn.cdi8.workbench.facility.FacilityActionRuntime;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.impl.delegate.ActivityBehavior;
import org.flowable.engine.impl.delegate.TriggerableActivityBehavior;

/** Executes declarative entry guards before Flowable's ordinary service task. */
final class FacilityGuardedActivityBehavior implements TriggerableActivityBehavior {
    private final ActivityBehavior delegate;
    private final FacilityActionRuntime actions;
    private final cn.cdi8.workbench.facility.FacilityHoldRuntime holds;

    FacilityGuardedActivityBehavior(ActivityBehavior delegate, FacilityActionRuntime actions,cn.cdi8.workbench.facility.FacilityHoldRuntime holds) {
        this.holds=holds;
        this.delegate = delegate;
        this.actions = actions;
    }

    @Override public void execute(DelegateExecution execution) {
        if(!holds.guard(execution))return;
        actions.enter(execution);
        delegate.execute(execution);
    }

    @Override public void trigger(DelegateExecution execution, String name, Object data) {
        if(!holds.guard(execution))return;
        if (!(delegate instanceof TriggerableActivityBehavior triggerable))
            throw new IllegalStateException("该活动不支持外部触发");
        triggerable.trigger(execution, name, data);
    }
}
