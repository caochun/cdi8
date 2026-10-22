package cn.cdi8.simulator;

import cn.cdi8.simulator.runtime.ComponentModel;
import cn.cdi8.simulator.tango.ComponentDevice;
import cn.cdi8.simulator.bridge.JavaControlBridgeApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import org.tango.server.ServerManager;

/** Standalone no-database Tango server; one or more component instances per process. */
public final class SimulatorApplication {
    private record Spec(String name, ComponentModel model, Path database, boolean autoComplete, long delayMs,
                        boolean context, Map<String,Boolean> contextDefaults, String revision) { }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "--bridge".equals(args[0])) {
            JavaControlBridgeApplication.main(Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        Map<String,String> options=new HashMap<>();boolean auto=false;
        Set<String> valued=Set.of("--port","--device","--db","--model","--devices","--ready-file","--completion-delay-ms","--parent-pid");
        for (int i=0;i<args.length;i++) {
            if (args[i].equals("--auto-complete")) { auto=true;continue; }
            if (args[i].equals("--help")) {
                System.out.println("Java component simulator: --port 10000 --device gxlf/seed/seed_01 --db data/java-device-simulator/seed_01.sqlite3 [--model file.scxml] [--devices devices.json] [--ready-file file.json] [--auto-complete --completion-delay-ms 500]; managed bridge: --bridge --port 8766 [--data-dir data/java-simulator]; external bridge: --bridge --devices bindings.json");
                return;
            }
            if (!valued.contains(args[i]) || i+1==args.length) throw new IllegalArgumentException("未知或缺少值的参数："+args[i]);
            options.put(args[i],args[++i]);
        }
        int port=Integer.parseInt(options.getOrDefault("--port","10000"));
        long delay=Long.parseLong(options.getOrDefault("--completion-delay-ms","500"));
        if (port<0 || port>65535 || delay<0 || delay>3_600_000) throw new IllegalArgumentException("无效的端口或反馈延迟");
        if (port==0) try (var socket=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) { port=socket.getLocalPort(); }
        if (options.containsKey("--devices") && (options.containsKey("--device") || options.containsKey("--model") || options.containsKey("--db")))
            throw new IllegalArgumentException("--devices 不能与 --device、--model 或 --db 同时使用");
        List<Spec> specs=options.containsKey("--devices") ? readSpecs(Path.of(options.get("--devices")),auto,delay) : List.of(singleSpec(options,auto,delay));
        if (specs.isEmpty()) throw new IllegalArgumentException("至少需要一个设备配置");
        if(options.containsKey("--parent-pid")) {
            ProcessHandle parent=ProcessHandle.of(Long.parseLong(options.get("--parent-pid"))).orElseThrow();
            Thread watcher=new Thread(()->{
                while(parent.isAlive())try{Thread.sleep(1000);}catch(InterruptedException ex){return;}
                System.exit(0);
            },"bridge-parent-watch");watcher.setDaemon(true);watcher.start();
        }

        ComponentDevice.clearConfiguration();
        var names=new String[specs.size()];
        for (int i=0;i<specs.size();i++) {
            Spec spec=specs.get(i);names[i]=spec.name();
            ComponentDevice.Settings settings=spec.context()
                ? ComponentDevice.Settings.context(spec.autoComplete(),spec.delayMs(),spec.contextDefaults())
                : new ComponentDevice.Settings(spec.model(),spec.database(),spec.autoComplete(),spec.delayMs(),spec.revision());
            ComponentDevice.configure(spec.name(),settings);
        }
        System.setProperty("OAPort",Integer.toString(port));
        System.setProperty("OAIAddr","127.0.0.1");
        System.setProperty("org.tango.server.checkalarms","false");
        String instance="component_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        var server=ServerManager.getInstance();
        server.addClass(ComponentDevice.class.getCanonicalName(),ComponentDevice.class);
        String[] serverArgs=new String[3+names.length];
        serverArgs[0]=instance;serverArgs[1]="-nodb";serverArgs[2]="-dlist";
        System.arraycopy(names,0,serverArgs,3,names.length);
        server.startError(serverArgs,"JavaComponentSimulator");

        ObjectNode ready=ComponentModel.JSON.createObjectNode().put("engine","apache-commons-scxml2");
        if (specs.size()==1) {
            Spec spec=specs.get(0);
            ready.put("device",spec.name()).put("model",modelId(spec));
            ready.put("address",address(port,spec.name()));
        }
        ArrayNode devices=ready.putArray("devices");
        for (Spec spec:specs) devices.addObject().put("device",spec.name()).put("model",modelId(spec)).put("address",address(port,spec.name()));
        String readyText=ComponentModel.encode(ready);
        if (options.containsKey("--ready-file")) {
            Path file=Path.of(options.get("--ready-file")).toAbsolutePath();Files.createDirectories(file.getParent());
            Path temporary=Files.createTempFile(file.getParent(),"device-ready-",".tmp");
            Files.writeString(temporary,readyText);Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING);
        }
        System.out.println(readyText);
        new CountDownLatch(1).await();
    }

    private static String address(int port,String name) { return "tango://127.0.0.1:"+port+"/"+name+"#dbase=no"; }

    private static Spec singleSpec(Map<String,String> options,boolean auto,long delay) throws Exception {
        String name=options.getOrDefault("--device","gxlf/seed/seed_01");validateName(name);
        String xml=options.containsKey("--model")?readText(Path.of(options.get("--model"))):resource("seed-source-state-machine.scxml");
        ComponentModel model=new ComponentModel(xml);
        Path db=Path.of(options.getOrDefault("--db","data/java-device-simulator/seed_01.sqlite3"));
        return new Spec(name,model,db,auto,delay,false,Map.of(),null);
    }

    private static List<Spec> readSpecs(Path file,boolean auto,long defaultDelay) throws Exception {
        JsonNode root=ComponentModel.JSON.readTree(readText(file));
        if (!root.isArray()) throw new IllegalArgumentException("--devices 必须是 JSON 数组");
        List<Spec> result=new ArrayList<>();Set<String> names=new HashSet<>();
        for (JsonNode value:root) {
            String name=required(value,"device");validateName(name);if(!names.add(name))throw new IllegalArgumentException("设备名重复："+name);
            boolean context="context".equals(value.path("component").asText())||"context".equals(value.path("kind").asText());
            ComponentModel model=null;Path db=null;Map<String,Boolean> defaults=Map.of();
            if (!context) {
                String modelFile=required(value,"model");
                String xml=Files.exists(Path.of(modelFile))?readText(Path.of(modelFile)):resource(modelFile);
                model=new ComponentModel(xml);
                db=Path.of(value.path("db").asText("data/java-device-simulator/"+name.replace('/','_')+".sqlite3"));
            } else {
                JsonNode configured=value.path("contextDefaults");if(!configured.isMissingNode()&&!configured.isObject())throw new IllegalArgumentException("contextDefaults 必须为对象");
                if(configured.isObject()) {Map<String,Boolean> parsed=new LinkedHashMap<>();configured.fields().forEachRemaining(entry->{if(!entry.getValue().isBoolean())throw new IllegalArgumentException("contextDefaults 必须为布尔值");parsed.put(entry.getKey(),entry.getValue().asBoolean());});defaults=parsed;}
            }
            boolean autoComplete=value.has("autoComplete")?value.path("autoComplete").asBoolean():auto;
            long delay=value.has("delayMs")?value.path("delayMs").asLong():defaultDelay;
            if(delay<0 || delay>3_600_000)throw new IllegalArgumentException("反馈延迟无效："+name);
            result.add(new Spec(name,model,db,autoComplete,delay,context,defaults,value.path("revision").isTextual()?value.path("revision").asText():null));
        }
        return result;
    }

    private static String required(JsonNode value,String key) { String text=value.path(key).asText("");if(text.isBlank())throw new IllegalArgumentException("设备配置缺少 "+key);return text; }
    private static String modelId(Spec spec) { return spec.context()?"preparation-context-demo-v1":spec.model().metadata.path("model_id").asText(); }
    private static void validateName(String name) { if(!name.matches("[A-Za-z0-9_-]+/[A-Za-z0-9_-]+/[A-Za-z0-9_-]+"))throw new IllegalArgumentException("设备名需要 domain/family/member 三段："+name); }
    private static String readText(Path path) throws Exception { return Files.readString(path); }
    private static String resource(String name) throws Exception { try(var input=SimulatorApplication.class.getResourceAsStream("/models/"+name)){return new String(Objects.requireNonNull(input,"缺少组件模型："+name).readAllBytes(),StandardCharsets.UTF_8);} }
}
