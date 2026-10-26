package cn.cdi8.workbench.integration.flowable;

import java.util.ArrayList;
import org.flowable.spring.boot.ProcessEngineConfigurationConfigurer;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.springframework.stereotype.Component;

/** Replaces Flowable's service-task parser with one that recognizes the command marker. */
@Component
public final class ComponentCommandProcessEngineConfigurer implements ProcessEngineConfigurationConfigurer {
    private final ComponentCommandBpmnParseHandler handler;
    private final FacilityHoldParseHandler holds;

    public ComponentCommandProcessEngineConfigurer(ComponentCommandBpmnParseHandler handler,FacilityHoldParseHandler holds) {
        this.handler = handler;
        this.holds=holds;
    }

    @Override
    public void configure(SpringProcessEngineConfiguration configuration) {
        configuration.setCreateDiagramOnDeploy(false); // Browser viewers render the deployed XML directly.
        var handlers = configuration.getCustomDefaultBpmnParseHandlers();
        if (handlers == null) handlers = new ArrayList<>();
        handlers.add(handler);
        handlers.add(holds.processHandler());handlers.add(holds);handlers.add(holds.endHandler());
        configuration.setCustomDefaultBpmnParseHandlers(handlers);
        var listeners=configuration.getEventListeners();
        if(listeners==null)listeners=new ArrayList<>();
        listeners.add(holds.lifecycleListener());configuration.setEventListeners(listeners);
    }
}
