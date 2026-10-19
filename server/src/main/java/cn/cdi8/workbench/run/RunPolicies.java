package cn.cdi8.workbench.run;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Resolves only explicitly registered scenario keys, including pinned releases. */
@Component
public class RunPolicies {
    private final List<RunPolicy> policies;

    public RunPolicies(List<RunPolicy> policies) { this.policies = List.copyOf(policies); }

    public static String originalKey(String key) {
        return key == null ? "" : key.replaceFirst("__lab_[a-f0-9]{12}$", "");
    }

    public Optional<RunPolicy> find(String key) {
        return policies.stream().filter(policy -> policy.processKeys().contains(originalKey(key))).findFirst();
    }

    public RunPolicy requireBuiltin(String key) {
        if (key == null) throw new IllegalArgumentException("未知流程");
        return policies.stream().filter(policy -> policy.processKeys().contains(key)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("未知流程"));
    }
}
