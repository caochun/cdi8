package cn.cdi8.simulator;

import cn.cdi8.simulator.engine.ScxmlEngine;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScxmlEngineTest {
    static String seed() throws Exception {
        try (var stream=ScxmlEngineTest.class.getResourceAsStream("/models/seed-source-state-machine.scxml")) {
            return new String(stream.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    @Test void hierarchicalEventPrefixesUseApacheSemantics() throws Exception {
        var engine=new ScxmlEngine(seed());
        assertEquals("statusUnknown",engine.leaf());
        var step=engine.dispatch("fault.confirmed.hardware");
        assertEquals("faultLocked",engine.leaf());
        assertTrue(step.exits().contains("notReady"));
        assertTrue(step.entries().contains("fault"));
    }
    @Test void restoredLeafHasItsAncestorTransitions() throws Exception {
        var engine=new ScxmlEngine(seed());
        engine.restore("configured");
        assertEquals("stableEmission",engine.dispatch("emission.stable-confirmed").target());
        assertEquals("shutdownNormal",engine.dispatch("shutdown.confirmed").target());
    }
    @Test void guardsAndAssignmentsAreExecutedByTheLibrary() {
        var engine=new ScxmlEngine("""
            <scxml xmlns="http://www.w3.org/2005/07/scxml" version="1.0" datamodel="jexl" initial="a">
              <datamodel><data id="count" expr="0"/></datamodel>
              <state id="a">
                <transition event="retry" cond="count &lt; 2" target="a"><assign location="count" expr="count + 1"/></transition>
                <transition event="finish" cond="count == 2" target="b"/>
              </state><state id="b"/>
            </scxml>
            """);
        assertFalse(engine.dispatch("retry").transitions().isEmpty());
        assertFalse(engine.dispatch("retry").transitions().isEmpty());
        assertTrue(engine.dispatch("retry").transitions().isEmpty());
        assertEquals("b",engine.dispatch("finish").target());
    }
    @Test void internalTransitionsPreserveTheCompoundParent() {
        var engine=new ScxmlEngine("""
            <scxml xmlns="http://www.w3.org/2005/07/scxml" version="1.0" initial="parent">
              <state id="parent" initial="a">
                <state id="a"/><state id="b"/>
                <transition event="internal" target="b" type="internal"/>
                <transition event="external" target="a"/>
              </state>
            </scxml>
            """);
        assertEquals(List.of("a"),engine.dispatch("internal").exits());
        assertEquals(List.of("b","parent"),engine.dispatch("external").exits());
    }
    @Test void externalEntitiesAreRejected() {
        assertThrows(IllegalArgumentException.class,()->new ScxmlEngine("""
            <!DOCTYPE scxml [<!ENTITY test SYSTEM "file:///etc/passwd">]>
            <scxml xmlns="http://www.w3.org/2005/07/scxml" version="1.0" initial="a"><state id="a"/></scxml>
            """));
    }
}
