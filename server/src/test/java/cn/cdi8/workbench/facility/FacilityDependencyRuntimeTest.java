package cn.cdi8.workbench.facility;

import cn.cdi8.workbench.integration.tango.TangoGateway;
import cn.cdi8.workbench.systemdefinition.SystemDefinitionModel;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class FacilityDependencyRuntimeTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static String model(String name)throws Exception{return Files.readString(Path.of("examples/gxlf/system/"+name));}
    static class Transport extends TangoGateway {
        final Map<String,ObjectNode> values=new LinkedHashMap<>();
        final Map<String,Map<String,String>> devices=new LinkedHashMap<>();
        Transport(){super(JSON,event->{},"http://unused",false);}
        void add(String id,String component,String outer) {
            devices.put(id,Map.of("id",id,"component",component));
            values.put(id,JSON.createObjectNode().put("source_id",id+"-source").put("state_revision",1).put("outer_state",outer).put("state_id",outer+"-leaf"));
        }
        @Override public boolean connected(){return true;}
        @Override public JsonNode snapshot(String id){return values.get(id).deepCopy();}
        @Override public JsonNode catalog(){var out=JSON.createArrayNode();devices.values().forEach(d->out.addObject().put("id",d.get("id")).put("component",d.get("component")).put("connected",true));return out;}
    }
    Transport transport;FacilityStateRuntime states;FacilityDependencyRuntime dependencies;Map<String,String> instances;
    @BeforeEach void setup() {
        transport=new Transport();
        transport.add("seed_01","seed","emitting");transport.add("shg_01","shg","emitting");
        transport.add("preamp_01","preamp","wakeup");transport.add("preamp_02","preamp","wakeup");
        transport.add("multipass_01","multipass","ready");transport.add("multipass_07","multipass","ready");
        transport.add("synchronization_01","synchronization","repeatMode");transport.add("synchronization_07","synchronization","repeatMode");
        states=new FacilityStateRuntime(JSON,transport,event->{if(dependencies!=null)dependencies.changed((FacilityStateRuntime.Changed)event);});
        dependencies=new FacilityDependencyRuntime(JSON,states);states.refresh();
        instances=states.binding().topology().bind(transport.devices,Map.of());
    }
    @AfterEach void close(){transport.close();}
    ObjectNode plan(String target,Map<String,String> sources) {
        return dependencies.plan(UUID.randomUUID().toString(),"multipass","collimation",target,List.of(1,7),sources,"seed",instances);
    }
    void change(String id,String outer) {
        var value=transport.values.get(id);value.put("outer_state",outer).put("state_id",outer+"-leaf").put("state_revision",value.path("state_revision").asLong()+1);
        states.observe(new TangoGateway.Notice(JSON.createObjectNode().put("device_id",id).put("connected",true).set("snapshot",value.deepCopy())));
    }
    @Test void followsOnlySelectedSourceAndPreampGroup() {
        change("seed_01","fault");change("preamp_02","fault");change("synchronization_07","fault");
        var result=dependencies.check(plan("multipass_01",Map.of("1","shg")).toString(),false,true);
        assertEquals("valid",result.path("status").asText(),result.toPrettyString());
        Set<String> devices=new HashSet<>();result.path("requirements").forEach(r->devices.add(r.path("device").asText()));
        assertEquals(Set.of("shg_01","preamp_01","synchronization_01"),devices);
    }
    @Test void secondGroupUsesItsOwnPreampAndClock() {
        change("preamp_01","fault");
        var result=dependencies.check(plan("multipass_07",Map.of()).toString(),false,true);
        assertEquals("valid",result.path("status").asText());
        assertTrue(result.path("requirements").toString().contains("preamp-02"));
        assertFalse(result.path("requirements").toString().contains("preamp-01"));
    }
    @Test void missingBindingBlocksInsteadOfUsingAnyOtherDevice() {
        instances=new HashMap<>(instances);instances.remove("preamp-01");
        var result=dependencies.check(plan("multipass_01",Map.of()).toString(),false,true);
        assertEquals("unknown",result.path("status").asText());assertTrue(result.path("reason").asText().contains("preamp-01"));
    }
    @Test void upstreamFailureIsLatchedEvenIfItRecoversBeforeNextPoll() {
        var plan=plan("multipass_01",Map.of());assertEquals("valid",dependencies.check(plan.toString(),false,true).path("status").asText());
        change("seed_01","fault");change("seed_01","emitting");
        var result=dependencies.check(plan.toString(),true,false);
        assertEquals("violated",result.path("status").asText());assertTrue(result.path("latched").asBoolean());
        assertTrue(result.path("reason").asText().contains("seed-01"));
    }
    @Test void observedRevisionRollbackInvalidatesInput() {
        var plan=plan("multipass_01",Map.of());dependencies.check(plan.toString(),false,true);
        transport.values.get("seed_01").put("state_revision",0);states.refresh();
        assertEquals("unknown",dependencies.check(plan.toString(),true,false).path("status").asText());
    }
    @Test void staleInputCannotRemainValid()throws Exception {
        states.activate(model("gxlf-facility.system-definition.xml"),model("gxlf-facility-binding.xml"),
            model("gxlf-facility-state-projection.xml").replace("evidenceMaxAgeMs=\"2000\"","evidenceMaxAgeMs=\"30\""));
        var plan=plan("multipass_01",Map.of());assertEquals("valid",dependencies.check(plan.toString(),false,true).path("status").asText());
        Thread.sleep(60);
        assertEquals("unknown",dependencies.check(plan.toString(),true,false).path("status").asText());
    }
    @Test void entryOnlyConditionIsReleasedAfterDispatch()throws Exception {
        states.activate(model("gxlf-facility.system-definition.xml").replace("maintain=\"execution\"","maintain=\"start\""),model("gxlf-facility-binding.xml"),model("gxlf-facility-state-projection.xml"));
        var plan=plan("multipass_01",Map.of());assertEquals("valid",dependencies.check(plan.toString(),false,true).path("status").asText());
        change("seed_01","fault");
        assertEquals("valid",dependencies.check(plan.toString(),true,false).path("status").asText());
    }
    @Test void restartCannotClaimContinuousCoverageOfAnAlreadyDispatchedCommand() {
        var plan=plan("multipass_01",Map.of());
        assertEquals("unknown",dependencies.check(plan.toString(),true,false).path("status").asText());
    }
    @Test void completionReleasesMonitorAndNextInvocationHasNewChecks() {
        var first=plan("multipass_01",Map.of());dependencies.check(first.toString(),false,true);dependencies.release(first.path("id").asText());
        change("seed_01","fault");change("seed_01","emitting");
        var second=plan("multipass_01",Map.of());assertNotEquals(first.path("id"),second.path("id"));
        assertEquals("valid",dependencies.check(second.toString(),false,true).path("status").asText());
    }
    @Test void parameterConfigurationDoesNotAcquireOpticalDependency() {
        change("seed_01","fault");
        assertNull(dependencies.plan("command","multipass","parameter-receive","multipass_01",List.of(1),Map.of(),"seed",instances));
    }
    @Test void invalidContractReferencesAreRejected()throws Exception {
        String xml=model("gxlf-facility.system-definition.xml");
        for(String invalid:List.of(xml.replace("maintain=\"execution\"","maintain=\"forever\""),
            xml.replace("inputPortRef=\"optical-in\"","inputPortRef=\"missing-port\""),
            xml.replace("pathRef=\"optical-path\"","pathRef=\"missing-path\""),
            xml.replace("signalRef=\"alignment-light\" factRef=\"seedEmitting\"","signalRef=\"other\" factRef=\"seedEmitting\"")))
            assertThrows(IllegalArgumentException.class,()->SystemDefinitionModel.parse(invalid));
    }
    @Test void invalidFactMappingIsRejected()throws Exception {
        String xml=model("gxlf-facility-state-projection.xml").replace("id=\"seedEmitting\" label=\"种子源保持出光\" outerStates=\"emitting\" typeRef=\"seed\"","id=\"seedEmitting\" label=\"种子源保持出光\" outerStates=\"emitting\" typeRef=\"shg\"");
        assertThrows(IllegalArgumentException.class,()->FacilityStateProjectionModel.parse(xml).validateDependencies(states.definition()));
    }
    @Test void topologyRejectsCyclesBeforeRuntime()throws Exception {
        String xml=model("gxlf-facility.system-definition.xml");
        xml=xml.replace("<output signalRef=\"alignment-light\" factRef=\"seedEmitting\" id=\"alignment-light\" />",
            "<output id=\"alignment-light\" signalRef=\"alignment-light\" factRef=\"seedEmitting\"><requirements><inputRequirement id=\"cycle\" pathRef=\"optical-path\" inputPortRef=\"optical-in\" signalRef=\"alignment-light\" /></requirements></output>");
        int at=xml.indexOf("<port id=\"optical-out\"");xml=xml.substring(0,at)+"<port id=\"optical-in\" direction=\"in\" interfaceRef=\"optical-link\" />"+xml.substring(at);
        xml=xml.replace("<connection id=\"seed-to-preamp\"",
            "<connection id=\"cycle\" relation=\"optical-feed\"><source instanceSetRef=\"preamp\" portRef=\"optical-out\" /><target instanceSetRef=\"seed\" portRef=\"optical-in\" /></connection><connection id=\"seed-to-preamp\"");
        String cyclic=xml;assertTrue(assertThrows(IllegalArgumentException.class,()->SystemDefinitionModel.parse(cyclic)).getMessage().contains("环"));
    }
    @Test void sourceSelectionRespectsFacilityAllocation() {
        var contracts=states.definition().dependencies();
        assertTrue(contracts.sourceAllowed("shg",8));assertFalse(contracts.sourceAllowed("shg",9));assertTrue(contracts.sourceAllowed("seed",9));
    }
    @Test void explicitBindingWorksWithArbitraryRuntimeId() {
        transport.add("arbitrary-device","multipass","ready");
        var resolved=states.binding().topology().bind(transport.devices,Map.of("multipass-01","arbitrary-device"));
        assertEquals("arbitrary-device",resolved.get("multipass-01"));
        assertThrows(IllegalArgumentException.class,()->states.binding().topology().bind(transport.devices,Map.of("multipass-01","preamp_01")));
    }
    @Test void nonCanonicalSourceSelectionCannotSilentlyUseTheDefaultSource() {
        var execution=org.mockito.Mockito.mock(org.flowable.engine.delegate.DelegateExecution.class);
        var variables=Map.<String,Object>of("shotId","test-shot","recipeRef","recipe","recipeVersion","1",
            "beamlines",List.of(1),"defaultSource","seed","sourceSelections",Map.of("01","shg"));
        org.mockito.Mockito.when(execution.getVariable(org.mockito.ArgumentMatchers.anyString())).thenAnswer(call->variables.get(call.getArgument(0)));
        var shots=new cn.cdi8.workbench.run.ShotRunRuntime(JSON,states);
        assertTrue(assertThrows(IllegalArgumentException.class,()->shots.open(execution)).getMessage().contains("十进制编号"));
    }
}
