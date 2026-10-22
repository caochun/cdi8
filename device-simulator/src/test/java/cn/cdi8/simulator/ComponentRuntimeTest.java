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
