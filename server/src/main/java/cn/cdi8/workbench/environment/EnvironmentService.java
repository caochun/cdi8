package cn.cdi8.workbench.environment;

import cn.cdi8.workbench.integration.tango.TangoGateway;
import cn.cdi8.workbench.model.ModelStore;
import cn.cdi8.workbench.run.WorkflowService;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.util.*;
import org.flowable.engine.RuntimeService;
import org.springframework.stereotype.Service;

/** Owns saved environment configuration and replacement of simulation instances. */
@Service
public class EnvironmentService {
    private final ModelStore store;
    private final TangoGateway tango;
    private final RuntimeService runtime;
    private final WorkflowService workflows;
    private final ObjectMapper json;

    public EnvironmentService(ModelStore store, TangoGateway tango, RuntimeService runtime,
                              WorkflowService workflows, ObjectMapper json) {
        this.store = store;
        this.tango = tango;
        this.runtime = runtime;
        this.workflows = workflows;
        this.json = json;
    }

    public JsonNode config() {
        try {Path path=store.directory().resolve("configuration.json");return Files.exists(path)?json.readTree(path.toFile()):json.createObjectNode();}
        catch(Exception ex){throw new IllegalStateException(ex);}
    }
    public synchronized JsonNode saveConfig(JsonNode input) {
        try {json.writerWithDefaultPrettyPrinter().writeValue(store.directory().resolve("configuration.json").toFile(),input);return input;}
        catch(Exception ex){throw new IllegalStateException(ex);}
    }
    public void ensureIdle(){if(runtime.createProcessInstanceQuery().count()>0)throw new IllegalStateException("请先停止正在运行的流程，再应用模拟环境");}
    public synchronized JsonNode apply(JsonNode config) {
      synchronized(workflows) {
        ensureIdle();
        ArrayNode devices=json.createArrayNode();Set<String> ids=new HashSet<>();
        for(JsonNode row:config.path("devices")) {
            String modelId=row.path("model").asText(), revision=row.path("revision").asText();
            var model=store.get(modelId);
            if(!"scxml".equals(model.path("kind").asText()))throw new IllegalArgumentException("设备必须使用 SCXML 模型");
            JsonNode version=store.revision(modelId,revision);
            if(row.path("count").asInt(1)<1)throw new IllegalArgumentException("每组至少需要一个实例");
            for(int i=1;i<=row.path("count").asInt(1);i++) {
                if(i>200)throw new IllegalArgumentException("单组实例数不能超过 200");
                String id=row.path("component").asText()+"_"+String.format("%02d",i);
                if(!ids.add(id))throw new IllegalArgumentException("组件类型重复："+row.path("component").asText());
                devices.addObject().put("id",id).put("component",row.path("component").asText())
                    .put("revision",revision).put("xml",version.path("xml").asText())
                    .put("autoComplete",row.path("autoComplete").asBoolean())
                    .put("delayMs",row.path("delayMs").asInt(500));
            }
        }
        var environment=tango.request("POST","/lab/environment",Map.of("devices",devices,"autoComplete",config.path("autoComplete").asBoolean(),"conditions",config.path("conditions").isObject()?config.path("conditions"):json.createObjectNode()));
        tango.reconnect();
        for(int attempt=0;attempt<100;attempt++) {
            if(tango.connected() && ids.stream().allMatch(id->tango.cached(id)!=null))break;
            try {Thread.sleep(100);}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IllegalStateException("环境切换被中断");}
        }
        if(!tango.connected() || ids.stream().anyMatch(id->tango.cached(id)==null))throw new IllegalStateException("模拟环境已创建，但事件重连未完成，请检查连接后重新应用");
        ObjectNode saved=config.deepCopy();saved.set("environment",environment);saved.set("appliedDevices",config.path("devices").deepCopy());saved.put("appliedAutoComplete",config.path("autoComplete").asBoolean());saved.set("appliedConditions",config.path("conditions").isObject()?config.path("conditions").deepCopy():json.createObjectNode());saveConfig(saved);return saved;
      }
    }
}
