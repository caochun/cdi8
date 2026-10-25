package cn.cdi8.workbench.integration.flowable;

import cn.cdi8.workbench.run.ComponentCommandTasks;
import cn.cdi8.workbench.run.ActionContractModel;
import cn.cdi8.workbench.facility.FacilityActionRuntime;
import cn.cdi8.workbench.facility.FacilityBpmnModel;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.flowable.bpmn.model.BaseElement;
import org.flowable.bpmn.model.FieldExtension;
import org.flowable.bpmn.model.ServiceTask;
import org.flowable.engine.impl.bpmn.parser.BpmnParse;
import org.flowable.engine.impl.bpmn.parser.handler.ServiceTaskParseHandler;
import org.springframework.stereotype.Component;

/** Parses a component-command marker into a triggerable activity. */
@Component
public final class ComponentCommandBpmnParseHandler extends ServiceTaskParseHandler {
    public static final String TYPE = "component-command";

    private final ComponentCommandTasks tasks;
    private final ObjectMapper json;
    private final FacilityActionRuntime facilityActions;

    public ComponentCommandBpmnParseHandler(ComponentCommandTasks tasks, ObjectMapper json, FacilityActionRuntime facilityActions) {
        this.tasks = tasks;
        this.json = json;
        this.facilityActions = facilityActions;
    }

    @Override
    public Class<? extends BaseElement> getHandledType() {
        return ServiceTask.class;
    }

    @Override
    protected void executeParse(BpmnParse parse, ServiceTask task) {
        ActionContractModel.validateElement(task);
        boolean hasFacilityActions=task.getExtensionElements().getOrDefault("action",java.util.List.of()).stream()
            .anyMatch(item->FacilityBpmnModel.FACILITY_NS.equals(item.getNamespace()));
        if(hasFacilityActions) {
            FacilityBpmnModel.validateProcess(parse.getBpmnModel());
            task.setTriggerable(true);
            task.setBehavior(new ComponentCommandActivityBehavior(tasks,json,"","",facilityActions));
            return;
        }
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldExtension field : task.getFieldExtensions()) {
            String value = field.getStringValue();
            if (value == null && field.getExpression() != null) value = field.getExpression();
            fields.put(field.getFieldName(), value);
        }
        if (!"true".equalsIgnoreCase(fields.get("componentCommand"))
                && !TYPE.equalsIgnoreCase(task.getType())) {
            super.executeParse(parse, task);
            return;
        }
        task.setTriggerable(true);
        task.setBehavior(new ComponentCommandActivityBehavior(tasks, json,
            fields.get("action"), fields.get("expectedState")));
    }
}
