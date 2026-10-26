package cn.cdi8.simulator.bridge;

import cn.cdi8.simulator.SimulatorApplication;
import cn.cdi8.simulator.runtime.ComponentModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static cn.cdi8.simulator.runtime.ComponentModel.*;

/** Prepares isolated generations. Ownership transfers to the bridge only after Tango is readable. */
final class EnvironmentManager {
    private final Path directory;
    private final Path simulatorJar;

    EnvironmentManager(Path directory, Path simulatorJar) throws IOException {
        this.directory=directory.toAbsolutePath();
        this.simulatorJar=simulatorJar;
        Files.createDirectories(this.directory);
    }

    static final class Generation implements AutoCloseable {
        final Process process;
        final ObjectNode description, bindings;
        final Path directory;
        Generation(Process process, ObjectNode description, ObjectNode bindings, Path directory) {
            this.process=process;this.description=description;this.bindings=bindings;this.directory=directory;
        }
        @Override public void close() { stop(process); }
    }

    Generation prepare(JsonNode payload) throws Exception {
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
            String id=identifier(row,"id"), component=identifier(row,"component");
            if("context".equals(component)||"context_01".equals(id)||models.containsKey(id))
                throw new IllegalArgumentException("设备编号重复或使用了保留的 context 类型："+id);
            if(text(row,"revision").isBlank())throw new IllegalArgumentException("模型版本不能为空");
            delay(row);bool(row,"autoComplete",false);
            models.put(id,new ComponentModel(text(row,"xml")));
        }
        String id=UUID.randomUUID().toString().replace("-","");
        Path dir=Files.createDirectory(directory.resolve(id));
        Process process=null;
        try {
            var specs=JSON.createArrayNode();
            ObjectNode description=JSON.createObjectNode().put("id",id).put("status","ready").put("runtime","java");
            var devices=description.putArray("devices");
            var names=new LinkedHashMap<String,String>();
            for(JsonNode row:payload.path("devices")) {
                String deviceId=row.path("id").asText(),component=row.path("component").asText();
                Path deviceDir=Files.createDirectory(dir.resolve(deviceId));
                Path modelFile=deviceDir.resolve("model.scxml");Files.writeString(modelFile,models.get(deviceId).xml);
                String name="gxlf/"+component+"/"+deviceId;
                names.put(deviceId,name);
                specs.addObject().put("device",name).put("model",modelFile.toString())
                    .put("revision",row.path("revision").asText()).put("db",deviceDir.resolve("state.sqlite3").toString())
                    .put("autoComplete",bool(row,"autoComplete",false)).put("delayMs",delay(row));
                var device=devices.addObject().put("id",deviceId).put("component",component).put("revision",row.path("revision").asText());
                device.put("modelDigest",models.get(deviceId).digest);
                device.set("capabilities",TrialManager.capabilities(models.get(deviceId)));
            }
            String contextName="gxlf/context/context_01";names.put("context_01",contextName);
            specs.addObject().put("device",contextName).put("component","context").put("autoComplete",contextAuto)
                .put("delayMs",500).set("contextDefaults",conditions.deepCopy());
            Path config=dir.resolve("devices.json"),ready=dir.resolve("ready.json");
            JSON.writerWithDefaultPrettyPrinter().writeValue(config.toFile(),specs);
            JSON.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("request.json").toFile(),payload);
            List<String> command=javaCommand();
            command.addAll(List.of("--port","0","--devices",config.toString(),"--ready-file",ready.toString(),
                "--parent-pid",Long.toString(ProcessHandle.current().pid())));
            process=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(dir.resolve("device-server.log").toFile()).start();
            long deadline=System.nanoTime()+Duration.ofSeconds(35).toNanos();
            while(!Files.exists(ready)) {
                if(!process.isAlive())throw new IllegalStateException("Java 设备服务启动失败，退出码 "+process.exitValue()+"；日志："+dir.resolve("device-server.log"));
                if(System.nanoTime()>deadline)throw new IllegalStateException("Java 设备服务启动超时；日志："+dir.resolve("device-server.log"));
                Thread.sleep(50);
            }
            JsonNode started=JSON.readTree(ready.toFile());
            var addresses=new HashMap<String,String>();
            for(JsonNode device:started.path("devices"))addresses.put(device.path("device").asText(),device.path("address").asText());
            ObjectNode bindings=JSON.createObjectNode();
            for(var entry:names.entrySet()) {
                String address=addresses.get(entry.getValue());
                if(address==null||address.isBlank())throw new IllegalStateException("设备服务未提供地址："+entry.getKey());
                String component=entry.getKey().equals("context_01")?"context":entry.getValue().split("/")[1];
                bindings.putObject(entry.getKey()).put("component",component).put("address",address);
            }
            description.put("pid",process.pid());
            JSON.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("environment.json").toFile(),description);
            return new Generation(process,description,bindings,dir);
        } catch(Exception ex) {
            if(process!=null)stop(process);
            // Keep the generation's request, model snapshots and log for diagnosis.
            Files.writeString(dir.resolve("failure.txt"),ex.toString());
            throw ex;
        }
    }

    private List<String> javaCommand() {
        List<String> result=new ArrayList<>();
        result.add(Path.of(System.getProperty("java.home"),"bin","java").toString());
        Path jar=simulatorJar;
        if(jar==null) {
            String[] arguments=ProcessHandle.current().info().arguments().orElse(new String[0]);
            for(int i=0;i+1<arguments.length;i++)if(arguments[i].equals("-jar")) {jar=Path.of(arguments[i+1]);break;}
        }
        if(jar!=null)result.addAll(List.of("-jar",jar.toAbsolutePath().toString()));
        else result.addAll(List.of("-cp",System.getProperty("java.class.path"),SimulatorApplication.class.getName()));
        return result;
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
    private static void stop(Process process) {
        process.destroy();
        try {if(!process.waitFor(5,TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);}}
        catch(InterruptedException ex){process.destroyForcibly();Thread.currentThread().interrupt();}
    }
}
