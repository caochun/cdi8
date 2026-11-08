package cn.cdi8.facility.runtime;

import cn.cdi8.facility.contract.FacilityBindingModel;
import cn.cdi8.facility.contract.FacilityStateProjectionModel;

import cn.cdi8.facility.contract.SystemDefinitionModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * Pure facility-fact evaluation over the current device evidence.
 *
 * This layer deliberately knows nothing about BPMN runs or operating-constraint
 * latching. It turns snapshots into deviceFacts, facts and guards; supervision
 * consumes the resulting facts separately.
 */
final class FacilityFactProjection {
    record Result(ObjectNode facts, ObjectNode guards, ObjectNode deviceFacts,
                  ObjectNode observations, String integrity, String reason) {}

    private final ObjectMapper json;
    private final BooleanSupplier connected;

    FacilityFactProjection(ObjectMapper json, BooleanSupplier connected) {
        this.json = json;
        this.connected = connected;
    }

    Result evaluate(SystemDefinitionModel definition, FacilityBindingModel binding,
                    FacilityStateProjectionModel projection,
                    Map<String, JsonNode> snapshots,
                    Map<String, String> deviceBindingKeys,
                    Map<String, Long> observedAt,
                    Map<String, String> invalidObservations) {
        ObjectNode facts = json.createObjectNode();
        Map<String, Status> statuses = new LinkedHashMap<>();
        for (var fact : projection.evaluationOrder()) {
            Status result = evaluateCondition(fact.condition(), statuses, definition, binding, projection,
                snapshots, deviceBindingKeys, observedAt, invalidObservations);
            statuses.put(fact.id(), result);
            facts.putObject(fact.id()).put("status", result.status).put("reason", result.reason)
                .put("label", fact.label()).put("role", fact.role().value());
        }

        ObjectNode guards = json.createObjectNode();
        for (var guard : projection.guards()) {
            Status result = evaluateCondition(guard.condition(), statuses, definition, binding, projection,
                snapshots, deviceBindingKeys, observedAt, invalidObservations);
            guards.putObject(guard.id()).put("status", result.status).put("reason", result.reason)
                .put("label", guard.label());
        }

        String integrity = "valid", reason = "";
        for (Status result : statuses.values()) {
            if ("unknown".equals(result.status)) {
                integrity = "unknown";
                reason = result.reason;
                break;
            }
            if ("violated".equals(result.status)) {
                integrity = "violated";
                reason = result.reason;
            }
        }

        ObjectNode deviceFacts = json.createObjectNode();
        for (String device : deviceBindingKeys.keySet()) {
            ObjectNode values = deviceFacts.putObject(device);
            for (var fact : projection.deviceFacts()) {
                if (!fact.componentType().equals(componentTypeOf(device, binding, deviceBindingKeys))) continue;
                JsonNode snapshot = snapshots.get(device);
                Status status = deviceStatus(device, projection, snapshots, observedAt, invalidObservations);
                if ("valid".equals(status.status) && !matches(snapshot, fact.states(), fact.outerStates()))
                    status = Status.violated("设备状态不满足：" + device + " / " + snapshot.path("state_id").asText());
                ObjectNode value = values.putObject(fact.id()).put("status", status.status)
                    .put("reason", status.reason).put("label", fact.label()).put("componentType", fact.componentType());
                if (snapshot != null) value.put("source_id", snapshot.path("source_id").asText())
                    .put("state_revision", snapshot.path("state_revision").asLong())
                    .put("state", snapshot.path("state_id").asText());
            }
        }

        ObjectNode observations = json.createObjectNode();
        for (var entry : snapshots.entrySet()) {
            observations.putObject(entry.getKey())
                .put("observed", observedAt.containsKey(entry.getKey()))
                .put("observedAt", observedAt.getOrDefault(entry.getKey(), 0L))
                .put("source_id", entry.getValue().path("source_id").asText())
                .put("state_revision", entry.getValue().path("state_revision").asLong())
                .put("reason", invalidObservations.getOrDefault(entry.getKey(), ""));
        }
        return new Result(facts, guards, deviceFacts, observations, integrity, reason);
    }

    private Status evaluateCondition(FacilityStateProjectionModel.Condition condition,
                                     Map<String, Status> statuses,
                                     SystemDefinitionModel definition,
                                     FacilityBindingModel binding,
                                     FacilityStateProjectionModel projection,
                                     Map<String, JsonNode> snapshots,
                                     Map<String, String> deviceBindingKeys,
                                     Map<String, Long> observedAt,
                                     Map<String, String> invalidObservations) {
        return switch (condition.kind()) {
            case "factPredicate" -> statuses.getOrDefault(condition.ref(),
                Status.unknown("事实尚未计算：" + condition.ref()));
            case "devicePredicate", "actionPredicate" -> quantifiedRequirement(condition, definition, binding,
                projection, snapshots, deviceBindingKeys, observedAt, invalidObservations);
            case "observationPredicate" -> observationStatus(condition.ref(), condition.expected(), definition,
                binding, projection, snapshots, deviceBindingKeys, observedAt, invalidObservations);
            case "all", "any" -> {
                boolean any = "any".equals(condition.kind());
                Status result = any ? Status.violated("任一条件均未满足") : Status.ok();
                for (var term : condition.terms()) {
                    Status value = evaluateCondition(term, statuses, definition, binding, projection, snapshots,
                        deviceBindingKeys, observedAt, invalidObservations);
                    result = any ? either(result, value) : combine(result, value);
                }
                yield result;
            }
            default -> throw new IllegalStateException("不支持的条件：" + condition.kind());
        };
    }

    private Status quantifiedRequirement(FacilityStateProjectionModel.Condition condition,
                                         SystemDefinitionModel definition,
                                         FacilityBindingModel binding,
                                         FacilityStateProjectionModel projection,
                                         Map<String, JsonNode> snapshots,
                                         Map<String, String> deviceBindingKeys,
                                         Map<String, Long> observedAt,
                                         Map<String, String> invalidObservations) {
        var selected = deviceBindingKeys.keySet().stream()
            .filter(id -> condition.ref().equals(binding.instanceSetForBindingKey(deviceBindingKeys.get(id))))
            .toList();
        if (selected.isEmpty()) return Status.unknown("缺少设施设备观测：" + condition.ref());
        Status result = condition.anyDevice() ? Status.violated("没有设备满足工作状态判据") : Status.ok();
        for (String id : selected) {
            Status value = deviceStatus(id, projection, snapshots, observedAt, invalidObservations);
            JsonNode snapshot = snapshots.get(id);
            if ("valid".equals(value.status)) {
                if ("actionPredicate".equals(condition.kind())) {
                    if (!snapshot.has("active_action") || !snapshot.has("active_command_id"))
                        value = Status.unknown("缺少动作执行观测：" + id);
                    else if (snapshot.path("active_action").isNull() != snapshot.path("active_command_id").isNull())
                        value = Status.unknown("动作与命令标识观测不一致：" + id);
                    else if (condition.active() != condition.action().equals(snapshot.path("active_action").asText()))
                        value = Status.violated("设备动作状态不满足：" + id + " / " + condition.action());
                } else if (!matches(snapshot, condition.states(), condition.outerStates())) {
                    value = Status.violated("设备状态不满足：" + id + " / " + snapshot.path("state_id").asText());
                }
            }
            result = condition.anyDevice() ? either(result, value) : combine(result, value);
        }
        return result;
    }

    private Status deviceRequirement(Set<String> sets, String states, String outerStates, boolean connectivityOnly,
                                     FacilityBindingModel binding, FacilityStateProjectionModel projection,
                                     Map<String, JsonNode> snapshots, Map<String, String> deviceBindingKeys,
                                     Map<String, Long> observedAt, Map<String, String> invalidObservations) {
        if (!connected.getAsBoolean()) return Status.unknown("Tango 连接不可用");
        Status result = Status.ok();
        for (String set : sets) {
            var selected = deviceBindingKeys.keySet().stream()
                .filter(id -> set.equals(binding.instanceSetForBindingKey(deviceBindingKeys.get(id))))
                .toList();
            if (selected.isEmpty()) {
                result = combine(result, Status.unknown("缺少设施设备观测：" + set));
                continue;
            }
            for (String id : selected) {
                Status item = deviceStatus(id, projection, snapshots, observedAt, invalidObservations);
                if ("valid".equals(item.status) && !connectivityOnly && !matches(snapshots.get(id), states, outerStates))
                    item = Status.violated("设备状态不满足：" + id + " / " + snapshots.get(id).path("state_id").asText());
                result = combine(result, item);
            }
        }
        return result;
    }

    private Status observationStatus(String id, String expected, SystemDefinitionModel definition,
                                     FacilityBindingModel binding, FacilityStateProjectionModel projection,
                                     Map<String, JsonNode> snapshots, Map<String, String> deviceBindingKeys,
                                     Map<String, Long> observedAt, Map<String, String> invalidObservations) {
        var observation = projection.observation(id);
        if ("external".equals(observation.adapter())) return Status.unknown("外部观测尚未绑定：" + id);
        var endpoint = definition.endpoint(observation.source(), true);
        var declared = definition.endpointInstanceSets(endpoint);
        if ("device-connectivity".equals(observation.adapter())) {
            var participating = new LinkedHashSet<String>();
            for (String key : deviceBindingKeys.values()) {
                String set = binding.instanceSetForBindingKey(key);
                if (declared.contains(set)) participating.add(set);
            }
            if (participating.isEmpty()) return Status.unknown("尚未取得设备连接观测");
            return deviceRequirement(participating, "", "", true, binding, projection, snapshots,
                deviceBindingKeys, observedAt, invalidObservations);
        }
        return deviceRequirement(new LinkedHashSet<>(declared), expected, expected, false, binding, projection,
            snapshots, deviceBindingKeys, observedAt, invalidObservations);
    }

    private String componentTypeOf(String id, FacilityBindingModel binding, Map<String, String> deviceBindingKeys) {
        String deviceBindingKey = deviceBindingKeys.get(id);
        return deviceBindingKey == null ? "" : binding.componentTypeForBindingKey(deviceBindingKey);
    }

    private Status deviceStatus(String id, FacilityStateProjectionModel projection,
                                Map<String, JsonNode> snapshots, Map<String, Long> observedAt,
                                Map<String, String> invalidObservations) {
        if (!connected.getAsBoolean()) return Status.unknown("Tango 连接不可用");
        if (invalidObservations.containsKey(id)) return Status.unknown(id + " / " + invalidObservations.get(id));
        long maxAge = projection.evidenceMaxAgeMs();
        if (!snapshots.containsKey(id) || System.currentTimeMillis() - observedAt.getOrDefault(id, 0L) > maxAge)
            return Status.unknown("设备观测缺失或过期：" + id);
        return Status.ok();
    }

    private static boolean matches(JsonNode snapshot, String states, String outerStates) {
        return words(states).contains(snapshot.path("state_id").asText())
            || words(outerStates).contains(snapshot.path("outer_state").asText());
    }

    private static Set<String> words(String value) {
        if (value == null || value.isBlank()) return Set.of();
        return new LinkedHashSet<>(Arrays.asList(value.trim().split("\\s+")));
    }

    private static Status combine(Status left, Status right) {
        if ("violated".equals(left.status) || "violated".equals(right.status))
            return "violated".equals(left.status) ? left : right;
        if ("unknown".equals(left.status) || "unknown".equals(right.status))
            return "unknown".equals(left.status) ? left : right;
        return Status.ok();
    }

    private static Status either(Status left, Status right) {
        if ("valid".equals(left.status) || "valid".equals(right.status)) return Status.ok();
        if ("unknown".equals(left.status) || "unknown".equals(right.status))
            return "unknown".equals(left.status) ? left : right;
        return left;
    }

    private record Status(String status, String reason) {
        static Status ok() { return new Status("valid", ""); }
        static Status violated(String reason) { return new Status("violated", reason); }
        static Status unknown(String reason) { return new Status("unknown", reason); }
    }
}
