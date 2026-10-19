package cn.cdi8.workbench.run;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Rules supplied by a built-in scenario, separate from process execution. */
public interface RunPolicy {
    Set<String> processKeys();
    List<String> defaultSources(String processKey);
    Map<String, Object> variables(String processKey, List<String> sources);
    List<String> participantCollections(String processKey);
    default Map<String, Object> defaults(Map<String, Object> variables) { return Map.of(); }
}
