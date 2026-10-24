package cn.cdi8.simulator;

import cn.cdi8.simulator.runtime.ComponentModel;
import cn.cdi8.simulator.runtime.ComponentRuntime;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static cn.cdi8.simulator.runtime.ComponentModel.JSON;
import static org.junit.jupiter.api.Assertions.*;

class GxlfComponentsTest {
    @TempDir Path directory;
    static final List<String> MODELS=List.of("seed-source","shg-injection","preamp","multipass","pump",
        "measurement","frequency-conversion","target-alignment","vacuum","diagnostics",
        "synchronization","switch-driver","cooling","control-environment");

    ComponentRuntime runtime(String name) throws Exception {
        try(var input=getClass().getResourceAsStream("/models/"+name+"-state-machine.scxml")) {
            assertNotNull(input,name);
            return new ComponentRuntime(new ComponentModel(new String(input.readAllBytes(),StandardCharsets.UTF_8)),
                directory.resolve(name+"-"+UUID.randomUUID()+".sqlite3"));
        }
    }
    void action(ComponentRuntime runtime,String action) {
        String id=UUID.randomUUID().toString();
        JsonNode accepted=runtime.execute(JSON.createObjectNode().put("command_id",id).put("action",action));
        assertEquals("accepted",accepted.path("status").asText(),accepted.toString());
        JsonNode completed=runtime.simulate(JSON.createObjectNode().put("command_id",id).put("outcome","success"));
        assertEquals("succeeded",completed.path("status").asText(),completed.toString());
    }
    void initialized(ComponentRuntime runtime) { action(runtime,"power_on_check");action(runtime,"function_check"); }
    void confirmation(ComponentRuntime runtime,String key,String outerLabel) {
        runtime.reportEvent(JSON.createObjectNode().put("event_id",UUID.randomUUID().toString())
            .put("event",key).put("observed_outer_state",outerLabel));
    }

    @Test void allFourteenModelsInitializeAndExposeScxmlAdmissionContracts() throws Exception {
        for(String name:MODELS) try(var runtime=runtime(name)) {
            initialized(runtime);
            assertEquals(name.equals("seed-source")?"qualified":"functionReady",runtime.snapshot().path("state_id").asText(),name);
            assertTrue(runtime.snapshot().path("supports_action_contract").asBoolean(),name);
            assertFalse(runtime.snapshot().path("action_contracts").isEmpty(),name);
            // Only twelve models declare parameter control; vacuum is observed and
            // the environment exposes its own clearance/door operations.
            if(!List.of("vacuum","control-environment").contains(name)) {
                action(runtime,"parameter_dispatch");
                assertEquals(name.equals("seed-source")?"configured":"parameterOk",runtime.snapshot().path("state_id").asText(),name);
            }
        }
    }
    @Test void pumpChargeAcceptanceAndSuccessDoNotClaimPhysicalChargeCompletion() throws Exception {
        try(var runtime=runtime("pump")) {
            initialized(runtime);
            for(String action:List.of("parameter_dispatch","emission_prepare","charge_prepare","charge"))action(runtime,action);
            assertEquals("chargingActive",runtime.snapshot().path("state_id").asText());
            JsonNode rejected=runtime.execute(JSON.createObjectNode().put("command_id","too-early").put("action","trigger_prepare"));
            assertEquals("rejected",rejected.path("status").asText());
            confirmation(runtime,"charging_completed","泵浦充电完成");
            assertEquals("chargeComplete",runtime.snapshot().path("state_id").asText());
            action(runtime,"trigger_prepare");
            assertEquals("triggerReady",runtime.snapshot().path("state_id").asText());
        }
    }
    @Test void simulationFeedbackReplayUsesTheOriginalStateObservation() throws Exception {
        for(String name:MODELS) try(var runtime=runtime(name)) {
            String id="power-check";
            runtime.execute(JSON.createObjectNode().put("command_id",id).put("action","power_on_check"));
            var feedback=JSON.createObjectNode().put("command_id",id).put("outcome","success");
            JsonNode result=runtime.simulate(feedback),snapshot=runtime.snapshot();
            assertEquals(JSON.readTree(result.toString()),runtime.simulate(feedback),name);
            assertEquals(snapshot,runtime.snapshot(),name);
            assertThrows(IllegalArgumentException.class,()->runtime.simulate(feedback.deepCopy().put("outcome","failure")),name);
            assertEquals(snapshot,runtime.snapshot(),name);
        }
    }
    @Test void activeProtectionPurgeSurvivesRunTerminationAndCannotBeResetOrShutDown() throws Exception {
        try(var runtime=runtime("cooling")) {
            initialized(runtime);action(runtime,"parameter_dispatch");action(runtime,"purge_start");
            assertEquals("purgeActive",runtime.snapshot().path("state_id").asText());
            confirmation(runtime,"run_terminated_1","片放吹扫中");
            for(String action:List.of("standby_reset","abort_reset","shutdown")) {
                assertEquals("rejected",runtime.execute(JSON.createObjectNode().put("command_id",action).put("action",action)).path("status").asText());
                assertEquals("purgeActive",runtime.snapshot().path("state_id").asText());
            }
            confirmation(runtime,"purge_completed","就绪");
            assertEquals("purgeComplete",runtime.snapshot().path("state_id").asText());
            action(runtime,"standby_reset");
        }
    }
    @Test void repressurizationImmediatelyRevokesVacuumHolding() throws Exception {
        try(var runtime=runtime("vacuum")) {
            initialized(runtime);
            confirmation(runtime,"pumping_observed","抽真空中");
            confirmation(runtime,"holding_observed","真空保持");
            assertEquals("vacuumStable",runtime.snapshot().path("state_id").asText());
            confirmation(runtime,"repressurizing_observed","负压中");
            assertEquals("pressureRestoring",runtime.snapshot().path("state_id").asText());
            assertThrows(IllegalArgumentException.class,()->confirmation(runtime,"holding_observed","真空保持"));
            assertEquals("pressureRestoring",runtime.snapshot().path("state_id").asText());
            confirmation(runtime,"pressure_restored","负压完成");
            assertEquals("pressureRestored",runtime.snapshot().path("state_id").asText());
        }
    }
    @Test void openingDoorAfterInterlockTripDoesNotClearTheFaultLatch() throws Exception {
        try(var runtime=runtime("control-environment")) {
            initialized(runtime);action(runtime,"clear_area");action(runtime,"lock_door");
            assertEquals("interlockEstablished",runtime.snapshot().path("state_id").asText());
            confirmation(runtime,"interlock_tripped","异常");
            action(runtime,"unlock_door");
            assertEquals("interlockTripped",runtime.snapshot().path("state_id").asText());
            assertFalse(runtime.snapshot().path("action_contracts").path("clear_area").path("ready").asBoolean());
            confirmation(runtime,"recovery_confirmed","就绪");
            assertEquals("resetReady",runtime.snapshot().path("state_id").asText());
            assertFalse(runtime.snapshot().path("action_contracts").path("clear_area").path("ready").asBoolean());
            action(runtime,"function_check");
            action(runtime,"clear_area");
        }
    }
}
