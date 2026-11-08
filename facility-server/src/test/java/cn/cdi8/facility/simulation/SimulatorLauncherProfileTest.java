package cn.cdi8.facility.simulation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** The facility can materialize its own simulation without a workbench model store. */
class SimulatorLauncherProfileTest {
    @Test
    void resolvesBundledFacilityProfileAndComponentModels(@TempDir Path directory) throws Exception {
        var launcher = new SimulatorLauncher(new ObjectMapper(), "/missing/device-simulator.jar",
            directory.toString(), true, "classpath:simulation/gxlf-facility.json");
        var request = launcher.initialRequest();
        assertEquals(14, request.path("devices").size());
        assertEquals("seed_01", request.path("devices").get(0).path("id").asText());
        assertTrue(request.path("devices").get(0).path("xml").asText().contains("componentMetadata"));
        assertEquals("embedded-gxlf-1", request.path("devices").get(0).path("revision").asText());
    }
}
