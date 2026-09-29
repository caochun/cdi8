package cn.cdi8.control.model;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ControlModelTest {
    static final ObjectMapper JSON=new ObjectMapper(),YAML=new ObjectMapper(new YAMLFactory());
    static ObjectNode bundle() throws Exception {
        ObjectNode b=JSON.createObjectNode();
        for(var e:Map.of("actions","action-contracts.yaml","catalog","device-catalog.yaml","workflow","workflow-bindings.yaml").entrySet())
            try(var stream=ControlModelTest.class.getResourceAsStream("/control-model/"+e.getValue())){b.set(e.getKey(),YAML.readTree(stream));}
        return b;
    }
    static InputStream bpmn(){return ControlModelTest.class.getResourceAsStream("/processes/laser-joint.bpmn20.xml");}
    @Test void referenceAndNormalPathChecksExplicitlyDoNotClaimFullProof() throws Exception {
        var m=new ControlModel(bundle());m.validate();var findings=BpmnChecks.check(m,bpmn());
        assertEquals(14,findings.stream().filter(f->f.detail().contains("post/preconditions")).count());
        assertTrue(findings.stream().anyMatch(f->f.status().equals("UNDEFINED")));
    }
    @Test void unsupportedOperatorsUnknownFieldsAndUnknownValuesAreRejected() throws Exception {
        var fields=bundle().get("actions").get("state_fields");
        for(String text:List.of("{\"field\":\"main_state\",\"eval\":\"true\"}","{\"field\":\"typo\",\"equals\":null}","{\"field\":\"main_state\",\"equals\":\"typo\"}","{\"all\":[]}"))
            assertThrows(IllegalArgumentException.class,()->Conditions.validate(JSON.readTree(text),fields));
    }
    @Test void missingIsNotNullAndNestedAllAnyWork() throws Exception {
        var rule=JSON.readTree("{\"all\":[{\"field\":\"active_action\",\"equals\":null},{\"any\":[{\"field\":\"main_state\",\"in\":[\"正常\",\"就绪\"]}]}]}");
        Map<String,Object> state=new HashMap<>(Map.of("main_state","正常"));
        assertFalse(Conditions.matches(rule,state));state.put("active_action",null);assertTrue(Conditions.matches(rule,state));
        state.put("main_state","异常");assertFalse(Conditions.matches(rule,state));
    }
    @Test void badBindingsAndEmptyGroupsFailAtLoad() throws Exception {
        ObjectNode b=bundle();((ObjectNode)b.at("/workflow/nodes/seed_emit/contracts")).put("seed","shg.frequency_doubled_emit");
        var wrongType=new ControlModel(b);assertThrows(IllegalArgumentException.class,wrongType::validate);
        b=bundle();((ObjectNode)b.at("/catalog/groups")).set("seed",JSON.createArrayNode());var bad=new ControlModel(b);
        assertThrows(IllegalArgumentException.class,bad::validate);
    }
    @Test void recipeParametersHaveTypesAndRejectUnknownInputs() throws Exception {
        var m=new ControlModel(bundle());m.validate();
        m.validateParameters(JSON.readTree("{\"seed_configure\":{\"recipe_id\":\"shot-42\"}}"));
        for(String input:List.of("{\"seed_configure\":{\"recipe_id\":42}}","{\"seed_configure\":{\"unknown\":true}}","{\"unknown\":{}}"))
            assertThrows(IllegalArgumentException.class,()->m.validateParameters(JSON.readTree(input)));
    }
    @Test void unsupportedModelPropertiesAndBadSchemasAreNotSilentlyIgnored() throws Exception {
        var extra=bundle();((ObjectNode)extra.at("/actions/contracts/seed.seed_source_emit")).put("hold","ignored?");
        assertThrows(IllegalArgumentException.class,()->new ControlModel(extra).validate());
        var schema=bundle();((ObjectNode)schema.at("/actions/state_fields/main_state")).put("nullable","yes");
        assertThrows(IllegalArgumentException.class,()->new ControlModel(schema).validate());
        var enumType=bundle();((ObjectNode)enumType.at("/actions/contracts/seed.parameter_dispatch/parameters/recipe_id"))
            .set("values",JSON.createArrayNode().add(12));
        assertThrows(IllegalArgumentException.class,()->new ControlModel(enumType).validate());
    }
    @Test void changingOnlyCatalogChangesFanoutVariables() throws Exception {
        var b=bundle();((ArrayNode)b.at("/catalog/groups/seed")).remove(2);((ArrayNode)b.at("/catalog/groups/all")).remove(2);((ObjectNode)b.at("/catalog/devices")).remove("seed_03");
        var model=new ControlModel(b);model.validate();BpmnChecks.check(model,bpmn());
        assertEquals(List.of("seed_01","seed_02"),model.variables().get("seedInstances"));
        assertEquals(List.of("seed_01","seed_02","shg_01"),model.variables().get("allInstances"));
    }
    @Test void requiredInputsAreCheckedAtRunStartNotConfigurationLoad() throws Exception {
        var b=bundle();((ObjectNode)b.at("/actions/contracts/seed.parameter_dispatch/parameters/recipe_id")).put("required",true);
        var m=new ControlModel(b);m.validate();
        assertThrows(IllegalArgumentException.class,()->m.validateParameters(JSON.createObjectNode()));
        m.validateParameters(JSON.readTree("{\"seed_configure\":{\"recipe_id\":\"required-recipe\"}}"));
    }
    @Test void normalPathConflictGivesNodeLocation() throws Exception {
        var b=bundle();for(var leaf:b.at("/actions/contracts/seed.parameter_dispatch/completion/all"))
            if(leaf.path("field").asText().equals("current_state"))((ObjectNode)leaf).put("equals","自检完成");
        var model=new ControlModel(b);model.validate();
        var ex=assertThrows(IllegalArgumentException.class,()->BpmnChecks.check(model,bpmn()));
        assertTrue(ex.getMessage().contains("gate")||ex.getMessage().contains("emit"));
    }
    @Test void wrongBpmnContractOrInstanceBindingIsRejected() throws Exception {
        String xml;try(var input=bpmn()){xml=new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
        var model=new ControlModel(bundle());model.validate();
        for(String bad:List.of(xml.replace("devices.prepare(execution, 'seed_self_test')","devices.prepare(execution, 'absent')"),
                               xml.replace("devices.verify(execution, 'seed_self_test')","devices.verify(execution, 'seed_check')"),
                               xml.replace("devices.enqueue(execution, 'shg_self_test', shgInstance)","devices.enqueue(execution, 'shg_self_test', deviceId)")))
            assertThrows(IllegalArgumentException.class,()->BpmnChecks.check(model,new ByteArrayInputStream(bad.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }
    @Test void contractSuccessAndAllowedPreconditionsMatchIndependentSimulatorModels() throws Exception {
        var model=new ControlModel(bundle());model.validate();
        for(String type:List.of("seed","shg")) {
            String filename=type.equals("seed")?"excel-seed-source-state-machine.yaml":"excel-shg-injector-state-machine.yaml";
            var reference=YAML.readTree(Path.of("simulator/gxlf_sim_system/models",filename).toFile());
            for(var action:reference.get("actions")) {
                var contract=model.contract(type+"."+action.get("id").asText());
                var expected=Conditions.fixedState(contract.get("completion")).orElseThrow();
                for(String field:List.of("main_state","current_state","business_state"))
                    assertEquals(action.get("success").get(field).asText(),expected.get(field));
                for(var main:model.actions().at("/state_fields/main_state/values"))for(var current:model.actions().at("/state_fields/current_state/values")) {
                    Map<String,Object> state=new HashMap<>();state.put("main_state",main.asText());state.put("current_state",current.asText());state.put("active_action",null);
                    boolean allowed=true;for(var it=action.get("precondition").fields();it.hasNext();) {
                        var entry=it.next();boolean found=false;for(var option:entry.getValue())found|=option.asText().equals(state.get(entry.getKey()));allowed&=found;
                    }
                    assertEquals(allowed,Conditions.matches(contract.get("precondition"),state),type+"/"+action.get("id")+state);
                }
            }
        }
    }
}
