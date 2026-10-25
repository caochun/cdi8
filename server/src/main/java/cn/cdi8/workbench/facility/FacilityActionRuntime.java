package cn.cdi8.workbench.facility;

import cn.cdi8.workbench.integration.flowable.ComponentCommandActivityBehavior;
import cn.cdi8.workbench.run.ComponentCommandTasks;
import cn.cdi8.workbench.systemdefinition.SystemDefinitionModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.flowable.bpmn.model.BaseElement;
import org.flowable.bpmn.model.ExtensionElement;
import org.flowable.engine.delegate.DelegateExecution;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** Expands fac:action declarations into one command lifecycle per bound device. */
@Component("facilityActions")
public final class FacilityActionRuntime {
    public static final String NS=FacilityBpmnModel.FACILITY_NS;
    private final ObjectMapper json;
    private final ComponentCommandTasks commands;
    private final SystemDefinitionModel definition;

    public FacilityActionRuntime(ObjectMapper json, ComponentCommandTasks commands) {
        this.json=json;this.commands=commands;
        try(thisResource input=new thisResource(new ClassPathResource("system-models/gxlf-facility.system-definition.xml"))) {
            this.definition=SystemDefinitionModel.parse(new String(input.readAllBytes(),StandardCharsets.UTF_8));
        }catch(Exception ex){throw new IllegalStateException("无法加载设施动作定义："+ex.getMessage(),ex);}
    }

    /** Called by a triggerable service task when it enters the command lifecycle. */
    public synchronized void start(DelegateExecution execution) {
        if(execution.getVariableLocal("facilityActionQueue")!=null)return;
        ArrayNode queue=json.createArrayNode();
        for(var action:actions(execution.getCurrentFlowElement())) {
            String component=attr(action,"component"), capability=attr(action,"capability"), collection=attr(action,"collection");
            Object raw=execution.getVariable(collection);
            if(!(raw instanceof Collection<?> values))throw new IllegalStateException("设施动作集合未绑定："+collection);
            String actionRef=definition.actionRef(component,capability);
            for(Object value:values)if(value instanceof Map<?,?> target&&component.equals(Objects.toString(target.get("component"),""))) {
                ObjectNode item=queue.addObject().put("component",component).put("capability",capability).put("action",actionRef);
                item.set("target",json.valueToTree(target));
                item.put("expectedState",attr(action,"expectedState"));
            }
        }
        if(queue.isEmpty())throw new IllegalStateException("设施动作没有匹配设备");
        execution.setVariableLocal("facilityActionQueue",queue.toString());
        execution.setVariableLocal("facilityActionIndex",0);
        advance(execution);
    }

    /** Called after one command terminal result; returns true when another remains. */
    public synchronized boolean advance(DelegateExecution execution) {
        Object raw=execution.getVariableLocal("facilityActionQueue");
        if(raw==null)return false;
        try {
            ArrayNode queue=(ArrayNode)json.readTree(raw.toString());
            int index=((Number)execution.getVariableLocal("facilityActionIndex")).intValue();
            if(index>=queue.size())return false;
            JsonNode item=queue.get(index);execution.setVariableLocal("facilityActionIndex",index+1);
            execution.setVariableLocal("target",json.convertValue(item.path("target"),Map.class));
            commands.enqueue(execution,item.path("action").asText(),item.path("expectedState").asText());
            return true;
        }catch(Exception ex){throw new IllegalStateException("设施动作队列损坏："+ex.getMessage(),ex);}
    }

    private static List<ExtensionElement> actions(BaseElement element){return element.getExtensionElements().getOrDefault("action",List.of()).stream().filter(item->NS.equals(item.getNamespace())).toList();}
    private static String attr(ExtensionElement element,String name){var value=element.getAttributes().get(name);return value==null||value.isEmpty()?"":value.get(0).getValue();}
    private static final class thisResource implements AutoCloseable {
        private final java.io.InputStream input;thisResource(ClassPathResource resource)throws Exception{input=resource.getInputStream();}
        byte[] readAllBytes()throws Exception{return input.readAllBytes();}
        public void close()throws Exception{input.close();}
    }
}
