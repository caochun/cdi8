package cn.cdi8.workbench.application;

import cn.cdi8.workbench.environment.EnvironmentService;
import cn.cdi8.workbench.model.ModelService;
import cn.cdi8.workbench.run.RunDeploymentService;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Stable API facade; models, environments and run releases have separate owners. */
@Service
public class WorkbenchService {
    private final ModelService models;
    private final EnvironmentService environments;
    private final RunDeploymentService runs;

    public WorkbenchService(ModelService models, EnvironmentService environments, RunDeploymentService runs) {
        this.models = models;
        this.environments = environments;
        this.runs = runs;
    }

    public JsonNode validate(JsonNode input) { return models.validate(input); }
    public synchronized JsonNode save(JsonNode input) { return models.save(input); }
    public synchronized JsonNode publish(String id) { return models.publish(id); }
    public JsonNode config() { return environments.config(); }
    public synchronized JsonNode saveConfig(JsonNode input) { return environments.saveConfig(input); }
    public synchronized JsonNode apply(JsonNode config) { return environments.apply(config); }
    public synchronized Map<String,Object> start(JsonNode config) { return runs.start(config); }
}
