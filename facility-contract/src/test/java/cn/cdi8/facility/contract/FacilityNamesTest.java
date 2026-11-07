package cn.cdi8.facility.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FacilityNamesTest {
    @Test void oldTargetAndDependencyKeepTheirIdentityAndLatchedEvidence() throws Exception {
        var target=FacilityNames.target(Map.of("id","source_01","component","seed","runtimeKey","source-adapter"));
        assertEquals("seed",target.get("componentType"));
        assertEquals("source-adapter",target.get("deviceBindingKey"));
        assertFalse(target.containsKey("component"));assertFalse(target.containsKey("runtimeKey"));
        var plan=FacilityNames.dependencyPlan((ObjectNode)new ObjectMapper().readTree("""
            {"id":"command-1","component":"multipass","latched":true,"status":"unknown","requirements":[
              {"component":"seed","logical":"seed-01","device":"source_01","status":"unknown","observation":{"component":"seed","status":"unknown"}}
            ]}
            """));
        assertEquals("multipass",plan.path("componentType").asText());
        assertTrue(plan.path("latched").asBoolean());assertEquals("unknown",plan.path("status").asText());
        var requirement=plan.path("requirements").get(0);
        assertEquals("seed-01",requirement.path("componentInstanceId").asText());
        assertEquals("source_01",requirement.path("device").asText());
        assertEquals("seed",requirement.path("observation").path("componentType").asText());
        assertThrows(IllegalArgumentException.class,()->FacilityNames.target(Map.of("componentType","seed","component","pump")));
    }
}
