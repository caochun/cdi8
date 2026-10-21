package cn.cdi8.simulator.runtime;

import java.nio.file.Path;

/**
 * @deprecated Use {@link ComponentRuntime}. Kept as a source-compatible alias
 * for the original Java seed simulator API.
 */
@Deprecated
public final class SeedRuntime extends ComponentRuntime {
    public SeedRuntime(ComponentModel model, Path database) {
        super(model, database);
    }
}
