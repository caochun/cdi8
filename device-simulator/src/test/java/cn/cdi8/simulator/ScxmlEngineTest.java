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
    @Test void readinessUsesGuardsAndTargetlessTransitionsWithoutExecutingContent() {
        var engine=new ScxmlEngine("""
            <scxml xmlns="http://www.w3.org/2005/07/scxml" version="1.0" datamodel="jexl" initial="parent">
              <datamodel><data id="count" expr="0"/></datamodel>
              <state id="parent" initial="a">
                <transition event="request" cond="count == 0 &amp;&amp; In('a') &amp;&amp; _event.name == 'request.work'">
                  <assign location="count" expr="count + 1"/>
                </transition>
                <state id="a"/>
              </state>
            </scxml>
            """);
        for(int i=0;i<3;i++) {
            assertTrue(engine.enabled("request.work"));
            assertFalse(engine.enabled("request.other"));
            assertFalse(engine.enabled("unknown"));
            assertEquals("a",engine.leaf());
        }
        var step=engine.dispatch("request.work");
        assertFalse(step.transitions().isEmpty());
        assertTrue(step.entries().isEmpty());assertTrue(step.exits().isEmpty());
        assertEquals("a",engine.leaf());
        assertFalse(engine.enabled("request.work"),"Only the real command may increment count");
        assertTrue(engine.dispatch("request.work").transitions().isEmpty());
    }
    @Test void readinessAndDispatchShareChildPriorityAndAncestorFallback() {
        var engine=new ScxmlEngine("""
            <scxml xmlns="http://www.w3.org/2005/07/scxml" version="1.0" datamodel="jexl" initial="parent">
              <state id="parent" initial="a">
                <transition event="request" target="c"/>
                <state id="a"><transition event="request" cond="false" target="b"/></state>
                <state id="b"/>
                <state id="c"><transition event="request" target="b"/></state>
              </state>
            </scxml>
            """);
        assertTrue(engine.enabled("request.work"));
        assertEquals("c",engine.dispatch("request.work").target());
        assertTrue(engine.enabled("request.work"));
        assertEquals("b",engine.dispatch("request.work").target());
    }
    @Test void invalidGuardsCannotEnqueueErrorsWhileInspectingReadiness() {
        var engine=new ScxmlEngine("""
            <scxml xmlns="http://www.w3.org/2005/07/scxml" version="1.0" datamodel="jexl" initial="a">
              <state id="a">
                <transition event="request" cond="undefinedValue &gt; 0"/>
                <transition event="safe"/>
                <transition event="error.execution" target="b"/>
              </state><state id="b"/>
            </scxml>
            """);
        assertThrows(IllegalStateException.class,()->engine.enabled("request"));
        assertEquals("a",engine.dispatch("safe").target());
    }
    @Test void guardsWithSideEffectsAreRejectedWhenLoadingTheModel() {
        for(String guard:List.of("count = 1","count++","items.clear()","new('java.util.ArrayList')")) {
            String xml="<scxml xmlns=\"http://www.w3.org/2005/07/scxml\" version=\"1.0\" initial=\"a\">"
                +"<state id=\"a\"><transition event=\"request\" cond=\""+guard+"\"/></state></scxml>";
            assertThrows(IllegalArgumentException.class,()->new ScxmlEngine(xml),guard);
        }
    }
    @Test void externalEntitiesAreRejected() {
        assertThrows(IllegalArgumentException.class,()->new ScxmlEngine("""
            <!DOCTYPE scxml [<!ENTITY test SYSTEM "file:///etc/passwd">]>
            <scxml xmlns="http://www.w3.org/2005/07/scxml" version="1.0" initial="a"><state id="a"/></scxml>
            """));
    }
}
