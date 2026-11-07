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
}
