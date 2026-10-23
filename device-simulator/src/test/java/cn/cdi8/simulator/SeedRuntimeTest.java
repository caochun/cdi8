package cn.cdi8.simulator;

import cn.cdi8.simulator.runtime.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static cn.cdi8.simulator.runtime.ComponentModel.*;
import static org.junit.jupiter.api.Assertions.*;

class SeedRuntimeTest {
    @TempDir Path directory;
    SeedRuntime runtime() throws Exception { return new SeedRuntime(new ComponentModel(ScxmlEngineTest.seed()),directory.resolve("seed.sqlite3")); }
    JsonNode request(String id,String action) { return JSON.createObjectNode().put("command_id",id).put("action",action); }
    JsonNode feedback(String id,String outcome) { return JSON.createObjectNode().put("command_id",id).put("outcome",outcome); }
    void perform(SeedRuntime runtime,String id,String action) {
        assertEquals("accepted",runtime.execute(request(id,action)).path("status").asText());
        assertEquals("succeeded",runtime.simulate(feedback(id,"success")).path("status").asText());
    }
    JsonNode event(SeedRuntime runtime,String id,String name,String observation) {
        return runtime.reportEvent(JSON.createObjectNode().put("event_id",id).put("event",name).put("observed_outer_state",observation));
    }
    @Test void completeSeedCycleAndIdempotentFeedback() throws Exception {
        try (var runtime=runtime()) {
            assertEquals("rejected",runtime.execute(request("bad","parameter_dispatch")).path("status").asText());
            perform(runtime,"power","power_on_check");
            perform(runtime,"function","function_check");
            perform(runtime,"parameters","parameter_dispatch");
            assertEquals("valid",runtime.snapshot().path("configuration").path("status").asText());
            var accepted=runtime.execute(request("emit","seed_source_emit"));
            assertEquals(encode(accepted),encode(runtime.execute(request("emit","seed_source_emit"))));
            long revision=runtime.snapshot().path("state_revision").asLong();
            var result=runtime.simulate(feedback("emit","success"));
            assertEquals(encode(result),encode(runtime.simulate(feedback("emit","success"))));
            assertEquals(revision+1,runtime.snapshot().path("state_revision").asLong());
            assertEquals("stableEmission",runtime.snapshot().path("state_id").asText());
            perform(runtime,"collect","laser_parameter_collect");
            perform(runtime,"standby","standby_reset");
            perform(runtime,"shutdown","shutdown");
            assertEquals("shutdownNormal",runtime.snapshot().path("state_id").asText());
            assertEquals("off",runtime.snapshot().path("context").path("power_status").asText());
            assertEquals(14,runtime.history(100).size());
        }
    }
    @Test void faultInterruptsPendingCommandAndLateSuccessCannotAdvanceIt() throws Exception {
        try (var runtime=runtime()) {
            runtime.execute(request("power","power_on_check"));
            var fault=event(runtime,"fault-1","fault_confirmed","异常");
            assertEquals(encode(fault),encode(event(runtime,"fault-1","fault_confirmed","异常")));
            assertEquals("interrupted",runtime.simulate(feedback("power","success")).path("status").asText());
            assertEquals("faultLocked",runtime.snapshot().path("state_id").asText());
            event(runtime,"fault-2","communication_fault_confirmed","异常");
            assertEquals("faultLocked",runtime.snapshot().path("state_id").asText());
            assertEquals(2,runtime.snapshot().path("faults").size());
            event(runtime,"recover","recovery_confirmed","未就绪");
            assertEquals("statusUnknown",runtime.snapshot().path("state_id").asText());
            assertTrue(runtime.snapshot().path("faults").isEmpty());
        }
    }
    @Test void restartKeepsPendingCommandAndRestoresHierarchy() throws Exception {
        try (var runtime=runtime()) {
            perform(runtime,"power","power_on_check");
            runtime.execute(request("function","function_check"));
        }
        try (var runtime=runtime()) {
            assertEquals("function",runtime.snapshot().path("active_command_id").asText());
            assertEquals("accepted",runtime.result("function").path("status").asText());
            runtime.simulate(feedback("function","success"));
            perform(runtime,"parameters","parameter_dispatch");
            perform(runtime,"emit","seed_source_emit");
        }
        try (var runtime=runtime()) {
            assertEquals("stableEmission",runtime.snapshot().path("state_id").asText());
            assertEquals("emit",runtime.result("emit").path("command_id").asText());
            perform(runtime,"shutdown","shutdown");
        }
    }
    @Test void conflictingRequestsAndFeedbackDoNotRewriteHistory() throws Exception {
        try (var runtime=runtime()) {
            var accepted=runtime.execute(request("power","power_on_check"));
            assertThrows(IllegalArgumentException.class,()->runtime.execute(request("power","shutdown")));
            assertEquals("rejected",runtime.execute(request("other","shutdown")).path("status").asText());
            assertEquals(encode(accepted),encode(runtime.result("power")));
            runtime.simulate(feedback("power","failure"));
            assertThrows(IllegalArgumentException.class,()->runtime.simulate(feedback("power","success")));
            assertEquals("qualificationInvalid",runtime.snapshot().path("state_id").asText());
            assertEquals(2,runtime.history(100).size());
        }
    }
    @Test void editedModelCannotReuseAnExistingDatabase() throws Exception {
        try (var runtime=runtime()) { perform(runtime,"power","power_on_check"); }
        String changed=ScxmlEngineTest.seed().replace("状态未确认","新的标签");
        assertThrows(IllegalStateException.class,()->new SeedRuntime(new ComponentModel(changed),directory.resolve("seed.sqlite3")));
    }
}
