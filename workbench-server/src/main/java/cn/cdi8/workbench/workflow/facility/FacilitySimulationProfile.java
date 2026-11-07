package cn.cdi8.workbench.workflow.facility;

import cn.cdi8.facility.contract.FacilityBindingModel;
import cn.cdi8.facility.contract.FacilityInstanceTopology;

import cn.cdi8.workbench.model.ModelStore;
import cn.cdi8.facility.contract.SystemDefinitionModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
    /** Old saved preparation files used component for a device binding key. */
    public static JsonNode normalizeConfiguration(JsonNode input) {
        if(!input.isObject())throw new IllegalArgumentException("运行配置必须为对象");
        JsonNode config=input.deepCopy();
        for(String field:java.util.List.of("devices","appliedDevices"))normalizeDevices(config.path(field));
        normalizeDevices(config.path("environment").path("devices"));
        for(String field:java.util.List.of("facility","appliedFacility"))
            if(config.path(field) instanceof com.fasterxml.jackson.databind.node.ObjectNode facility)
                cn.cdi8.facility.contract.FacilityNames.rename(facility,"deviceGroups","components");
        return config;
    }
    private static void normalizeDevices(JsonNode devices) {
        for(JsonNode row:devices)if(row instanceof com.fasterxml.jackson.databind.node.ObjectNode device)
            cn.cdi8.facility.contract.FacilityNames.rename(device,"deviceBindingKey","component");
    }

    public record Device(String id, String deviceBindingKey, String revision, String xml,
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
            result.addObject().put("id", device.id()).put("deviceBindingKey", device.deviceBindingKey())
                .put("revision", device.revision()).put("xml", device.xml())
                .put("autoComplete", device.autoComplete()).put("delayMs", device.delayMs());
        }
        return result;
    }

    /**
     * Resolves either the legacy explicit device groups or a facility profile.
     * A facility profile derives instance count and runtime IDs from the
     * published system-definition and facility-binding revisions.
     */
    public static FacilitySimulationProfile resolve(JsonNode config, ModelStore store) {
        config=normalizeConfiguration(config);
        if (config.path("facility").isObject()) return resolveFacility(config.path("facility"), store);
        return resolveDeviceGroups(config, store);
    }

    private static FacilitySimulationProfile resolveDeviceGroups(JsonNode config, ModelStore store) {
        if (!config.path("devices").isArray()) throw new IllegalArgumentException("模拟环境必须提供 devices 数组");
        var devices = new java.util.ArrayList<Device>();
        Set<String> ids = new HashSet<>();
        for (JsonNode row : config.path("devices")) {
            String modelId = required(row, "model");
            String revision = required(row, "revision");
            String deviceBindingKey = required(row, "deviceBindingKey");
            var model = store.get(modelId);
            if (!"scxml".equals(model.path("kind").asText()))
                throw new IllegalArgumentException("设备必须使用 SCXML 模型");
            JsonNode version = store.revision(modelId, revision);
            int count = row.path("count").asInt(1);
            if (count < 1) throw new IllegalArgumentException("每组至少需要一个实例");
            if (count > 200) throw new IllegalArgumentException("单组实例数不能超过 200");
            boolean autoComplete = row.path("autoComplete").asBoolean(false);
            int delayMs = delay(row);
            for (int index = 1; index <= count; index++) {
                String id = deviceBindingKey + "_" + String.format(java.util.Locale.ROOT, "%02d", index);
                if (!ids.add(id)) throw new IllegalArgumentException("设备绑定键重复：" + deviceBindingKey);
                devices.add(new Device(id, deviceBindingKey, revision, version.path("xml").asText(), autoComplete, delayMs));
            }
        }
        return new FacilitySimulationProfile(devices);
    }

    private static FacilitySimulationProfile resolveFacility(JsonNode facility, ModelStore store) {
        var definition = systemDefinition(facility.path("definition"), store, "设施系统定义");
        String bindingXml = systemXml(facility.path("binding"), store, "设施绑定");
        var binding = FacilityBindingModel.parse(bindingXml, definition);
        JsonNode deviceGroups = facility.path("deviceGroups");
        if (!deviceGroups.isObject()) throw new IllegalArgumentException("设施模拟配置必须提供 deviceGroups 对象");

        var expected = new HashSet<String>();
        for (var instance : binding.topology().instances()) expected.add(instance.deviceBindingKey());
        var configuredKeys = deviceGroups.fieldNames();
        while (configuredKeys.hasNext()) {
            String key = configuredKeys.next();
            if (!expected.contains(key)) throw new IllegalArgumentException("设施模拟配置包含未绑定设备绑定键：" + key);
        }

        var byBindingKey = new LinkedHashMap<String, java.util.List<FacilityInstanceTopology.Instance>>();
        for (var instance : binding.topology().instances())
            byBindingKey.computeIfAbsent(instance.deviceBindingKey(), ignored -> new java.util.ArrayList<>()).add(instance);

        var devices = new java.util.ArrayList<Device>();
        for (var entry : byBindingKey.entrySet()) {
            String deviceBindingKey = entry.getKey();
            JsonNode group = deviceGroups.path(deviceBindingKey);
            if (!group.isObject()) throw new IllegalArgumentException("设施模拟配置缺少设备绑定键：" + deviceBindingKey);
            String modelId = required(group, "model");
            String revision = required(group, "revision");
            var model = store.get(modelId);
            if (!"scxml".equals(model.path("kind").asText()))
                throw new IllegalArgumentException("设施设备必须使用 SCXML 模型：" + deviceBindingKey);
            JsonNode version = store.revision(modelId, revision);
            int count = group.path("count").isIntegralNumber()
                ? group.path("count").asInt() : entry.getValue().size();
            if (count < 1 || count > entry.getValue().size())
                throw new IllegalArgumentException("设备绑定键实例数量超出系统定义：" + deviceBindingKey);
            for (int index = 0; index < count; index++) {
                var instance = entry.getValue().get(index);
                devices.add(new Device(instance.deviceId(), deviceBindingKey, revision,
                    version.path("xml").asText(), group.path("autoComplete").asBoolean(false), delay(group)));
            }
        }
        return new FacilitySimulationProfile(devices);
    }

    private static SystemDefinitionModel systemDefinition(JsonNode reference, ModelStore store, String label) {
        return SystemDefinitionModel.parse(systemXml(reference, store, label));
    }

    private static String systemXml(JsonNode reference, ModelStore store, String label) {
        String modelId = required(reference, "model");
        String revision = required(reference, "revision");
        var model = store.get(modelId);
        if (!"system".equals(model.path("kind").asText())) throw new IllegalArgumentException(label + "必须使用 system 模型");
        return store.revision(modelId, revision).path("xml").asText();
    }

    private static int delay(JsonNode row) {
        int delayMs = row.path("delayMs").asInt(500);
        if (delayMs < 0) throw new IllegalArgumentException("设备动作延迟不能为负数");
        return delayMs;
    }

    private static String required(JsonNode row, String name) {
        String value = row.path(name).asText();
        if (value.isBlank()) throw new IllegalArgumentException("配置缺少 " + name);
        return value;
    }
}
