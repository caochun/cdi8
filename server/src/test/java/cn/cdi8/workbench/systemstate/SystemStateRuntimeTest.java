package cn.cdi8.workbench.systemstate;

import cn.cdi8.workbench.integration.tango.TangoGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.ExtensionElement;
import org.flowable.bpmn.model.ExtensionAttribute;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.engine.runtime.ProcessInstanceQuery;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SystemStateRuntimeTest {
    static final String HEADER="<system-state-model xmlns=\""+SystemStateModel.NS+"\" id=\"test\" initial=\"initial\">";
    static final String FOOTER="</system-state-model>";
    static String condition(String states) { return "<require collection=\"targets\" component=\"seed shg\" states=\""+states+"\"/>"; }
    static String simple() { return HEADER+"<state id=\"initial\" label=\"Initial\">"+condition("good")+"</state>"+FOOTER; }

    static class Fixture {
        final ObjectMapper json=new ObjectMapper();
        final Map<String,Object> variables=new HashMap<>();
        final TangoGateway gateway=mock(TangoGateway.class);
        final RuntimeService runtime=mock(RuntimeService.class);
        final DelegateExecution execution=mock(DelegateExecution.class);
        final SystemStateRuntime states;
        ObjectNode snapshot;
        @SuppressWarnings("unchecked") Fixture(String xml) throws Exception {
            RepositoryService repository=mock(RepositoryService.class);
            ObjectProvider<RepositoryService> repositories=mock(ObjectProvider.class);
            ObjectProvider<RuntimeService> engines=mock(ObjectProvider.class);
            when(repositories.getObject()).thenReturn(repository);when(engines.getObject()).thenReturn(runtime);
            var definition=mock(ProcessDefinition.class);when(definition.getDeploymentId()).thenReturn("deployment");
            when(repository.getProcessDefinition("definition")).thenReturn(definition);
            when(repository.getResourceAsStream("deployment","system-model-test.xml"))
                .thenAnswer(call->new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            var bpmn=new BpmnModel();var process=new org.flowable.bpmn.model.Process();process.setId("test");bpmn.addProcess(process);
            var binding=new ExtensionElement();binding.setName("systemModel");binding.setNamespace(SystemStateModel.NS);
            var ref=new ExtensionAttribute();ref.setName("ref");ref.setValue("test");binding.addAttribute(ref);process.addExtensionElement(binding);
            when(repository.getBpmnModel("definition")).thenReturn(bpmn);
            when(execution.getProcessDefinitionId()).thenReturn("definition");when(execution.getProcessInstanceId()).thenReturn("pid");
            when(execution.getVariable(anyString())).thenAnswer(call->variables.get(call.getArgument(0)));
            doAnswer(call->{variables.put(call.getArgument(0),call.getArgument(1));return null;}).when(execution).setVariable(anyString(),any());
            when(runtime.getVariable(eq("pid"),anyString())).thenAnswer(call->variables.get(call.getArgument(1)));
            when(runtime.hasVariable(eq("pid"),anyString())).thenAnswer(call->variables.containsKey(call.getArgument(1)));
            doAnswer(call->{variables.put(call.getArgument(1),call.getArgument(2));return null;}).when(runtime).setVariable(eq("pid"),anyString(),any());
            var query=mock(ProcessInstanceQuery.class);var instance=mock(ProcessInstance.class);
            when(runtime.createProcessInstanceQuery()).thenReturn(query);when(query.processInstanceId("pid")).thenReturn(query);
            when(query.singleResult()).thenReturn(instance);when(instance.getProcessDefinitionId()).thenReturn("definition");
            variables.put("targets",List.of(Map.of("id","seed_01","component","seed")));
            when(gateway.connected()).thenReturn(true);
            when(gateway.snapshot("seed_01")).thenAnswer(call->snapshot.deepCopy());
            states=new SystemStateRuntime(json,gateway,repositories,engines);set("good",1);
            states.open(execution);
        }
        void set(String leaf,long revision) { snapshot=json.createObjectNode().put("source_id","device-instance").put("state_revision",revision).put("state_id",leaf).put("outer_state","healthy"); }
        void event(String leaf,long revision) {
            set(leaf,revision);states.observe(new TangoGateway.Notice(json.createObjectNode().put("device_id","seed_01").set("snapshot",snapshot.deepCopy())));
        }
        JsonNode current() throws Exception { return json.readTree(variables.get(SystemStateRuntime.STATE).toString()); }
    }

    @Test void entryConditionsCheckCurrentSnapshotAndDoNotReplayEarlierLegalIntermediateStates() throws Exception {
        String xml=HEADER+"<state id=\"initial\" label=\"Initial\">"+condition("start middle ready")+"</state>"+
            "<state id=\"working\" label=\"Working\">"+condition("ready running")+
            "<require collection=\"targets\" component=\"seed shg\" states=\"ready\" scope=\"entry\"/></state>"+
            "<transition id=\"begin\" from=\"initial\" to=\"working\"/>"+FOOTER;
        var f=new Fixture(xml);f.event("middle",2);f.event("ready",3);
        f.states.report(f.execution,"begin");
        assertEquals("working",f.current().path("state").asText());
        verify(f.gateway,times(1)).snapshot("seed_01");
        f.event("running",4);
        assertNull(f.states.inspect("pid",true),"Entry-only ready must not be required throughout normal running");
        assertEquals("valid",f.current().path("integrity").asText());
        assertEquals(4,f.current().path("evidence").path("seed_01").path("revision").asLong());
    }
    @Test void twoPredicatesOnOneDeviceCannotConsumeEachOthersTransientViolation() throws Exception {
        String broad="<require collection=\"targets\" component=\"seed\" outerStates=\"healthy\"/>";
        var f=new Fixture(HEADER+"<state id=\"initial\" label=\"Initial\">"+broad+condition("good")+"</state>"+FOOTER);
        assertNull(f.states.inspect("pid",true));
        f.event("bad",2);f.event("good",3);
        assertNotNull(f.states.inspect("pid",true));
        assertEquals("violated",f.current().path("integrity").asText());
        assertTrue(f.current().path("reason").asText().contains("bad"));
        assertNotNull(f.states.inspect("pid",true),"Recovery cannot clear an established violation");
    }
    @Test void sourceStateIsMonitoredUntilTheTransitionBoundary() throws Exception {
        String xml=HEADER+"<state id=\"initial\" label=\"Initial\">"+condition("good")+"</state>"+
            "<state id=\"next\" label=\"Next\">"+condition("good bad")+"</state>"+
            "<transition id=\"go\" from=\"initial\" to=\"next\"/>"+FOOTER;
        var f=new Fixture(xml);f.event("bad",2);f.event("good",3);
        assertThrows(IllegalStateException.class,()->f.states.report(f.execution,"go"));
        assertEquals("initial",f.current().path("state").asText());
        assertEquals("violated",f.current().path("integrity").asText());
    }
    @Test void unresolvedBusinessRulesBlockTransitionWithoutPretendingSourceStateFailed() throws Exception {
        var f=new Fixture(HEADER+"<state id=\"initial\" label=\"Initial\">"+condition("good")+"</state>"+
            "<state id=\"next\" label=\"Next\">"+condition("good")+"</state>"+
            "<transition id=\"go\" from=\"initial\" to=\"next\" blocked=\"缺少权威发射证据\"/>"+FOOTER);
        var error=assertThrows(IllegalStateException.class,()->f.states.report(f.execution,"go"));
        assertTrue(error.getMessage().contains("缺少权威发射证据"));
        assertEquals("valid",f.current().path("integrity").asText());
        assertEquals("initial",f.current().path("state").asText());
        assertTrue(f.current().path("history").isEmpty());
    }
    @Test void missingOrUnmatchedParticipantsCannotSatisfyAUniversalCondition() throws Exception {
        for(Object targets:List.of(List.of(),List.of(Map.of("id","pump_01","component","pump")),List.of(Map.of("component","seed")))) {
            var f=new Fixture(simple());f.variables.put("targets",targets);
            assertNotNull(f.states.inspect("pid",true));
            assertEquals("violated",f.current().path("integrity").asText());
        }
    }
    @Test void lossOfEvidenceContinuityLatchesUnknownEvenWhenCurrentStateLooksGood() throws Exception {
        for(String failure:List.of("source","revision","missing","connection","eventGap","restart")) {
            var f=new Fixture(simple());f.set("good",5);assertNull(f.states.inspect("pid",true));
            switch(failure) {
                case "source" -> f.snapshot.put("source_id","replacement");
                case "revision" -> f.snapshot.put("state_revision",4);
                case "missing" -> f.snapshot.remove("source_id");
                case "connection" -> f.states.observe(new TangoGateway.Notice(f.json.createObjectNode().put("type","connection_lost")));
                case "eventGap" -> { for(int i=0;i<16385;i++)f.states.observe(new TangoGateway.Notice(f.json.createObjectNode().put("type","heartbeat"))); }
                case "restart" -> {
                    var state=(ObjectNode)f.current();((ObjectNode)state.path("evidence")).put("eventEpoch","previous-server-instance");
                    f.variables.put(SystemStateRuntime.STATE,state.toString());
                }
            }
            assertNotNull(f.states.inspect("pid",true),failure);
            assertEquals("unknown",f.current().path("integrity").asText(),failure);
        }
    }
    @Test void safetyInterlockAndDeviceFaultAreClassifiedAndLatchedTogether() throws Exception {
        String xml=HEADER+"<anomaly id=\"deviceFault\" label=\"设备故障\" category=\"deviceFault\" states=\"faultLock commFault timeoutFault\" outerStates=\"fault\"/>"+
            "<anomaly id=\"safetyInterlock\" label=\"安全联锁触发\" category=\"safetyInterlock\" states=\"interlockTripped\"/>"+
            "<anomaly id=\"stateConditionLost\" label=\"系统条件失效\" category=\"stateConditionLost\"/>"+
            "<anomaly id=\"evidenceUnknown\" label=\"状态未知\" category=\"unknown\"/>"+
            "<state id=\"initial\" label=\"Initial\">"+condition("good")+"</state>"+FOOTER;
        var f=new Fixture(xml);
        f.variables.put("allComponents",List.of(Map.of("id","seed_01","component","seed"),Map.of("id","context_01","component","context")));
        f.set("faultLock",2);f.snapshot.put("outer_state","fault").put("fault_origin","power-supply").putArray("faults").add("overcurrent");
        f.states.observe(new TangoGateway.Notice(f.json.createObjectNode().put("device_id","seed_01").put("connected",true).set("snapshot",f.snapshot.deepCopy())));
        var interlock=f.json.createObjectNode().put("source_id","context-instance").put("state_revision",3).put("state_id","interlockTripped").put("outer_state","interlocked");
        f.states.observe(new TangoGateway.Notice(f.json.createObjectNode().put("device_id","context_01").put("connected",true).set("snapshot",interlock)));

        assertNotNull(f.states.inspect("pid",true));
        assertEquals("violated",f.current().path("integrity").asText());
        assertEquals("latched",f.current().path("anomalyStatus").asText());
        assertEquals(2,f.current().path("incidents").size());
        assertEquals("safetyInterlock",f.current().path("incidents").get(1).path("type").asText());
        assertEquals("deviceFault",f.current().path("incidents").get(0).path("type").asText());
        assertEquals("overcurrent",f.current().path("incidents").get(0).path("faults").get(0).asText());

        f.event("good",4);
        assertNotNull(f.states.inspect("pid",true),"A recovered snapshot must not clear a latched incident");
        assertEquals(2,f.current().path("incidents").size());
    }
    @Test void communicationLossIsRecordedAsUnknownNotAsDeviceFault() throws Exception {
        String xml=HEADER+"<anomaly id=\"deviceFault\" label=\"设备故障\" category=\"deviceFault\" states=\"faultLock\" outerStates=\"fault\"/>"+
            "<anomaly id=\"stateConditionLost\" label=\"系统条件失效\" category=\"stateConditionLost\"/>"+
            "<anomaly id=\"evidenceUnknown\" label=\"状态未知\" category=\"unknown\"/>"+
            "<state id=\"initial\" label=\"Initial\">"+condition("good")+"</state>"+FOOTER;
        var f=new Fixture(xml);
        f.states.observe(new TangoGateway.Notice(f.json.createObjectNode().put("type","connection_lost")));
        assertNotNull(f.states.inspect("pid",true));
        assertEquals("unknown",f.current().path("integrity").asText());
        assertEquals("evidenceUnknown",f.current().path("incidents").get(0).path("type").asText());
        assertEquals("unknown",f.current().path("incidents").get(0).path("category").asText());
    }
    @Test void unsuccessfulEntryCheckCannotEstablishTheDestinationState() throws Exception {
        var f=new Fixture(HEADER+"<state id=\"initial\" label=\"Initial\">"+condition("good")+"</state>"+
            "<state id=\"next\" label=\"Next\">"+condition("good ready")+
            "<require collection=\"targets\" component=\"seed\" states=\"ready\" scope=\"entry\"/></state>"+
            "<transition id=\"go\" from=\"initial\" to=\"next\"/>"+FOOTER);
        assertThrows(IllegalStateException.class,()->f.states.report(f.execution,"go"));
        assertEquals("initial",f.current().path("state").asText());
        assertTrue(f.current().path("history").isEmpty());
    }
}
