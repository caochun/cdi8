package cn.cdi8.workbench.facility;

import cn.cdi8.workbench.model.ModelStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The simulator generation is expanded once, before Tango receives any devices. */
class FacilitySimulationProfileTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void expandsMultipleInstancesWithPinnedModelRevision(@TempDir Path directory) throws Exception {
        var store = new ModelStore(json, directory.toString());
        store.initialize();
        var model = store.get("preamp-state-machine");
        String revision = model.path("revisions").get(model.path("revisions").size() - 1).path("id").asText();

        var config = json.createObjectNode().put("autoComplete", false);
        config.putArray("devices").addObject().put("model", "preamp-state-machine")
            .put("revision", revision).put("component", "preamp").put("count", 2)
            .put("delayMs", 30);

        var profile = FacilitySimulationProfile.resolve(config, store);
        assertEquals(Set.of("preamp_01", "preamp_02"),
            profile.devices().stream().map(FacilitySimulationProfile.Device::id).collect(java.util.stream.Collectors.toSet()));
        assertTrue(profile.devices().stream().allMatch(device -> revision.equals(device.revision())));
        assertEquals(2, profile.toSimulatorDevices(json).size());
    }

    @Test
    void rejectsInvalidOrDuplicateComponentGroups(@TempDir Path directory) throws Exception {
        var store = new ModelStore(json, directory.toString());
        store.initialize();
        var model = store.get("preamp-state-machine");
        String revision = model.path("revisions").get(model.path("revisions").size() - 1).path("id").asText();
        var config = json.createObjectNode();
        config.putArray("devices").addObject().put("model", "preamp-state-machine")
            .put("revision", revision).put("component", "preamp").put("count", 0);
        assertThrows(IllegalArgumentException.class, () -> FacilitySimulationProfile.resolve(config, store));

        var devices = config.putArray("devices");
        devices.removeAll();
        devices.addObject().put("model", "preamp-state-machine")
            .put("revision", revision).put("component", "preamp").put("count", 1);
        devices.addObject().put("model", "preamp-state-machine")
            .put("revision", revision).put("component", "preamp").put("count", 1);
        assertThrows(IllegalArgumentException.class, () -> FacilitySimulationProfile.resolve(config, store));
    }
}
