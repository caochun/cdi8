package cn.cdi8.workbench.scenarios.gxlf;

import cn.cdi8.workbench.run.ComponentCommandTasks;
import org.flowable.engine.delegate.DelegateExecution;
import org.springframework.stereotype.Component;

/** Compatibility expressions used by existing GXLF BPMN definitions. */
@Component("tango")
public class TangoTasks {
    private final ComponentCommandTasks commands;
    private final GxlfCompletionChecks checks;

    public TangoTasks(ComponentCommandTasks commands, GxlfCompletionChecks checks) {
        this.commands = commands;
        this.checks = checks;
    }

    public void enqueue(DelegateExecution e, String action, String expected) { commands.enqueue(e, action, expected); }
    public void verify(DelegateExecution e) { commands.verify(e); }
    public void mark(DelegateExecution e, String name) {
        commands.mark(e, name);
        e.setVariable("scenarioId", "gxlf");
    }
    public void succeeded(DelegateExecution e) { commands.succeeded(e); }
    public void failed(DelegateExecution e) { commands.failed(e); }
    public void finish(DelegateExecution e) { checks.finish(e); }
    public void initializationFinish(DelegateExecution e) { checks.initializationFinish(e); }
    public void parameterDispatchFinish(DelegateExecution e) { checks.parameterDispatchFinish(e); }
    public void formalEmissionFinish(DelegateExecution e) { checks.formalEmissionFinish(e); }
    public void postEmissionFinish(DelegateExecution e) { checks.postEmissionFinish(e); }
}
