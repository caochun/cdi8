package cn.cdi8.workbench.facility;

import cn.cdi8.workbench.model.ModelStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.HashSet;
import java.util.Set;

/**
 * Resolved simulator topology for one facility generation.
 *
 * The profile is the adapter-side boundary: it turns the operator's component
 * groups into immutable device instances, while keeping model revision and
 * runtime naming in one place. Facts and workflow code consume the resulting
 * Tango devices; they do not expand counts themselves.
 */
public final class FacilitySimulationProfile {
    public record Device(String id, String component, String revision, String xml,
                         boolean autoComplete, int delayMs) {}

    private final java.util.List<Device> devices;

    private FacilitySimulationProfile(java.util.List<Device> devices) {
        this.devices = java.util.List.copyOf(devices);
    }

    public java.util.List<Device> devices() {
        return devices;
    }

    public ArrayNode toSimulatorDevices(ObjectMapper json) {
        ArrayNode result = json.createArrayNode();
        for (Device device : devices) {
            result.addObject().put("id", device.id()).put("component", device.component())
                .put("revision", device.revision()).put("xml", device.xml())
                .put("autoComplete", device.autoComplete()).put("delayMs", device.delayMs());
        }
        return result;
    }

    public static FacilitySimulationProfile resolve(JsonNode config, ModelStore store) {
        if (!config.path("devices").isArray()) throw new IllegalArgumentException("模拟环境必须提供 devices 数组");
        var devices = new java.util.ArrayList<Device>();
        Set<String> ids = new HashSet<>();
        for (JsonNode row : config.path("devices")) {
            String modelId = required(row, "model");
            String revision = required(row, "revision");
            String component = required(row, "component");
            var model = store.get(modelId);
            if (!"scxml".equals(model.path("kind").asText()))
                throw new IllegalArgumentException("设备必须使用 SCXML 模型");
            JsonNode version = store.revision(modelId, revision);
            int count = row.path("count").asInt(1);
            if (count < 1) throw new IllegalArgumentException("每组至少需要一个实例");
            if (count > 200) throw new IllegalArgumentException("单组实例数不能超过 200");
            boolean autoComplete = row.path("autoComplete").asBoolean(false);
            int delayMs = row.path("delayMs").asInt(500);
            if (delayMs < 0) throw new IllegalArgumentException("设备动作延迟不能为负数");
            for (int index = 1; index <= count; index++) {
                String id = component + "_" + String.format(java.util.Locale.ROOT, "%02d", index);
                if (!ids.add(id)) throw new IllegalArgumentException("组件类型重复：" + component);
                devices.add(new Device(id, component, revision, version.path("xml").asText(), autoComplete, delayMs));
            }
        }
        return new FacilitySimulationProfile(devices);
    }

    private static String required(JsonNode row, String name) {
        String value = row.path(name).asText();
        if (value.isBlank()) throw new IllegalArgumentException("设备配置缺少 " + name);
        return value;
    }
}
