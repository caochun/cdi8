package cn.cdi8.workbench;

import cn.cdi8.workbench.application.WorkbenchService;
import cn.cdi8.workbench.integration.tango.TangoGateway;
import cn.cdi8.workbench.model.ModelStore;
import cn.cdi8.workbench.model.ModelValidation;
import cn.cdi8.workbench.run.WorkflowService;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.flowable.engine.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.ApplicationEventPublisher;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:workbench-test;DB_CLOSE_DELAY=-1",
    "control.events-enabled=false","control.pump-delay=3600000","flowable.async-executor-activate=false",
    "control.workbench-directory=${java.io.tmpdir}/cdi8-workbench-test-${random.uuid}"})
class WorkbenchIntegrationTest {
    @Autowired ModelStore store;@Autowired WorkbenchService workbench;@Autowired ModelValidation validation;
    @Autowired WorkflowService workflows;@Autowired RuntimeService runtime;@Autowired RepositoryService repository;
    @Autowired ObjectMapper json;@Autowired TestGateway tango;
    @TestConfiguration static class Configuration {
        @Bean @Primary TestGateway testGateway(ObjectMapper json,ApplicationEventPublisher events){return new TestGateway(json,events);}
    }
    static class TestGateway extends TangoGateway {
        final ObjectMapper json;JsonNode catalog,state;
        TestGateway(ObjectMapper json,ApplicationEventPublisher events){super(json,events,"http://127.0.0.1:1",false);this.json=json;}
        @Override public boolean connected(){return true;}
        @Override public JsonNode catalog(){return catalog;}
        @Override public JsonNode cached(String id){return state;}
        @Override public JsonNode request(String method,String path,Object body){return json.createObjectNode().put("id","test-environment");}
        @Override public JsonNode snapshot(String id) { return state; }
        @Override public JsonNode execute(String device, String id, String action, Object parameters) {
            ((ObjectNode)state).put("state_id", "active").put("outer_state", "fault").putNull("active_command_id");
            return json.createObjectNode().put("command_id", id).put("action", action).put("status", "succeeded");
        }
    }
    ObjectNode config;
    String process(String key,String inner){return "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" targetNamespace=\"test\"><process id=\""+key+"\" name=\""+key+"\" isExecutable=\"true\"><startEvent id=\"start\"/><sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"call\"/>"+inner+"<sequenceFlow id=\"f2\" sourceRef=\"call\" targetRef=\"end\"/><endEvent id=\"end\"/></process></definitions>";}
    String simple(String key){return process(key,"<userTask id=\"call\" name=\"pause\"/>");}
    JsonNode model(String id,String xml){store.save(id,id+".bpmn20.xml","bpmn",xml);return workbench.publish(id);}
    @BeforeEach void prepare() {
        for(var p:runtime.createProcessInstanceQuery().list())runtime.deleteProcessInstance(p.getId(),"cleanup");
        var state=json.createObjectNode().put("state_id","unqualified").put("outer_state","notReady");
        var device=json.createObjectNode().put("id","seed_01").put("component","seed").put("connected",true);device.set("snapshot",state);
        tango.catalog=json.createArrayNode().add(device);tango.state=state;
        config=json.createObjectNode().put("entry","parent").put("autoComplete",false);
        var source=store.get("seed-source-state-machine");var version=source.path("revisions").get(0).path("id").asText();
        config.putArray("devices").addObject().put("model",source.path("id").asText()).put("revision",version).put("component","seed").put("count",1);
    }
    @Test void immutableVersionsAndPinnedDependenciesRemainStableAfterEdits() {
        var child=model("child",simple("child_process"));
        String parentXml=process("parent_process","<callActivity id=\"child_process\" calledElement=\"child_process\"/>")
            .replace("targetRef=\"call\"","targetRef=\"child_process\"").replace("sourceRef=\"call\"","sourceRef=\"child_process\"")
            .replace("targetNamespace=","xmlns:bpmndi=\"http://www.omg.org/spec/BPMN/20100524/DI\" xmlns:dc=\"http://www.omg.org/spec/DD/20100524/DC\" targetNamespace=")
            .replace("</definitions>","<bpmndi:BPMNDiagram id=\"diagram\"><bpmndi:BPMNPlane id=\"plane\" bpmnElement=\"parent_process\"><bpmndi:BPMNShape id=\"shape\" bpmnElement=\"child_process\"><dc:Bounds x=\"100\" y=\"100\" width=\"100\" height=\"80\"/></bpmndi:BPMNShape></bpmndi:BPMNPlane></bpmndi:BPMNDiagram></definitions>");
        var parent=model("parent",parentXml);
        config.putArray("processes").addObject().put("model","parent").put("revision",parent.path("id").asText());
        ((ArrayNode)config.get("processes")).addObject().put("model","child").put("revision",child.path("id").asText());
        config=(ObjectNode)workbench.apply(config);
        var run=workbench.start(config);String id=run.get("id").toString();
        String deployed=workflows.runXml(id);
        assertThat(deployed).contains("calledElement=\"child_process__lab_").contains("bpmnElement=\"child_process\"");
        store.save("child","child.bpmn20.xml","bpmn",simple("changed_child"));
        assertThat(store.revision("child",child.path("id").asText()).path("xml").asText()).contains("child_process").doesNotContain("changed_child");
        assertThat(workflows.runXml(id)).isEqualTo(deployed);
        workflows.stop(id,"test stop");assertThat(workflows.view(id).get("outcome")).isEqualTo("FAILED");
    }
    @Test void missingChildVersionIsRejectedBeforeStarting() {
        var parent=model("parent",process("parent_process","<callActivity id=\"call\" calledElement=\"unselected\"/>"));
        config.putArray("processes").addObject().put("model","parent").put("revision",parent.path("id").asText());
        config=(ObjectNode)workbench.apply(config);
        assertThatThrownBy(()->workbench.start(config)).hasMessageContaining("子流程版本");
        assertThat(runtime.createProcessInstanceQuery().count()).isZero();
    }
    @Test void changingDevicesRequiresReapplyingEnvironment() {
        config=(ObjectNode)workbench.apply(config);((ObjectNode)config.path("devices").get(0)).put("count",2);
        assertThatThrownBy(()->workbench.start(config)).hasMessageContaining("重新应用");
    }
    @Test void arbitraryConditionWaitUsesModelDeclarationRatherThanNodeName() {
        String xml=process("declared_condition","<receiveTask id=\"call\" lab:condition=\"alignment_ready\" lab:collection=\"contextTargets\"/>")
            .replace("targetNamespace=", "xmlns:lab=\"https://cdi8.cn/workbench\" targetNamespace=");
        var version=model("parent",xml);
        config.putArray("processes").addObject().put("model","parent").put("revision",version.path("id").asText());
        config.putObject("bindings").putArray("contextTargets").add("seed_01");
        ((ObjectNode)tango.state).putObject("conditions").put("alignment_ready",false);
        config=(ObjectNode)workbench.apply(config);
        var run=workbench.start(config);String id=run.get("id").toString();
        workflows.pump();assertThat(runtime.getActiveActivityIds(id)).contains("call");
        assertThat(workflows.view(id).get("waits").toString()).contains("alignment_ready");
        ((ObjectNode)tango.state.path("conditions")).put("alignment_ready",true);
        workflows.pump();assertThat(workflows.view(id).get("outcome")).isEqualTo("SUCCEEDED");
    }
    @Test void malformedBpmnReturnsValidationDiagnostics() {
        assertThat(validation.validate("bpmn","<definitions>").path("valid").asBoolean()).isFalse();
        assertThat(validation.validate("bpmn",simple("new_process")).path("valid").asBoolean()).isTrue();
    }
    private String startGeneric(String xml, Map<String,Object> variables) {
        var deployment = repository.createDeployment().addString("generic.bpmn20.xml", xml).deploy();
        var definition = repository.createProcessDefinitionQuery().deploymentId(deployment.getId()).singleResult();
        return workflows.startDefinition(definition.getId(), new HashMap<>(variables)).get("id").toString();
    }

    private String conditionFlow(boolean maintain) {
        String nodes = "<receiveTask id=\"call\" lab:condition=\"safety_permit\" lab:maintain=\"" + maintain
            + "\"/><sequenceFlow id=\"middle\" sourceRef=\"call\" targetRef=\"later\"/>"
            + "<receiveTask id=\"later\" lab:condition=\"finish_ready\"/>";
        return process("generic_conditions", nodes)
            .replace("targetNamespace=", "xmlns:lab=\"https://cdi8.cn/workbench\" targetNamespace=")
            .replace("id=\"f2\" sourceRef=\"call\"", "id=\"f2\" sourceRef=\"later\"");
    }

    @Test void genericConditionNamesDoNotEnableGxlfContinuousChecks() {
        ObjectNode conditions = ((ObjectNode)tango.state).putObject("conditions");
        conditions.put("safety_permit", true).put("finish_ready", false);
        var target = Map.of("id", "seed_01", "component", "custom");
        String id = startGeneric(conditionFlow(false), Map.of("contextTargets", List.of(target)));
        workflows.pump();
        assertThat(runtime.getActiveActivityIds(id)).contains("later");
        assertThat(runtime.getVariable(id, "safetyRequired")).isNull();
        conditions.put("safety_permit", false);
        workflows.pump();
        assertThat(workflows.view(id).get("outcome")).isEqualTo("RUNNING");
        assertThat(runtime.getActiveActivityIds(id)).contains("later");
        conditions.put("finish_ready", true);
        workflows.pump();
        assertThat(workflows.view(id).get("outcome")).isEqualTo("SUCCEEDED");
    }

    @Test void genericMaintainedConditionsStillStopTheRunWhenEvidenceIsLost() {
        ObjectNode conditions = ((ObjectNode)tango.state).putObject("conditions");
        conditions.put("safety_permit", true).put("finish_ready", false);
        var target = Map.of("id", "seed_01", "component", "custom");
        String id = startGeneric(conditionFlow(true), Map.of("contextTargets", List.of(target)));
        workflows.pump();
        assertThat(runtime.getActiveActivityIds(id)).contains("later");
        conditions.put("safety_permit", false);
        workflows.pump();
        assertThat(workflows.view(id).get("outcome")).isEqualTo("FAILED");
        assertThat(workflows.view(id).get("failureReason").toString()).contains("持续条件失效");
    }

    @Test void genericCommandsCanReuseActionNamesAndStateLabelsWithoutGxlfSemantics() {
        String node = "<serviceTask id=\"call\" flowable:delegateExpression=\"${componentCommand}\">"
            + "<extensionElements><flowable:field name=\"componentCommand\" stringValue=\"true\"/>"
            + "<flowable:field name=\"action\" stringValue=\"preamp_repeat\"/>"
            + "<flowable:field name=\"expectedState\" stringValue=\"active\"/></extensionElements></serviceTask>";
        String xml = process("generic_command", node)
            .replace("targetNamespace=", "xmlns:flowable=\"http://flowable.org/bpmn\" targetNamespace=");
        var target = Map.of("id", "seed_01", "component", "custom");
        ((ObjectNode)tango.state).put("outer_state", "fault");
        String id = startGeneric(xml, Map.of("target", target, "allComponents", List.of(target),
            "sourceTargets", List.of(target), "sourcesRequired", true));
        workflows.pump();
        assertThat(workflows.view(id).get("outcome")).isEqualTo("SUCCEEDED");
        assertThat(tango.state.path("outer_state").asText()).isEqualTo("fault");
        assertThat(json.valueToTree(workflows.view(id)).path("participants").get(0).path("id").asText())
            .isEqualTo("seed_01");
    }

}
