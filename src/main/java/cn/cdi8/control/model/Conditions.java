package cn.cdi8.control.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** A deliberately small predicate language; no EL, reflection, or arbitrary scripts. */
public final class Conditions {
    private static final ObjectMapper JSON = new ObjectMapper();
    private Conditions() {}

    public static void validate(JsonNode rule, JsonNode fields) {
        require(rule.isObject(), "condition must be an object");
        if (rule.has("all") || rule.has("any")) {
            require(rule.size() == 1, "all/any cannot be combined with other keys");
            JsonNode items = rule.has("all") ? rule.get("all") : rule.get("any");
            require(items.isArray() && !items.isEmpty(), "all/any needs non-empty conditions");
            items.forEach(child -> validate(child, fields));
            return;
        }
        require(rule.size() == 2 && rule.path("field").isTextual(), "predicate requires field and equals/in");
        String name = rule.get("field").asText();
        require(fields.has(name), "unknown state field: " + name);
        if (rule.has("equals")) checkValue(rule.get("equals"), fields.get(name), name);
        else {
            require(rule.has("in") && rule.get("in").isArray() && !rule.get("in").isEmpty(), "unknown operator or empty in");
            rule.get("in").forEach(value -> checkValue(value, fields.get(name), name));
        }
    }

    public static void checkValue(JsonNode value, JsonNode schema, String name) {
        if (value.isNull()) { require(schema.path("nullable").asBoolean(false), name + " cannot be null"); return; }
        boolean type = switch (schema.path("type").asText()) {
            case "string" -> value.isTextual();
            case "boolean" -> value.isBoolean();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "object" -> value.isObject();
            default -> false;
        };
        require(type, name + " has wrong type");
        if (schema.has("values")) {
            boolean found = false;
            for (JsonNode allowed : schema.get("values")) found |= allowed.equals(value);
            require(found, name + " has unknown value: " + value);
        }
    }

    public static boolean matches(JsonNode rule, Map<String,Object> state) {
        if (rule.has("all")) { for (var child : rule.get("all")) if (!matches(child,state)) return false; return true; }
        if (rule.has("any")) { for (var child : rule.get("any")) if (matches(child,state)) return true; return false; }
        String field=rule.path("field").asText();
        if (!state.containsKey(field)) return false; // Missing differs from explicit null.
        JsonNode actual=JSON.valueToTree(state.get(field));
        if (rule.has("equals")) return actual.equals(rule.get("equals"));
        if (rule.has("in")) for(var value:rule.get("in")) if(actual.equals(value)) return true;
        return false;
    }

    /** Explain mismatches using the same predicates as the runtime checks. */
    public static String mismatch(JsonNode rule, Map<String,Object> state) {
        if(matches(rule,state))return "";
        if(rule.has("all")||rule.has("any")) {
            List<String> reasons=new ArrayList<>();
            for(var child:rule.get(rule.has("all")?"all":"any")) {
                String reason=mismatch(child,state);if(!reason.isEmpty())reasons.add(reason);
            }
            return String.join(rule.has("all")?"；":" 或 ",reasons);
        }
        String field=rule.path("field").asText();
        String label=Map.of("main_state","主状态","current_state","当前状态","business_state","业务状态",
            "task_state","任务状态","active_action","正在执行的动作").getOrDefault(field,field);
        String actual=state.containsKey(field)?Objects.toString(state.get(field),"无"):"缺失";
        var expected=rule.has("equals")?rule.get("equals"):rule.get("in");
        return label+"为「"+actual+"」，要求 "+expected.toString();
    }

    /** Extract a deterministic post-state for static path checks; OR/in is intentionally not guessed. */
    public static Optional<Map<String,Object>> fixedState(JsonNode rule) {
        Map<String,Object> values=new LinkedHashMap<>();
        if(!collectFixed(rule,values))return Optional.empty();
        return Optional.of(values);
    }
    private static boolean collectFixed(JsonNode rule, Map<String,Object> values) {
        if(rule.has("all")) {for(var child:rule.get("all"))if(!collectFixed(child,values))return false;return true;}
        if(!rule.has("equals"))return false;
        String field=rule.get("field").asText();Object value=JSON.convertValue(rule.get("equals"),Object.class);
        if(values.containsKey(field)&&!Objects.equals(values.get(field),value))return false;
        values.put(field,value);return true;
    }
    public static void require(boolean value,String reason) { if(!value)throw new IllegalArgumentException(reason); }
}
