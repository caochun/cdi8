package cn.cdi8.simulator.runtime;

import cn.cdi8.simulator.engine.ScxmlEngine;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Behaviors relied on when changing the SCXML 2 distribution. */
class ScxmlCompatibilityTest {
    @TempDir Path directory;

    @Test void readinessUsesGuardsWithoutExecutingTransitionContent() {
        var engine=new ScxmlEngine("""
            <scxml xmlns="http://www.w3.org/2005/07/scxml" version="1.0" initial="parent" datamodel="jexl">
              <datamodel><data id="count" expr="0" /></datamodel>
              <state id="parent" initial="idle">
                <transition event="reset.*" target="idle" />
                <state id="idle">
                  <transition event="request" cond="In('parent') &amp;&amp; count == 0">
                    <assign location="count" expr="count + 1" />
                  </transition>
                  <transition event="finish" cond="count == 1" target="active" />
                </state>
                <state id="active" />
              </state>
            </scxml>
            """);
        assertTrue(engine.enabled("request"));
        assertTrue(engine.enabled("request"),"readiness must not execute the assignment");
        assertFalse(engine.enabled("finish"));
        engine.dispatch("request");
        assertEquals("idle",engine.leaf());
        assertFalse(engine.enabled("request"));
        assertTrue(engine.enabled("finish"));
        engine.dispatch("finish");
        assertEquals("active",engine.leaf());
        assertTrue(engine.enabled("reset.requested"),"ancestor event descriptors remain supported");
        engine.dispatch("reset.requested");
        assertEquals("idle",engine.leaf());
    }

    @Test void sqliteRestartRestoresLeafAndCommandResultWithoutReexecution() throws Exception {
        String xml;
        try(var input=getClass().getResourceAsStream("/component.scxml")) {
            assertNotNull(input);
            xml=new String(input.readAllBytes(),StandardCharsets.UTF_8);
        }
        var model=new ComponentModel(xml);
        var request=ComponentModel.JSON.createObjectNode().put("command_id","activate-1").put("action","activate");
        Path database=directory.resolve("component.sqlite3");
        int events;
        try(var runtime=new ComponentRuntime(model,database)) {
            assertEquals("accepted",runtime.execute(request).path("status").asText());
            runtime.simulate(ComponentModel.JSON.createObjectNode().put("command_id","activate-1").put("outcome","success"));
            events=runtime.history(100).size();
        }
        try(var runtime=new ComponentRuntime(model,database)) {
            assertEquals("active",runtime.snapshot().path("state_id").asText());
            assertEquals("succeeded",runtime.execute(request).path("status").asText());
            assertEquals(events,runtime.history(100).size());
        }
    }

    @Test void snapshotRevisionAndCommandResultStayAligned() throws Exception {
        String xml;
        try(var input=getClass().getResourceAsStream("/component.scxml")) {
            assertNotNull(input);
            xml=new String(input.readAllBytes(),StandardCharsets.UTF_8);
        }
        var model=new ComponentModel(xml);
        var request=ComponentModel.JSON.createObjectNode().put("command_id","activate-2").put("action","activate");
        try(var runtime=new ComponentRuntime(model,directory.resolve("snapshot.sqlite3"))) {
            var initial=runtime.snapshot();
            assertEquals("component",initial.path("snapshot_kind").asText());
            assertFalse(initial.path("source_id").asText().isBlank());
            assertTrue(initial.path("state_revision").isIntegralNumber());
            assertTrue(initial.path("model_digest").asText().matches("[0-9a-f]{64}"));
            assertEquals(initial.path("state_revision").asLong(),0L);

            var accepted=runtime.execute(request);
            var executing=runtime.snapshot();
            assertTrue(executing.path("state_revision").asLong()>initial.path("state_revision").asLong());
            assertEquals("activate-2",executing.path("active_command_id").asText());
            assertEquals("executing",executing.path("task_state").asText());
            assertEquals(executing,accepted.path("snapshot"));

            var completed=runtime.simulate(ComponentModel.JSON.createObjectNode()
                .put("command_id","activate-2").put("outcome","success"));
            var finalSnapshot=runtime.snapshot();
            assertEquals("active",finalSnapshot.path("state_id").asText());
            assertTrue(finalSnapshot.path("state_revision").asLong()>executing.path("state_revision").asLong());
            assertTrue(finalSnapshot.path("active_command_id").isNull());
            assertEquals(finalSnapshot,completed.path("snapshot"));
            var persisted=runtime.result("activate-2").path("snapshot");
            assertEquals(finalSnapshot.path("source_id"),persisted.path("source_id"));
            assertEquals(finalSnapshot.path("state_revision").asLong(),persisted.path("state_revision").asLong());
            assertEquals(finalSnapshot.path("state_id"),persisted.path("state_id"));
            assertEquals(finalSnapshot.path("task_state"),persisted.path("task_state"));
            assertFalse(runtime.history(100).isEmpty());
        }
    }
}
