package cn.cdi8.simulator;

import cn.cdi8.simulator.runtime.ComponentModel;
import cn.cdi8.simulator.tango.SeedDevice;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import org.tango.server.ServerManager;

/** Standalone no-database Tango server; one seed instance per process. */
public final class SimulatorApplication {
    public static void main(String[] args) throws Exception {
        Map<String,String> options=new HashMap<>();boolean auto=false;
        Set<String> valued=Set.of("--port","--device","--db","--model","--ready-file","--completion-delay-ms");
        for (int i=0;i<args.length;i++) {
            if (args[i].equals("--auto-complete")) { auto=true;continue; }
            if (args[i].equals("--help")) {
                System.out.println("Java seed simulator: --port 10000 --device gxlf/seed/seed_01 --db data/java-device-simulator/seed_01.sqlite3 [--model file.scxml] [--ready-file file.json] [--auto-complete --completion-delay-ms 500]");
                return;
            }
            if (!valued.contains(args[i]) || i+1==args.length) throw new IllegalArgumentException("未知或缺少值的参数："+args[i]);
            options.put(args[i],args[++i]);
        }
        int port=Integer.parseInt(options.getOrDefault("--port","10000"));
        long delay=Long.parseLong(options.getOrDefault("--completion-delay-ms","500"));
        if (port<0 || port>65535 || delay<0 || delay>3_600_000) throw new IllegalArgumentException("无效的端口或反馈延迟");
        if (port==0) try (var socket=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) { port=socket.getLocalPort(); }
        String name=options.getOrDefault("--device","gxlf/seed/seed_01");
        if (!name.matches("[A-Za-z0-9_-]+/[A-Za-z0-9_-]+/[A-Za-z0-9_-]+")) throw new IllegalArgumentException("设备名需要 domain/family/member 三段");
        String xml;
        if (options.containsKey("--model")) xml=Files.readString(Path.of(options.get("--model")));
        else try (var input=SimulatorApplication.class.getResourceAsStream("/models/seed-source-state-machine.scxml")) {
            xml=new String(Objects.requireNonNull(input,"缺少种子源模型").readAllBytes(),StandardCharsets.UTF_8);
        }
        var model=new ComponentModel(xml);
        SeedDevice.configure(new SeedDevice.Settings(model,Path.of(options.getOrDefault("--db","data/java-device-simulator/seed_01.sqlite3")),auto,delay));
        System.setProperty("OAPort",Integer.toString(port));
        System.setProperty("OAIAddr","127.0.0.1");
        System.setProperty("org.tango.server.checkalarms","false");
        String instance="seed_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        var server=ServerManager.getInstance();
        server.addClass(SeedDevice.class.getCanonicalName(),SeedDevice.class);
        server.startError(new String[]{instance,"-nodb","-dlist",name},"JavaSeedSimulator");
        String ready=ComponentModel.encode(Map.of("address","tango://127.0.0.1:"+port+"/"+name+"#dbase=no",
            "device",name,"engine","apache-commons-scxml2","model",model.metadata.path("model_id").asText()));
        if (options.containsKey("--ready-file")) {
            Path file=Path.of(options.get("--ready-file")).toAbsolutePath();Files.createDirectories(file.getParent());Files.writeString(file,ready);
        }
        System.out.println(ready);
        new CountDownLatch(1).await();
    }
}
