package cn.cdi8.workbench.workflow.facility;

import cn.cdi8.workbench.integration.facility.FacilityDependencyClient;
import cn.cdi8.workbench.integration.facility.FacilityStateClient;

import cn.cdi8.workbench.workflow.ComponentCommandTasks;
import cn.cdi8.workbench.workflow.ShotRunRuntime;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.flowable.bpmn.model.BaseElement;
import org.flowable.bpmn.model.ExtensionElement;
import org.flowable.engine.delegate.DelegateExecution;
import org.springframework.stereotype.Component;

/** Expands fac:action declarations into one command lifecycle per bound device. */
@Component("facilityActions")
public final class FacilityActionRuntime {
    public static final String NS=FacilityBpmnModel.FACILITY_NS;
    private final ObjectMapper json;
    private final ComponentCommandTasks commands;
    private final FacilityStateClient facilityStates;
    private final ShotRunRuntime shots;
    private final FacilityDependencyClient dependencies;

    public FacilityActionRuntime(ObjectMapper json, ComponentCommandTasks commands, FacilityStateClient facilityStates, ShotRunRuntime shots, FacilityDependencyClient dependencies) {
        this.json=json;this.commands=commands;this.facilityStates=facilityStates;
        this.shots=shots;
        this.dependencies=dependencies;
    }

    /** Entry guards apply equally to action tasks and ordinary expression tasks. */
    public void enter(DelegateExecution execution) {
        var element=execution.getCurrentFlowElement();
        for(var operation:element.getExtensionElements().getOrDefault("operation",List.of()))
            if(NS.equals(operation.getNamespace()))facilityStates.requireOperation(attr(operation,"operationRef"));
        for(var requirement:element.getExtensionElements().getOrDefault("requires",List.of()))
            if(NS.equals(requirement.getNamespace()))facilityStates.requireGuard(attr(requirement,"guardRef"));
        for(var stage:element.getExtensionElements().getOrDefault("stage",List.of()))
            if(FacilityBpmnModel.RUN_NS.equals(stage.getNamespace()))shots.stage(execution,attr(stage,"value"));
    }

    /** Called by a triggerable service task when it enters the command lifecycle. */
    public synchronized void start(DelegateExecution execution) {
        if(execution.getVariableLocal("facilityActionQueue")!=null)return;
        ArrayNode queue=json.createArrayNode();
        for(var action:FacilityBpmnModel.actions(execution.getCurrentFlowElement())) {
            String componentType=attr(action,"typeRef"), capability=attr(action,"capabilityRef"), collection=attr(action,"collectionVar");
            Object raw=execution.getVariable(collection);
            if(!(raw instanceof Collection<?> values))throw new IllegalStateException("设施动作集合未绑定："+collection);
            String actionRef=facilityStates.definition().actionRef(componentType,capability);
            int before=queue.size();
            for(Object value:values)if(value instanceof Map<?,?> target&&componentType.equals(cn.cdi8.facility.contract.FacilityNames.componentType(target))) {
                ObjectNode item=queue.addObject().put("componentType",componentType).put("capability",capability).put("action",actionRef);
                item.set("target",json.valueToTree(cn.cdi8.facility.contract.FacilityNames.target(target)));
                item.put("expectedState",attr(action,"expectedStateRef"));
            }
            if(queue.size()==before)throw new IllegalStateException("设施动作没有匹配设备："+componentType+" / "+collection);
        }
        if(queue.isEmpty()) {
            if(FacilityBpmnModel.completion(execution.getCurrentFlowElement())!=null)return;
            throw new IllegalStateException("设施动作没有匹配设备");
        }
        execution.setVariableLocal("facilityActionQueue",queue.toString());
        execution.setVariableLocal("facilityActionIndex",0);
        advance(execution);
    }

    /** Called after one command terminal result; returns true when another remains. */
    public synchronized boolean advance(DelegateExecution execution) {
        Object raw=execution.getVariableLocal("facilityActionQueue");
        if(raw==null)return false;
        try {
            Object previous=execution.getVariableLocal("commandId");
            if(previous!=null)dependencies.release(previous.toString());
            execution.removeVariableLocal(FacilityDependencyClient.VARIABLE);
            ArrayNode queue=(ArrayNode)json.readTree(raw.toString());
            int index=((Number)execution.getVariableLocal("facilityActionIndex")).intValue();
            if(index>=queue.size()) {
                for(String name:List.of("facilityActionQueue","facilityActionIndex","target","facilityAction",
                    "commandId","commandDevice","commandAction","commandNode","expectedState","sent",
                    "commandResult","dispatchAttempted","commandOutcomeUnknown","commandStartedAt","commandSource","commandParameters"))
                    execution.removeVariableLocal(name);
                return false;
            }
            ObjectNode item=(ObjectNode)queue.get(index);execution.setVariableLocal("facilityActionIndex",index+1);
            cn.cdi8.facility.contract.FacilityNames.rename(item,"componentType","component");
            execution.setVariableLocal("target",cn.cdi8.facility.contract.FacilityNames.target(json.convertValue(item.path("target"),Map.class)));
            commands.enqueueFacilityAction(execution,item.path("action").asText(),item.path("expectedState").asText());
            var plan=dependencies.plan(execution.getVariableLocal("commandId").toString(),item.path("componentType").asText(),item.path("capability").asText(),
                item.path("target").path("id").asText(),(Collection<?>)execution.getVariable("beamlines"),
                (Map<String,String>)execution.getVariable("sourceSelections"),Objects.toString(execution.getVariable("defaultSource"),facilityStates.binding().topology().defaultSource()),
                (Map<String,String>)execution.getVariable("facilityInstances"));
            if(plan!=null)execution.setVariableLocal(FacilityDependencyClient.VARIABLE,plan.toString());
            return true;
        }catch(java.io.IOException ex){throw new IllegalStateException("设施动作队列损坏："+ex.getMessage(),ex);}
    }

    public void verifyDependencies(DelegateExecution execution) {
        Object raw=execution.getVariableLocal(FacilityDependencyClient.VARIABLE);
        if(raw==null)return;
        var result=dependencies.check(raw,true,false);
        execution.setVariableLocal(FacilityDependencyClient.VARIABLE,result.toString());
        if(!"valid".equals(result.path("status").asText()))throw new IllegalStateException("能力输入依赖失效："+result.path("reason").asText());
    }

    private static String attr(ExtensionElement element,String name){var value=element.getAttributes().get(name);return value==null||value.isEmpty()?"":value.get(0).getValue();}
}
