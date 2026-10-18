package cn.cdi8.control;

import java.util.ArrayList;
import org.flowable.spring.boot.ProcessEngineConfigurationConfigurer;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.springframework.stereotype.Component;

/** Replaces Flowable's service-task parser with one that recognizes the command marker. */
@Component
public final class ComponentCommandProcessEngineConfigurer implements ProcessEngineConfigurationConfigurer {
    private final ComponentCommandBpmnParseHandler handler;

    public ComponentCommandProcessEngineConfigurer(ComponentCommandBpmnParseHandler handler) {
        this.handler = handler;
    }

    @Override
    public void configure(SpringProcessEngineConfiguration configuration) {
        var handlers = configuration.getCustomDefaultBpmnParseHandlers();
        if (handlers == null) handlers = new ArrayList<>();
        handlers.add(handler);
        configuration.setCustomDefaultBpmnParseHandlers(handlers);
    }
}
