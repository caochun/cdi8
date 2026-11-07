package cn.cdi8.simulator.environment;

import cn.cdi8.simulator.runtime.ComponentModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import static cn.cdi8.simulator.runtime.ComponentModel.*;

/** Materializes immutable device configuration inside the device JVM. */
public final class EnvironmentConfiguration {
    public record Prepared(Path devices,ObjectNode description) {}
    public static Prepared prepare(JsonNode payload,Path dir) throws Exception {
        if(payload==null || !payload.isObject() || !payload.path("devices").isArray()
                || payload.path("devices").size()>200)
            throw new IllegalArgumentException("devices 必须为数组，组件实例总数不能超过 200");
        boolean contextAuto=bool(payload,"autoComplete",false);
        JsonNode conditions=payload.has("conditions")?payload.get("conditions"):JSON.createObjectNode();
        if(!conditions.isObject())throw new IllegalArgumentException("外部条件必须是命名布尔值");
        conditions.fields().forEachRemaining(entry->{
            if(entry.getKey().isBlank()||!entry.getValue().isBoolean())throw new IllegalArgumentException("外部条件必须是命名布尔值");
        });
        // Validate every selected model before launching or touching the live environment.
        var models=new LinkedHashMap<String,ComponentModel>();
        for(JsonNode row:payload.path("devices")) {
            String id=identifier(row,"id"), deviceBindingKey=identifier(row,"deviceBindingKey");
            if("context".equals(deviceBindingKey)||"context_01".equals(id)||models.containsKey(id))
                throw new IllegalArgumentException("设备编号重复或使用了保留的 context 绑定键："+id);
            if(text(row,"revision").isBlank())throw new IllegalArgumentException("模型版本不能为空");
            delay(row);bool(row,"autoComplete",false);
            models.put(id,new ComponentModel(text(row,"xml")));
        }
        String id=dir.getFileName().toString();
        var specs=JSON.createArrayNode();
        ObjectNode description=JSON.createObjectNode().put("id",id).put("status","ready").put("runtime","java");
        var devices=description.putArray("devices");
        for(JsonNode row:payload.path("devices")) {
            String deviceId=row.path("id").asText(),deviceBindingKey=row.path("deviceBindingKey").asText();
            Path deviceDir=Files.createDirectory(dir.resolve(deviceId));
            Path modelFile=deviceDir.resolve("model.scxml");Files.writeString(modelFile,models.get(deviceId).xml);
            String name="gxlf/"+deviceBindingKey+"/"+deviceId;
            specs.addObject().put("device",name).put("model",modelFile.toString())
                .put("revision",row.path("revision").asText()).put("db",deviceDir.resolve("state.sqlite3").toString())
                .put("autoComplete",bool(row,"autoComplete",false)).put("delayMs",delay(row));
            var device=devices.addObject().put("id",deviceId).put("deviceBindingKey",deviceBindingKey).put("revision",row.path("revision").asText());
            device.put("modelDigest",models.get(deviceId).digest);
            device.set("capabilities",ComponentModelSupport.capabilities(models.get(deviceId)));
        }
        String contextName="gxlf/context/context_01";
        specs.addObject().put("device",contextName).put("kind","context").put("autoComplete",contextAuto)
            .put("delayMs",500).set("contextDefaults",conditions.deepCopy());
        Path config=dir.resolve("devices.json");
        JSON.writerWithDefaultPrettyPrinter().writeValue(config.toFile(),specs);
        JSON.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("request.json").toFile(),payload);
        description.put("managed",true).put("pid",ProcessHandle.current().pid());
        return new Prepared(config,description);
    }

    private static long delay(JsonNode row) {
        if(!row.has("delayMs"))return 500;
        JsonNode value=row.get("delayMs");
        if(!value.isIntegralNumber()||!value.canConvertToLong()||value.asLong()<0||value.asLong()>3_600_000)
            throw new IllegalArgumentException("反馈延迟必须在 0–3600000 毫秒之间");
        return value.asLong();
    }
    private static boolean bool(JsonNode row,String key,boolean fallback) {
        if(!row.has(key))return fallback;
        if(!row.get(key).isBoolean())throw new IllegalArgumentException(key+" 必须为布尔值");
        return row.get(key).asBoolean();
    }
}
