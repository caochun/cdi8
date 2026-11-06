package cn.cdi8.workbench.facility;

import cn.cdi8.workbench.model.ModelStore;
import cn.cdi8.workbench.systemdefinition.SystemDefinitionModel;
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
    @Test
    void expandsInstancesFromPublishedDefinitionAndBinding(@TempDir Path directory) throws Exception {
        var store = new ModelStore(json, directory.toString());
        store.initialize();
        String definitionRevision = latest(store, "gxlf-facility");
        String bindingRevision = latest(store, "gxlf-facility-binding");
        var definition = SystemDefinitionModel.parse(store.revision("gxlf-facility", definitionRevision).path("xml").asText());
        var binding = FacilityBindingModel.parse(store.revision("gxlf-facility-binding", bindingRevision).path("xml").asText(), definition);

        var config = json.createObjectNode();
        var facility = config.putObject("facility");
        facility.putObject("definition").put("model", "gxlf-facility").put("revision", definitionRevision);
        facility.putObject("binding").put("model", "gxlf-facility-binding").put("revision", bindingRevision);
        var components = facility.putObject("components");
        var modelRevisions = new java.util.HashMap<String, String>();
        for (var instance : binding.topology().instances()) {
            String modelId = switch (instance.component()) {
                case "seed" -> "seed-source-state-machine";
                case "shg" -> "shg-injection-state-machine";
                default -> instance.component() + "-state-machine";
            };
            String revision = modelRevisions.computeIfAbsent(modelId, id -> latest(store, id));
            components.putObject(instance.runtimeKey()).put("model", modelId).put("revision", revision).put("delayMs", 30);
        }

        var profile = FacilitySimulationProfile.resolve(config, store);
        assertEquals(binding.topology().instances().size(), profile.devices().size());
        assertEquals(10, profile.devices().stream().filter(device -> "preamp".equals(device.component())).count());
        assertTrue(profile.devices().stream().anyMatch(device -> "preamp_10".equals(device.id())));
    }

    private static String latest(ModelStore store, String modelId) {
        var model = store.get(modelId);
        return model.path("revisions").get(model.path("revisions").size() - 1).path("id").asText();
    }

}
