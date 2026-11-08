package cn.cdi8.tango;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates the device observation envelope before it enters facility state.
 *
 * <p>The contract checks structure and internal consistency only. It does not
 * know facility facts, supervision rules, or workflow semantics.</p>
 */
public final class SnapshotContract {
    private static final Pattern DIGEST = Pattern.compile("[A-Fa-f0-9]{64}");
    private static final Set<String> QUALIFICATIONS = Set.of("unknown", "valid", "invalid");

    private SnapshotContract() {}

    public static JsonNode validate(JsonNode value) {
        if (value == null || !value.isObject()) throw invalid("设备快照必须为 JSON 对象");
        String kind = value.path("snapshot_kind").asText("");
        if (!Set.of("component", "condition-source").contains(kind))
            throw invalid("设备快照 snapshot_kind 无效：" + kind);
        requiredText(value, "source_id");
        requiredRevision(value);
        if ("condition-source".equals(kind)) validateConditionSource(value);
        else validateComponent(value);
        return value;
    }

    private static void validateComponent(JsonNode value) {
        requiredText(value, "model");
        requiredPositiveInteger(value, "model_version");
        requiredText(value, "model_digest");
        if (!DIGEST.matcher(value.path("model_digest").asText()).matches())
            throw invalid("设备快照 model_digest 必须为 SHA-256");
        nullableText(value, "model_revision");
        for (String field : Set.of("componentLabel", "engine", "state_id", "outer_state",
            "outer_state_label", "inner_state", "inner_state_label", "task_state"))
            requiredText(value, field);
        requiredObject(value, "context");
        requiredObject(value, "action_contracts");
        requiredBoolean(value, "supports_entry_condition");
        requiredBoolean(value, "supports_action_contract");
        requiredNullable(value, "active_command_id");
        requiredNullable(value, "active_action");
        nullableText(value, "active_command_id");
        nullableText(value, "active_action");
        requiredNullable(value, "last_result");
        if (value.has("faults") && !value.get("faults").isArray())
            throw invalid("设备快照 faults 必须为数组");
        if (value.has("configuration") && !value.get("configuration").isObject())
            throw invalid("设备快照 configuration 必须为对象");
        if (value.has("data_qualification")) {
            String qualification = value.path("data_qualification").asText("");
            if (!QUALIFICATIONS.contains(qualification)) throw invalid("设备快照 data_qualification 无效");
        }
        boolean active = value.get("active_command_id").isTextual();
        boolean action = value.get("active_action").isTextual();
        if (active != action) throw invalid("设备快照 active_command_id 与 active_action 不一致");
        if (active != "executing".equals(value.path("task_state").asText()))
            throw invalid("设备快照 task_state 与活动命令不一致");
        if (!value.get("last_result").isNull()) {
            JsonNode result = value.get("last_result");
            if (!result.isObject()) throw invalid("设备快照 last_result 必须为对象，实际为：" + result);
            for (String field : Set.of("command_id", "action", "status", "key_state"))
                requiredText(result, field);
        }
    }

    private static void validateConditionSource(JsonNode value) {
        requiredText(value, "model");
        requiredText(value, "componentLabel");
        requiredText(value, "engine");
        requiredObject(value, "conditions");
        requiredText(value, "task_state");
        requiredNullable(value, "active_command_id");
        requiredNullable(value, "active_action");
        nullableText(value, "active_command_id");
        nullableText(value, "active_action");
        requiredNullable(value, "last_result");
        boolean active = value.get("active_command_id").isTextual();
        boolean action = value.get("active_action").isTextual();
        if (active != action) throw invalid("条件源快照 active_command_id 与 active_action 不一致");
        if (active != "executing".equals(value.path("task_state").asText()))
            throw invalid("条件源快照 task_state 与活动命令不一致");
        if (!value.get("last_result").isNull()) requiredObject(value.get("last_result"), "last_result");
    }

    private static void requiredRevision(JsonNode value) {
        if (!value.has("state_revision") || !value.get("state_revision").isIntegralNumber()
            || value.get("state_revision").asLong() < 0)
            throw invalid("设备快照 state_revision 必须为非负整数");
    }

    private static void requiredPositiveInteger(JsonNode value, String field) {
        if (!value.has(field) || !value.get(field).isIntegralNumber() || value.get(field).asLong() < 1)
            throw invalid("设备快照 " + field + " 必须为正整数");
    }

    private static void requiredText(JsonNode value, String field) {
        if (!value.has(field) || !value.get(field).isTextual() || value.get(field).asText().isBlank())
            throw invalid("设备快照 " + field + " 必须为非空字符串");
    }

    private static void nullableText(JsonNode value, String field) {
        if (!value.has(field) || value.get(field).isNull()) return;
        requiredText(value, field);
    }

    private static void requiredBoolean(JsonNode value, String field) {
        if (!value.has(field) || !value.get(field).isBoolean())
            throw invalid("设备快照 " + field + " 必须为布尔值");
    }

    private static void requiredObject(JsonNode value, String field) {
        if (!value.has(field) || !value.get(field).isObject())
            throw invalid("设备快照 " + field + " 必须为对象");
    }

    private static void requiredNullable(JsonNode value, String field) {
        if (!value.has(field)) throw invalid("设备快照缺少字段：" + field);
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
