package cn.cdi8.simulator.bridge;

import cn.cdi8.simulator.runtime.ComponentModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static cn.cdi8.simulator.runtime.ComponentModel.JSON;
import static org.junit.jupiter.api.Assertions.*;

class EnvironmentManagerTest {
    @TempDir Path directory;
    String xml() throws Exception {
        try(var stream=getClass().getResourceAsStream("/models/seed-source-state-machine.scxml")) {
            return new String(stream.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    ObjectNode payload() throws Exception {
        ObjectNode value=JSON.createObjectNode();value.putArray("devices").addObject().put("id","seed_01")
            .put("component","seed").put("revision","selected-revision").put("xml",xml());return value;
    }
    @Test void engineValidationRejectsModelsThatMetadataAloneWouldAccept() throws Exception {
        try(var trials=new TrialManager(directory)) {
            String broken=xml().replace("initial=\"notReady\"","initial=\"missing\"");
            assertNotEquals(xml(),broken);
            assertFalse(trials.validate(broken).path("valid").asBoolean());
            assertThrows(IllegalArgumentException.class,()->trials.create(broken));
        }
    }
    @Test void trialCommandsExposeRejectionWithoutReplacingTheActiveCommand() throws Exception {
        try(var trials=new TrialManager(directory)) {
            String id=trials.create(xml()).path("id").asText();
            JsonNode accepted=trials.command(id,JSON.createObjectNode().put("action","power_on_check"));
            String command=accepted.path("commandResult").path("command_id").asText();
            assertEquals("accepted",accepted.path("commandResult").path("status").asText());
            assertEquals(command,accepted.path("snapshot").path("active_command_id").asText());
            assertTrue(accepted.path("availableActions").isEmpty());
            JsonNode refused=trials.command(id,JSON.createObjectNode().put("action","shutdown"));
            assertEquals("rejected",refused.path("commandResult").path("status").asText());
            assertEquals(command,refused.path("snapshot").path("active_command_id").asText());
            assertNotEquals(command,refused.path("commandResult").path("command_id").asText());
            assertFalse(refused.path("commandResult").path("reason").asText().isBlank());
        }
    }
    @Test void badConfigurationCannotTouchTheCurrentEnvironment() throws Exception {
        try(var bridge=new JavaControlBridgeApplication.Bridge(null,directory,null)) {
            JsonNode previous=bridge.environmentView();
            ObjectNode invalid=payload();((ObjectNode)invalid.path("devices").get(0)).put("id","../escape");
            assertThrows(IllegalArgumentException.class,()->bridge.environment(invalid));
            assertEquals(previous,bridge.environmentView());assertTrue(bridge.catalog().isEmpty());
            ObjectNode wrongCondition=payload();wrongCondition.putObject("conditions").put("permit","true");
            assertThrows(IllegalArgumentException.class,()->bridge.environment(wrongCondition));
            assertEquals(previous,bridge.environmentView());
        }
    }
    @Test void childStartupFailurePreservesEnvironmentAndDiagnosticLog() throws Exception {
        try(var bridge=new JavaControlBridgeApplication.Bridge(null,directory,directory.resolve("missing.jar"))) {
            JsonNode previous=bridge.environmentView();ObjectNode request=payload();
            assertThrows(IllegalStateException.class,()->bridge.environment(request));
            assertEquals(previous,bridge.environmentView());assertTrue(bridge.catalog().isEmpty());
            try(var generations=Files.list(directory.resolve("environments"))) {
                Path generation=generations.findFirst().orElseThrow();
                assertTrue(Files.exists(generation.resolve("failure.txt")));
                assertTrue(Files.exists(generation.resolve("device-server.log")));
            }
        }
    }
}
