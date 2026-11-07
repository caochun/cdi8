package cn.cdi8.facility.simulation;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Launches device JVMs; SCXML parsing and execution stay in the simulator. */
@Component
public class SimulatorLauncher {
    private final ObjectMapper json;
    private final Path jar,directory;
    private final boolean enabled;
    public SimulatorLauncher(ObjectMapper json,
            @Value("${control.simulation.jar:device-simulator/target/device-simulator-0.1.0.jar}") String jar,
            @Value("${control.simulation.directory:data/java-simulator}") String directory,
            @Value("${control.simulation.enabled:false}") boolean enabled) {
        this.json=json;this.jar=Path.of(jar).toAbsolutePath();this.directory=Path.of(directory).toAbsolutePath();this.enabled=enabled;
    }
    public boolean enabled(){return enabled;}
    public record Generation(Process process,ObjectNode description,ObjectNode bindings) implements AutoCloseable {
        public boolean alive(){return process.isAlive();}
        @Override public void close(){stop(process);}
    }
    private List<String> command(String...args) {
        if(!Files.isRegularFile(jar))throw new IllegalStateException("缺少模拟器 JAR："+jar);
        var result=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-jar",jar.toString()));
        result.addAll(List.of(args));return result;
    }
    public JsonNode validate(String xml) throws Exception {
        // A short-lived CLI uses exactly the engine that will run the devices.
        Path dir=Files.createTempDirectory("cdi8-scxml-validation-");
        Process process=null;
        try {
            Path model=dir.resolve("model.scxml"),result=dir.resolve("result.json");
            Files.writeString(model,xml);
            process=new ProcessBuilder(command("--validate",model.toString(),"--result",result.toString()))
                .redirectErrorStream(true).redirectOutput(dir.resolve("validation.log").toFile()).start();
            if(!process.waitFor(20,TimeUnit.SECONDS))throw new IllegalStateException("SCXML 校验超时");
            if(process.exitValue()!=0||!Files.isRegularFile(result))throw new IllegalStateException("SCXML 校验进程失败："+Files.readString(dir.resolve("validation.log")));
            return json.readTree(result.toFile());
        }finally{
            if(process!=null&&process.isAlive())stop(process);
            try(var files=Files.walk(dir)){for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}
        }
    }
    public Generation prepare(JsonNode request) throws Exception {
        if(!enabled)throw new IllegalStateException("未启用模拟设备启动；外部设备模式不支持重建");
        Files.createDirectories(directory.resolve("environments"));
        Path dir=Files.createDirectory(directory.resolve("environments").resolve(UUID.randomUUID().toString().replace("-","")));
        Path input=dir.resolve("request.json"),ready=dir.resolve("ready.json");
        json.writerWithDefaultPrettyPrinter().writeValue(input.toFile(),request);
        Process process=null;
        try {
            process=new ProcessBuilder(command("--environment",input.toString(),"--port","0","--ready-file",ready.toString(),
                "--parent-pid",Long.toString(ProcessHandle.current().pid())))
                .redirectErrorStream(true).redirectOutput(dir.resolve("device-server.log").toFile()).start();
            long deadline=System.nanoTime()+Duration.ofSeconds(35).toNanos();
            while(!Files.exists(ready)) {
                if(!process.isAlive()||System.nanoTime()>deadline)throw new IllegalStateException("模拟设备启动失败或超时；日志："+dir.resolve("device-server.log"));
                Thread.sleep(50);
            }
            var result=json.readTree(ready.toFile());
            if(!result.path("bindings").isObject()||!result.path("environment").isObject())throw new IllegalStateException("模拟设备就绪描述无效");
            return new Generation(process,(ObjectNode)result.path("environment"),(ObjectNode)result.path("bindings"));
        }catch(Exception ex){
            if(process!=null)stop(process);
            Files.writeString(dir.resolve("failure.txt"),ex.toString());throw ex;
        }
    }
    private static void stop(Process process) {
        process.destroy();
        try {if(!process.waitFor(5,TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);}}
        catch(InterruptedException ex){process.destroyForcibly();Thread.currentThread().interrupt();}
    }
}
