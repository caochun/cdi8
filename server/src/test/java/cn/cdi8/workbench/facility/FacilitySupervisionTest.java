package cn.cdi8.workbench.facility;

import cn.cdi8.workbench.systemdefinition.SystemDefinitionModel;
import cn.cdi8.workbench.systemdefinition.CapabilityDependencies;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FacilitySupervisionTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static final SystemDefinitionModel.OperatingConstraint RULE=new SystemDefinitionModel.OperatingConstraint(
        "energy","储能期间保持联锁","energized","safe",List.of(new CapabilityDependencies.Fact("interlock","permit","execution")));
    ObjectNode facts(String active,String safe,String permit) {
        var facts=JSON.createObjectNode();facts.putObject("energized").put("status",active);
        facts.putObject("safe").put("status",safe);facts.putObject("permit").put("status",permit).put("reason","test observation");return facts;
    }
    @Test void unknownApplicabilityDoesNotPretendToBeInactive() {
        var supervisor=new FacilitySupervision(JSON);
        var value=supervisor.assess(List.of(RULE),facts("unknown","unknown","valid")).path("energy");
        assertEquals("unknown",value.path("status").asText());assertFalse(value.path("active").asBoolean());
    }
    @Test void transientLossIsLatchedUntilObservedSafeExitThenANewDutyCanBegin() {
        var supervisor=new FacilitySupervision(JSON);
        supervisor.assess(List.of(RULE),facts("violated","valid","valid"));
        assertEquals("valid",supervisor.assess(List.of(RULE),facts("valid","violated","valid")).path("energy").path("status").asText());
        var lost=supervisor.assess(List.of(RULE),facts("valid","violated","violated")).path("energy");
        assertTrue(lost.path("latched").asBoolean());assertTrue(lost.path("lastIncident").path("occurredAt").asLong()>0);
        assertEquals("violated",supervisor.assess(List.of(RULE),facts("valid","violated","valid")).path("energy").path("status").asText());
        var released=supervisor.assess(List.of(RULE),facts("violated","valid","valid")).path("energy");
        assertEquals("inactive",released.path("status").asText());assertFalse(released.path("active").asBoolean());
        assertEquals(lost.path("lastIncident"),released.path("lastIncident"),"解除仍保留事件，避免协调器漏掉瞬时异常");
        assertEquals("valid",supervisor.assess(List.of(RULE),facts("valid","violated","valid")).path("energy").path("status").asText());
    }
    @Test void faultOrDisconnectCannotReleaseAnActivePhysicalDuty() {
        for(String state:List.of("violated","unknown")) {
            var supervisor=new FacilitySupervision(JSON);supervisor.assess(List.of(RULE),facts("violated","valid","valid"));
            supervisor.assess(List.of(RULE),facts("valid","violated","valid"));
            var result=supervisor.assess(List.of(RULE),facts(state,"unknown","valid")).path("energy");
            assertTrue(result.path("active").asBoolean());assertEquals("unknown",result.path("status").asText());
            assertThrows(IllegalStateException.class,()->supervisor.validateReplacement(List.of()),"不能删除尚未退出的工作状态义务");
        }
    }
    @Test void restartIntoAnActiveModeCannotClaimContinuousCoverage() {
        var result=new FacilitySupervision(JSON).assess(List.of(RULE),facts("valid","violated","valid")).path("energy");
        assertEquals("unknown",result.path("status").asText());assertTrue(result.path("latched").asBoolean());
        assertTrue(result.path("reason").asText().contains("监督覆盖"));
    }
    @Test void missingObservationsBetweenSafeBaselineAndActivationLoseCoverage() {
        var supervisor=new FacilitySupervision(JSON);
        supervisor.assess(List.of(RULE),facts("violated","valid","valid"));
        supervisor.assess(List.of(RULE),facts("unknown","unknown","valid"));
        var result=supervisor.assess(List.of(RULE),facts("valid","violated","valid")).path("energy");
        assertEquals("unknown",result.path("status").asText());assertTrue(result.path("latched").asBoolean());
    }
    @Test void contradictoryActiveAndSafeObservationsDoNotRelease() {
        var result=new FacilitySupervision(JSON).assess(List.of(RULE),facts("valid","valid","valid")).path("energy");
        assertTrue(result.path("active").asBoolean());assertEquals("violated",result.path("status").asText());
    }
    @Test void rejectsMissingFactsInvalidLifetimesAndUnknownActions()throws Exception {
        String definition=FacilityXmlModelTest.definition(),projection=FacilityXmlModelTest.projection();
        var model=SystemDefinitionModel.parse(definition);
        assertThrows(IllegalArgumentException.class,()->FacilityStateProjectionModel.parse(projection.replace("id=\"interlockArmed\"","id=\"notArmed\"")));
        assertThrows(IllegalArgumentException.class,()->FacilityStateProjectionModel.parse(projection).validateDependencies(
            SystemDefinitionModel.parse(definition.replace("activationFactRef=\"interlockArmed\"","activationFactRef=\"missing\""))));
        String rules=definition.substring(definition.indexOf("<operatingConstraints>"),definition.indexOf("</operatingConstraints>"));
        assertThrows(IllegalArgumentException.class,()->SystemDefinitionModel.parse(definition.replace(rules,rules.replace("maintain=\"execution\"","maintain=\"start\""))));
        assertThrows(IllegalArgumentException.class,()->FacilityStateProjectionModel.parse(projection.replace("actionRef=\"collimation\"","actionRef=\"invented\""))
            .validateComponents(model,FacilityComponentModels.bundled(model)));
        String selfDisabling=projection.replace("<fact id=\"collimationExecuting\"", "<fact id=\"collimationExecuting\"")
            .replace("<condition><actionPredicate instanceSetRef=\"multipass\" actionRef=\"collimation\" active=\"true\" quantifier=\"any\" /></condition>",
                "<condition><all><factPredicate factRef=\"purgeStopped\"/><actionPredicate instanceSetRef=\"multipass\" actionRef=\"collimation\" active=\"true\" quantifier=\"any\" /></all></condition>");
        assertNotEquals(projection,selfDisabling);
        assertThrows(IllegalArgumentException.class,()->FacilityStateProjectionModel.parse(selfDisabling).validateDependencies(model));
    }
    @Test void oneChargingDeviceActivatesDutyEvenWhenOtherDevicesAreIdle()throws Exception {
        try(var transport=new FacilityDependencyRuntimeTest.Transport()) {
            transport.add("pump_01","pump","ready");transport.add("pump_02","pump","charging");
            for(var snapshot:transport.values.values())snapshot.putNull("active_action").putNull("active_command_id");
            var states=new FacilityStateRuntime(JSON,transport,event->{});states.refresh();
            assertEquals("valid",states.current().path("facts").path("pumpEnergyActive").path("status").asText());
            assertTrue(states.current().path("constraints").path("pump-energy").path("active").asBoolean());
            assertEquals("unknown",states.current().path("constraints").path("pump-energy").path("status").asText(),"真实联锁未接入，不能视为有效");
            transport.values.get("pump_02").put("outer_state","fault").put("state_id","faultLock").put("state_revision",2);states.refresh();
            assertTrue(states.current().path("constraints").path("pump-energy").path("active").asBoolean(),"故障状态不能证明能量消失");
        }
    }
    @Test void collimationResultDoesNotImplyAnOngoingCollimationOperation() {
        try(var transport=new FacilityDependencyRuntimeTest.Transport()) {
            transport.add("multipass_01","multipass","collimated");
            transport.values.get("multipass_01").put("state_id","collimationReady").putNull("active_action").putNull("active_command_id");
            var states=new FacilityStateRuntime(JSON,transport,event->{});states.refresh();
            assertEquals("valid",states.current().path("facts").path("multipassCollimated").path("status").asText());
            assertEquals("inactive",states.current().path("constraints").path("collimation-operation").path("status").asText());
            transport.values.get("multipass_01").remove("active_action");states.refresh();
            assertEquals("unknown",states.current().path("facts").path("collimationIdle").path("status").asText(),"缺失执行观测不能宣称动作已结束");
        }
    }
    @Test void productionCapabilitiesRequireSafetyFactsEvenWithoutABpmnGuard()throws Exception {
        try(var transport=new FacilityDependencyRuntimeTest.Transport()) {
            transport.add("seed_01","seed","ready");var states=new FacilityStateRuntime(JSON,transport,event->{});states.refresh();
            var dependencies=new FacilityDependencyRuntime(JSON,states);
            var plan=dependencies.plan("emit","seed","emit","seed_01",List.of(),Map.of(),"seed",Map.of());
            assertNotNull(plan);var result=dependencies.check(plan.toString(),false,false);
            assertEquals("unknown",result.path("status").asText());assertTrue(result.path("reason").asText().contains("interlockOperational"));
        }
    }
    @Test void initializedDevicesRemainFunctionReadyAfterMovingToConfiguredStates()throws Exception {
        try(var transport=new FacilityDependencyRuntimeTest.Transport()) {
            var definition=SystemDefinitionModel.parse(FacilityXmlModelTest.definition());
            for(var set:definition.instanceSets()) {
                String id=set.id()+"_01";transport.add(id,set.id(),"ready");
                transport.values.get(id).put("state_id",set.id().equals("seed")?"configured":Set.of("vacuum","control-environment").contains(set.id())?"functionReady":"parameterOk");
            }
            var states=new FacilityStateRuntime(JSON,transport,event->{});states.refresh();
            assertEquals("valid",states.current().path("facts").path("allFunctionReady").path("status").asText());
            transport.values.get("pump_01").put("state_id","resetReady").put("state_revision",2);states.refresh();
            assertEquals("violated",states.current().path("facts").path("allFunctionReady").path("status").asText(),"复位待机仍需重新通过功能检查");
            transport.values.get("pump_01").put("state_id","parameterOk").put("state_revision",3);
            transport.values.get("preamp_01").put("outer_state","fault").put("state_id","faultLock").put("state_revision",2);states.refresh();
            assertEquals("violated",states.current().path("facts").path("allFunctionReady").path("status").asText());
        }
    }
}
