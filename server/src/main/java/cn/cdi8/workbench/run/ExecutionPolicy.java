package cn.cdi8.workbench.run;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.flowable.engine.delegate.DelegateExecution;

/** Optional scenario checks; implementations decide which definitions they cover. */
public interface ExecutionPolicy {
    default Map<String, Object> conditionSatisfied(String key, Map<String, Object> variables, String condition) {
        return Map.of();
    }
    default String issue(String key, Map<String, Object> variables) { return null; }
    default void verifyCommand(DelegateExecution execution, JsonNode snapshot) { }
}
