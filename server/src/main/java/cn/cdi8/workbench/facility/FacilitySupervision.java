package cn.cdi8.workbench.facility;

import cn.cdi8.workbench.systemdefinition.SystemDefinitionModel.OperatingConstraint;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** System-owned duties follow observed operating modes, never BPMN scope lifetimes. */
final class FacilitySupervision {
    private static final class Duty {
        final OperatingConstraint rule;
        boolean active;
        boolean observedInactive;
        ObjectNode incident;
        ObjectNode lastIncident;
        Duty(OperatingConstraint rule){this.rule=rule;}
    }
    private final ObjectMapper json;
    private final Map<String,Duty> duties=new LinkedHashMap<>();
    FacilitySupervision(ObjectMapper json){this.json=json;}

    void validateReplacement(List<OperatingConstraint> rules) {
        for(var duty:duties.values())if(duty.active&&rules.stream().noneMatch(duty.rule::equals))
            throw new IllegalStateException("工作状态约束尚未取得解除证据，不能替换："+duty.rule.id());
    }

    ObjectNode assess(List<OperatingConstraint> rules,JsonNode facts) {
        duties.keySet().retainAll(rules.stream().map(OperatingConstraint::id).toList());
        ObjectNode result=json.createObjectNode();
        for(var rule:rules) {
            Duty duty=duties.compute(rule.id(),(id,old)->old!=null&&old.rule.equals(rule)?old:new Duty(rule));
            JsonNode activation=facts.path(rule.activation()),release=facts.path(rule.release());
            String status="unknown",reason="尚未确认适用工作状态或安全退出状态";
            boolean triggered=valid(activation),exited=valid(release),contradiction=triggered&&exited;
            if(!duty.active&&!triggered&&!(exited&&"violated".equals(activation.path("status").asText())))duty.observedInactive=false;
            if(triggered)duty.active=true;
            // Absence of an active state, a fault state or a missing observation never proves exit.
            if(exited&&"violated".equals(activation.path("status").asText())) {duty.active=false;duty.observedInactive=true;}
            ObjectNode value=result.putObject(rule.id()).put("label",rule.label()).put("active",duty.active)
                .put("activationFact",rule.activation()).put("releaseFact",rule.release());
            value.set("activation",activation.deepCopy());value.set("release",release.deepCopy());
            ObjectNode requirements=value.putObject("requirements");
            for(var req:rule.requirements())requirements.set(req.fact(),facts.path(req.fact()).deepCopy());
            if(duty.active) {
                status="valid";reason="";
                if(contradiction){status="violated";reason="工作状态与解除证据同时成立";}
                else if(!duty.observedInactive) {
                    status="unknown";reason="首次观测已处于工作状态，缺少从安全退出状态开始的监督覆盖";
                }
                else if(!"valid".equals(activation.path("status").asText())&&!exited) {
                    status="unknown";reason="工作状态丢失且尚无安全退出证据";
                }
                for(var req:rule.requirements()) {
                    JsonNode fact=facts.path(req.fact());String state=fact.path("status").asText("unknown");
                    if("violated".equals(state)||("unknown".equals(state)&&"valid".equals(status))) {
                        status=state;reason=req.fact()+"："+fact.path("reason").asText("缺少观测");
                    }
                }
                if(!"valid".equals(status)&&duty.incident==null) {
                    duty.incident=json.createObjectNode().put("status",status).put("reason",reason).put("occurredAt",System.currentTimeMillis())
                        .set("evidence",value.deepCopy());
                    duty.lastIncident=duty.incident;
                }
                if(duty.incident!=null){status=duty.incident.path("status").asText();reason=duty.incident.path("reason").asText();}
            } else if(exited&&"violated".equals(activation.path("status").asText())) {
                status="inactive";reason="已观测到约束适用状态结束";
            }
            value.put("status",status).put("reason",reason).put("latched",duty.active&&duty.incident!=null);
            if(duty.lastIncident!=null)value.set("lastIncident",duty.lastIncident.deepCopy());
            // Keep the last incident in this assessment so a brief failure cannot escape reconciliation.
            // A later activation starts a new duty only after an observed release.
            if(!duty.active&&"inactive".equals(status)&&duty.lastIncident!=null) {
                value.put("releasedAfterIncident",true);
                duty.incident=null;
            }
        }
        return result;
    }
    private static boolean valid(JsonNode value){return "valid".equals(value.path("status").asText());}
}
