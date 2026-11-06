package cn.cdi8.workbench.facility;

import cn.cdi8.workbench.integration.tango.TangoGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Phase-one tests: simulator/device evidence is mapped to facts without BPMN or supervision assertions. */
class FacilityFactProjectionTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PROJECTION = """
        <facility-state-projection xmlns="https://cdi8.cn/facility-state/v1"
          id="phase-one-facts" definitionRef="gxlf-facility" bindingRef="gxlf-facility-binding" evidenceMaxAgeMs="10000">
          <observations>
            <observation id="unused" adapter="device-state"><source instanceSetRef="preamp" portRef="state-out" /></observation>
          </observations>
          <facts>
            <fact id="anyPreampWakeup" label="任一预放处于唤醒">
              <condition><devicePredicate instanceSetRef="preamp" outerStates="wakeup" quantifier="any" /></condition>
            </fact>
            <fact id="allPreampWakeup" label="全部已部署预放处于唤醒">
              <condition><devicePredicate instanceSetRef="preamp" outerStates="wakeup" /></condition>
            </fact>
            <fact id="preampWakeupExecuting" label="任一预放正在执行唤醒">
              <condition><actionPredicate instanceSetRef="preamp" actionRef="preamp_wakeup" active="true" quantifier="any" /></condition>
            </fact>
            <fact id="allPreampActionsIdle" label="全部已部署预放动作空闲">
              <condition><actionPredicate instanceSetRef="preamp" actionRef="preamp_wakeup" active="false" quantifier="all" /></condition>
            </fact>
          </facts>
        </facility-state-projection>
        """;

    private FacilityDependencyRuntimeTest.Transport transport;
    private FacilityStateRuntime states;

    @BeforeEach
    void setUp() throws Exception {
        transport = new FacilityDependencyRuntimeTest.Transport();
        transport.add("preamp_01", "preamp", "ready");
        transport.add("preamp_02", "preamp", "ready");
        transport.values.values().forEach(value -> value.putNull("active_action").putNull("active_command_id"));
        states = new FacilityStateRuntime(JSON, transport, event -> {});
        String defaultProjection = model("gxlf-facility-state-projection.xml");
        String deviceFacts = defaultProjection.substring(defaultProjection.indexOf("<deviceFacts>"),
            defaultProjection.indexOf("</deviceFacts>") + "</deviceFacts>".length());
        states.activate(model("gxlf-facility.system-definition.xml"), model("gxlf-facility-binding.xml"),
            PROJECTION.replace("<facts>", deviceFacts + "<facts>"));
        states.refresh();
    }

    @AfterEach
    void tearDown() {
        transport.close();
    }

    @Test
    void anyAndAllFactsFollowMultipleInstances() {
        setState("preamp_02", "wakeup");
        var facts = states.factAssessment(false).path("facts");

        assertEquals("valid", facts.path("anyPreampWakeup").path("status").asText());
        assertEquals("violated", facts.path("allPreampWakeup").path("status").asText());

        setState("preamp_01", "wakeup");
        facts = states.factAssessment(false).path("facts");
        assertEquals("valid", facts.path("allPreampWakeup").path("status").asText());
    }

    @Test
    void actionFactsDoNotConfuseResultStateWithExecutionState() {
        setState("preamp_01", "wakeup");
        setState("preamp_02", "wakeup");
        var facts = states.factAssessment(false).path("facts");
        assertEquals("violated", facts.path("preampWakeupExecuting").path("status").asText());
        assertEquals("valid", facts.path("allPreampActionsIdle").path("status").asText());

        var value = transport.values.get("preamp_01");
        value.put("active_action", "preamp_wakeup").put("active_command_id", "cmd-01").put("state_revision", 3);
        states.observe(new TangoGateway.Notice(JSON.createObjectNode().put("device_id", "preamp_01")
            .put("connected", true).set("snapshot", value.deepCopy())));
        facts = states.factAssessment(false).path("facts");
        assertEquals("valid", facts.path("preampWakeupExecuting").path("status").asText());
        assertEquals("violated", facts.path("allPreampActionsIdle").path("status").asText());
    }

    @Test
    void invalidObservationIsUnknownAndFactViewHasNoSupervisionOutput() {
        setState("preamp_01", "wakeup");
        setState("preamp_02", "wakeup");
        var value = transport.values.get("preamp_01");
        value.put("state_revision", 0);
        states.observe(new TangoGateway.Notice(JSON.createObjectNode().put("type", "state_revision_rollback")
            .put("device_id", "preamp_01").put("connected", false).put("reason", "revision rollback")));

        var assessment = states.factAssessment(false);
        assertFalse(assessment.has("constraints"));
        assertEquals("valid", assessment.path("facts").path("anyPreampWakeup").path("status").asText());
        assertEquals("unknown", assessment.path("facts").path("allPreampWakeup").path("status").asText());
    }

    private void setState(String id, String outer) {
        var value = transport.values.get(id);
        value.put("outer_state", outer).put("state_id", outer + "-leaf")
            .put("state_revision", value.path("state_revision").asLong() + 1);
        states.observe(new TangoGateway.Notice(JSON.createObjectNode().put("device_id", id)
            .put("connected", true).set("snapshot", value.deepCopy())));
    }

    private static String model(String name) throws Exception {
        String xml = Files.readString(Path.of("examples/gxlf/system/" + name));
        return name.endsWith(".system-definition.xml") ? FacilityTestModels.opticalOnly(xml) : xml;
    }
}
