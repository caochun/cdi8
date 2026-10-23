package cn.cdi8.simulator;

import cn.cdi8.simulator.runtime.ComponentModel;
import cn.cdi8.simulator.runtime.ComponentRuntime;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static cn.cdi8.simulator.runtime.ComponentModel.JSON;
import static org.junit.jupiter.api.Assertions.*;

class ComponentRuntimeTest {
    @TempDir Path directory;

    private static String model(String resource) throws Exception {
        try (var stream=ComponentRuntimeTest.class.getResourceAsStream("/models/"+resource)) {
            if (stream==null) throw new IllegalArgumentException("缺少模型资源："+resource);
            return new String(stream.readAllBytes(),StandardCharsets.UTF_8);
        }
    }

    private static JsonNode request(String id,String action) {
        return JSON.createObjectNode().put("command_id",id).put("action",action);
    }

    private static JsonNode feedback(String id) {
        return JSON.createObjectNode().put("command_id",id).put("outcome","success");
    }
    private static String minimal() throws Exception {
        try(var stream=ComponentRuntimeTest.class.getResourceAsStream("/component.scxml")) {
            return new String(stream.readAllBytes(),StandardCharsets.UTF_8);
        }
    }

    @Test void intrinsicActionContractsProtectEveryCallerAndCannotBeWeakenedByAnExtraCondition() throws Exception {
        for(String resource:List.of("seed-source","shg-injection","preamp","multipass")) {
            var component=new ComponentModel(model(resource+"-state-machine.scxml"));
            var database=directory.resolve(resource+"-contract.sqlite3");
            JsonNode rejected;
            try(var runtime=new ComponentRuntime(component,database)) {
                var before=runtime.snapshot();
                var contracts=before.path("action_contracts");
                assertTrue(before.path("supports_action_contract").asBoolean());
                assertTrue(contracts.path("power_on_check").path("ready").asBoolean());
                assertFalse(contracts.path("function_check").path("ready").asBoolean());
                assertFalse(component.action("function_check").has("requests"));
                assertFalse(contracts.path("function_check").path("entry").has("states"));
                assertEquals(component.requestEvent("function_check"),contracts.path("function_check").path("entry").path("event").asText());
                rejected=runtime.execute(request("direct-function","function_check"));
                assertEquals("rejected",rejected.path("status").asText());
                assertEquals(before,runtime.snapshot());
                // A true process-supplied predicate cannot grant device permission.
                var loosen=JSON.createObjectNode().put("command_id","cannot-loosen").put("action","function_check");
                loosen.putObject("parameters").putObject("entry_condition")
                    .put("states",before.path("state_id").asText()).put("idle",true);
                assertEquals("rejected",runtime.execute(loosen).path("status").asText());
                assertEquals(before,runtime.snapshot());
                runtime.execute(request("power","power_on_check"));
                var busy=runtime.snapshot();
                busy.path("action_contracts").forEach(contract->assertFalse(contract.path("ready").asBoolean()));
                assertEquals("rejected",runtime.execute(request("busy-shutdown","shutdown")).path("status").asText());
                assertEquals(busy,runtime.snapshot());
                runtime.simulate(feedback("power"));
                assertTrue(runtime.snapshot().path("action_contracts").path("function_check").path("ready").asBoolean());
                assertEquals(JSON.readTree(rejected.toString()),runtime.execute(request("direct-function","function_check")));
                runtime.reportEvent(JSON.createObjectNode().put("event_id","fault").put("event","fault_confirmed").put("observed_outer_state","异常"));
                var fault=runtime.snapshot();
                assertFalse(fault.path("action_contracts").path("function_check").path("ready").asBoolean());
                assertEquals("rejected",runtime.execute(request("fault-function","function_check")).path("status").asText());
                assertEquals(fault,runtime.snapshot());
            }
            try(var recovered=new ComponentRuntime(component,database)) {
                assertEquals(JSON.readTree(rejected.toString()),recovered.result("direct-function"));
                assertEquals(JSON.readTree(rejected.toString()),recovered.execute(request("direct-function","function_check")));
            }
        }
    }

    @Test void entryConditionIsCheckedOnAcceptanceWithoutChangingDeviceAndReplayRemainsIdempotent() throws Exception {
        var component=new ComponentModel(model("seed-source-state-machine.scxml"));
        try(var runtime=new ComponentRuntime(component,directory.resolve("guard.sqlite3"))) {
            var request=JSON.createObjectNode().put("command_id","rejected-power").put("action","power_on_check");
            var guard=request.putObject("parameters").putObject("entry_condition").put("states","powerOnChecked").put("idle",true);
            var before=runtime.snapshot();
            assertTrue(before.path("supports_entry_condition").asBoolean());
            var rejected=runtime.execute(request);
            assertEquals("rejected",rejected.path("status").asText());
            assertTrue(rejected.path("reason").asText().contains("进入条件"));
            assertEquals(JSON.readTree(rejected.toString()),runtime.result("rejected-power"));
            assertEquals(before,runtime.snapshot(),"A failed guard must not start or mutate the physical action");
            var rejectedRequest=request.deepCopy();
            request.put("command_id","guarded-power");
            guard.put("states","statusUnknown");
            assertEquals("accepted",runtime.execute(request).path("status").asText());
            var second=request.deepCopy().put("command_id","other-power");
            assertEquals("rejected",runtime.execute(second).path("status").asText(),"A second command cannot share the device");
            var finished=runtime.simulate(feedback("guarded-power"));
            assertEquals("powerOnChecked",runtime.snapshot().path("state_id").asText());
            assertEquals("rejected",runtime.execute(request.deepCopy().put("command_id","changed-state")).path("status").asText(),
                "A new command must use current conditions even when SCXML still accepts the action");
            assertEquals(JSON.readTree(finished.toString()),runtime.execute(request),"A replay queries the completed command even when its entry state has changed");
            assertEquals(JSON.readTree(rejected.toString()),runtime.execute(rejectedRequest),"A rejected command ID cannot later become an accepted action");
        }
    }

    @Test void malformedOrUnsupportedEntryConditionsFailClosed() throws Exception {
        var component=new ComponentModel(model("seed-source-state-machine.scxml"));
        try(var runtime=new ComponentRuntime(component,directory.resolve("bad-guard.sqlite3"))) {
            var before=runtime.snapshot();
            for(String guard:List.of("null","[]","{}","{\"states\":\"statusUnknown\",\"idle\":false}",
                    "{\"states\":[\"statusUnknown\"],\"idle\":true}","{\"states\":\"statusUnknown\",\"idle\":true,\"unchecked_condition\":true}")) {
                var request=JSON.createObjectNode().put("command_id","bad-guard").put("action","power_on_check");
                request.putObject("parameters").set("entry_condition",JSON.readTree(guard));
                assertThrows(IllegalArgumentException.class,()->runtime.execute(request),guard);
                assertEquals(before,runtime.snapshot());
            }
        }
    }
    @Test void commandAdmissionAndReadinessFollowTheSameScxmlGuard() throws Exception {
        String xml=minimal()
            .replace("<datamodel>","<datamodel><data id=\"quota\" expr=\"1\"/>")
            .replace("<transition event=\"activate.request\" />",
                "<transition event=\"activate.request\" cond=\"quota &gt; 0\"><assign location=\"quota\" expr=\"quota - 1\"/></transition>");
        try(var runtime=new ComponentRuntime(new ComponentModel(xml),directory.resolve("scxml-guard.sqlite3"))) {
            for(int i=0;i<3;i++) assertTrue(runtime.snapshot().path("action_contracts").path("activate").path("ready").asBoolean());
            assertEquals("accepted",runtime.execute(request("first","activate")).path("status").asText());
            assertEquals("idle",runtime.snapshot().path("state_id").asText(),"A targetless request keeps the business state");
            runtime.simulate(JSON.createObjectNode().put("command_id","first").put("outcome","failure"));
            var before=runtime.snapshot();
            assertEquals("idle",before.path("state_id").asText());
            assertFalse(before.path("action_contracts").path("activate").path("ready").asBoolean());
            var rejected=runtime.execute(request("guard-false","activate"));
            assertEquals("rejected",rejected.path("status").asText());
            assertEquals(before,runtime.snapshot());
            assertEquals(JSON.readTree(rejected.toString()),runtime.result("guard-false"));
        }
    }
    @Test void legacyAdmissionTablesAreRejectedInsteadOfSilentlyOverridingScxml() throws Exception {
        String xml=minimal();
        assertThrows(IllegalArgumentException.class,()->new ComponentModel(xml.replace(
            "\"request_event\": \"activate.request\"","\"requests\": {\"idle\":\"activate.request\"}")));
        assertThrows(IllegalArgumentException.class,()->new ComponentModel(xml.replace(
            "\"request_event\": \"activate.request\"","\"request_event\": \"\"")));
    }
    @Test void explicitRequestTransitionsPreserveReconfigurationCollectionAndFaultRecovery() throws Exception {
        for(String resource:List.of("seed-source","shg-injection","preamp","multipass")) {
            try(var runtime=new ComponentRuntime(new ComponentModel(model(resource+"-state-machine.scxml")),directory.resolve(resource+"-special.sqlite3"))) {
                for(String action:List.of("power_on_check","function_check","parameter_dispatch","parameter_dispatch")) {
                    String id="step-"+runtime.history(100).size();
                    assertEquals("accepted",runtime.execute(request(id,action)).path("status").asText());
                    assertEquals("succeeded",runtime.simulate(feedback(id)).path("status").asText());
                }
                if(resource.equals("seed-source") || resource.equals("shg-injection")) {
                    String emit=resource.equals("seed-source")?"seed_source_emit":"frequency_doubled_emit";
                    runtime.execute(request("emit",emit));runtime.simulate(feedback("emit"));
                    for(String id:List.of("collect-1","collect-2")) {
                        assertEquals("accepted",runtime.execute(request(id,"laser_parameter_collect")).path("status").asText());
                        assertEquals("stableEmission",runtime.snapshot().path("state_id").asText());
                        runtime.simulate(feedback(id));
                        assertEquals("collectionOk",runtime.snapshot().path("state_id").asText());
                    }
                }
                runtime.reportEvent(JSON.createObjectNode().put("event_id","fault").put("event","fault_confirmed").put("observed_outer_state","异常"));
                assertEquals("accepted",runtime.execute(request("recover","abort_reset")).path("status").asText());
                assertEquals("succeeded",runtime.simulate(feedback("recover")).path("status").asText());
                assertEquals("notReady",runtime.snapshot().path("outer_state").asText());
                assertTrue(runtime.snapshot().path("action_contracts").path("power_on_check").path("ready").asBoolean());
            }
        }
    }

    @Test void restartPreservesStateRevisionButChangesDeviceIncarnation() throws Exception {
        var component=new ComponentModel(model("seed-source-state-machine.scxml"));
        var database=directory.resolve("restart.sqlite3");
        JsonNode original;
        try(var runtime=new ComponentRuntime(component,database)) {
            runtime.execute(request("power","power_on_check"));runtime.simulate(feedback("power"));
            original=runtime.snapshot();
            assertFalse(original.path("source_id").asText().isBlank());
            assertEquals(original.path("source_id"),runtime.snapshot().path("source_id"));
        }
        try(var recovered=new ComponentRuntime(component,database)) {
            assertEquals(original.path("state_revision"),recovered.snapshot().path("state_revision"));
            assertEquals(original.path("state_id"),recovered.snapshot().path("state_id"));
            assertNotEquals(original.path("source_id"),recovered.snapshot().path("source_id"));
        }
    }

    @Test void allFourModelsCompleteTheirDeclaredActionChains() throws Exception {
        Map<String,List<String>> actions=Map.of(
            "seed-source-state-machine.scxml",List.of("power_on_check","function_check","parameter_dispatch","seed_source_emit","laser_parameter_collect","standby_reset","shutdown"),
            "shg-injection-state-machine.scxml",List.of("power_on_check","function_check","parameter_dispatch","frequency_doubled_emit","laser_parameter_collect","standby_reset","shutdown"),
            "preamp-state-machine.scxml",List.of("power_on_check","function_check","parameter_dispatch","preamp_wakeup","coarse_loop","fine_loop","energy_meter_clear","energy_collect","standby_reset","shutdown"),
            "multipass-state-machine.scxml",List.of("power_on_check","function_check","parameter_dispatch","optical_collimation","enter_shot_state","standby_reset","shutdown")
        );
        for (var entry:actions.entrySet()) {
            var component=new ComponentModel(model(entry.getKey()));
            var database=directory.resolve(component.metadata.path("model_id").asText()+".sqlite3");
            try (var runtime=new ComponentRuntime(component,database)) {
                int sequence=0;
                for (String action:entry.getValue()) {
                    String id="command_"+(sequence++);
                    assertEquals("accepted",runtime.execute(request(id,action)).path("status").asText(),entry.getKey()+" accepted "+action);
                    assertEquals("succeeded",runtime.simulate(feedback(id)).path("status").asText(),entry.getKey()+" completed "+action);
                }
                assertEquals("shutdownNormal",runtime.snapshot().path("state_id").asText(),entry.getKey());
                assertEquals(component.metadata.path("model_id").asText(),runtime.snapshot().path("model").asText());
            }
        }
    }
}
