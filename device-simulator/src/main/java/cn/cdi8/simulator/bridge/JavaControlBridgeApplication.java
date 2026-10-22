package cn.cdi8.simulator.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import fr.esrf.TangoApi.DeviceData;
import fr.esrf.TangoApi.DeviceProxy;
import fr.esrf.TangoApi.events.ITangoChangeListener;
import fr.esrf.TangoApi.events.TangoChangeEvent;
import fr.esrf.TangoApi.events.TangoEventsAdapter;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** HTTP/NDJSON bridge over real JTango devices, with managed Java simulation generations. */
public final class JavaControlBridgeApplication {
    private static final ObjectMapper JSON=new ObjectMapper();
    private record Binding(String id,String component,String address,DeviceProxy proxy,
                           TangoEventsAdapter events,ITangoChangeListener listener) { }

    /** A generation owns its subscriptions and streams; late old events cannot leak into a new stream. */
    private static final class Session implements AutoCloseable {
        final Map<String,Binding> devices=new LinkedHashMap<>();
        final Map<String,ObjectNode> latest=new ConcurrentHashMap<>();
        final Set<BlockingQueue<String>> clients=ConcurrentHashMap.newKeySet();
        final AtomicLong sequence;
        final ObjectNode environment;
        final EnvironmentManager.Generation generation;
        volatile boolean closed;

        Session(JsonNode config,ObjectNode environment,EnvironmentManager.Generation generation,AtomicLong sequence) throws Exception {
            this.environment=environment;this.generation=generation;this.sequence=sequence;
            try {
                var fields=config.fields();
                while(fields.hasNext()) {
                    var entry=fields.next();String id=entry.getKey();JsonNode value=entry.getValue();
                    String component=value.path("component").asText(""),address=value.path("address").asText("");
                    if(id.isBlank()||component.isBlank()||address.isBlank())throw new IllegalArgumentException("设备映射缺少 id、component 或 address");
                    DeviceProxy proxy=new DeviceProxy(address);proxy.set_timeout_millis(2500);
                    proxy.set_source(fr.esrf.Tango.DevSource.DEV);
                    // A readable snapshot, rather than a ready file alone, makes a generation ready.
                    JsonNode snapshot=JSON.readTree(proxy.read_attribute("Snapshot").extractString());
                    TangoEventsAdapter events=null;
                    ITangoChangeListener listener=event->receive(id,proxy,event);
                    try {
                        events=new TangoEventsAdapter(proxy);
                        events.addTangoChangeListener(listener,"Snapshot",true);
                    } catch(Exception ignored) { /* Polling reconciles deployments without a ZMQ event service. */ }
                    devices.put(id,new Binding(id,component,address,proxy,events,listener));
                    publish(id,snapshot,"startup");
                }
            } catch(Exception ex) {close();throw ex;}
        }
        JsonNode snapshot(String id) throws Exception {
            Binding binding=binding(id);
            return JSON.readTree(binding.proxy.read_attribute("Snapshot").extractString());
        }
        Binding binding(String id) {
            Binding binding=devices.get(id);
            if(binding==null)throw new NoSuchElementException("unknown device: "+id);
            return binding;
        }
        private void receive(String id,DeviceProxy proxy,TangoChangeEvent event) {
            if(closed)return;
            try {
                JsonNode value=JSON.readTree(event==null||event.getValue()==null
                    ?proxy.read_attribute("Snapshot").extractString():event.getValue().extractString());
                publish(id,value,"tango_event");
            } catch(Exception ex) {publishError(id,ex.toString());}
        }
        private void publish(String id,JsonNode snapshot,String origin) {
            if(closed)return;
            ObjectNode value=JSON.createObjectNode().put("type","snapshot").put("sequence",sequence.incrementAndGet())
                .put("device_id",id).put("origin",origin).put("connected",true);
            value.set("snapshot",snapshot);latest.put(id,value);broadcast(value);
        }
        private void publishError(String id,String error) {
            if(closed)return;
            ObjectNode value=JSON.createObjectNode().put("type","device_error").put("sequence",sequence.incrementAndGet())
                .put("device_id",id).put("origin","error").put("connected",false).put("error",error);
            latest.put(id,value);broadcast(value);
        }
        private void broadcast(ObjectNode value) {
            String text=encode(value);
            for(var queue:clients)if(!queue.offer(text)){queue.clear();queue.offer("");clients.remove(queue);}
        }
        void reconcile() {
            if(generation!=null&&!generation.process.isAlive()) {
                for(String id:devices.keySet())if(latest.get(id)==null||latest.get(id).path("connected").asBoolean())
                    publishError(id,"Java 设备进程已退出，请重新创建模拟设备");
                return;
            }
            for(String id:devices.keySet()) {
                if(closed)return;
                try {
                    JsonNode value=snapshot(id),old=latest.get(id);
                    if(old==null||!old.path("snapshot").equals(value)||!old.path("connected").asBoolean())publish(id,value,"reconcile");
                } catch(Exception ex){publishError(id,ex.toString());}
            }
        }
        ArrayNode catalog() {
            ArrayNode result=JSON.createArrayNode();
            for(Binding b:devices.values()) {
                ObjectNode value=JSON.createObjectNode().put("id",b.id).put("component",b.component).put("address",b.address);
                ObjectNode current=latest.get(b.id);if(current!=null)value.setAll(current);else value.put("connected",false);
                result.add(value);
            }
            return result;
        }
        JsonNode call(String id,String command,JsonNode argument) throws Exception {
            Binding binding=binding(id);DeviceData input=new DeviceData();
            if("History".equals(command))input.insert(argument.asInt());
            else if("GetResult".equals(command))input.insert(argument.asText());
            else input.insert(JSON.writeValueAsString(argument));
            JsonNode result=JSON.readTree(binding.proxy.command_inout(command,input).extractString());
            try {publish(id,snapshot(id),"command");}catch(Exception ignored) { }
            return result;
        }
        void stream(HttpExchange exchange) throws IOException {
            BlockingQueue<String> queue=new LinkedBlockingQueue<>(1024);
            synchronized(this){if(!closed)clients.add(queue);}
            exchange.getResponseHeaders().set("Content-Type","application/x-ndjson; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control","no-cache");
            exchange.getResponseHeaders().set("Connection","close");exchange.sendResponseHeaders(200,0);
            try(OutputStream output=exchange.getResponseBody()) {
                for(ObjectNode value:latest.values())writeLine(output,encode(value));
                writeLine(output,"{\"type\":\"ready\"}");
                while(!closed) {
                    String value=queue.poll(3,TimeUnit.SECONDS);
                    if("".equals(value))break;
                    writeLine(output,value==null?"{\"type\":\"heartbeat\"}":value);
                }
            }catch(Exception ignored) {}finally{clients.remove(queue);}
        }
        private static void writeLine(OutputStream output,String value) throws IOException {
            output.write(value.getBytes(StandardCharsets.UTF_8));output.write('\n');output.flush();
        }
        @Override public void close() {
            synchronized(this){closed=true;for(var queue:clients){queue.clear();queue.offer("");}clients.clear();}
            for(Binding b:devices.values())try{if(b.events!=null)b.events.removeTangoChangeListener(b.listener,"Snapshot");}catch(Exception ignored) { }
            if(generation!=null)generation.close();
        }
    }

    static final class Bridge implements AutoCloseable {
        final TrialManager trials;
        private final EnvironmentManager environments;
        private final Object replacementLock=new Object();
        private final AtomicLong sequence=new AtomicLong();
        private final ScheduledExecutorService monitor=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"java-control-bridge-reconcile");t.setDaemon(true);return t;});
        private volatile Session current;
        private volatile boolean closed;

        Bridge(Path config,Path dataDirectory,Path simulatorJar) throws Exception {
            trials=new TrialManager(dataDirectory.resolve("trials"));
            environments=config==null?new EnvironmentManager(dataDirectory.resolve("environments"),simulatorJar):null;
            try {
                JsonNode bindings=config==null?JSON.createObjectNode():JSON.readTree(Files.readString(config));
                if(!bindings.isObject()||(config!=null&&bindings.isEmpty()))throw new IllegalArgumentException("设备配置必须为非空 JSON 对象");
                ObjectNode environment=JSON.createObjectNode().put("id",UUID.randomUUID().toString().replace("-",""))
                    .put("status",config==null?"empty":"ready").put("runtime","java").put("managed",config==null);
                var devices=environment.putArray("devices");
                bindings.fields().forEachRemaining(e->devices.addObject().put("id",e.getKey()).setAll((ObjectNode)e.getValue()));
                current=new Session(bindings,environment,null,sequence);
                monitor.scheduleWithFixedDelay(()->current.reconcile(),1,1,TimeUnit.SECONDS);
            }catch(Exception ex){close();throw ex;}
        }
        JsonNode snapshot(String id) throws Exception {return current.snapshot(id);}
        ArrayNode catalog() {return current.catalog();}
        synchronized JsonNode call(String id,String command,JsonNode argument) throws Exception {
            if(closed)throw new IllegalStateException("bridge 已关闭");
            return current.call(id,command,argument);
        }
        JsonNode environment(JsonNode payload) throws Exception {
            if(environments==null)throw new IllegalStateException("外部设备模式不支持重建模拟环境；请不带 --devices 启动 Java bridge");
            synchronized(replacementLock) {
                synchronized(this){ensureOpen();}
                EnvironmentManager.Generation generation=environments.prepare(payload);
                Session next=null;
                try {
                    generation.description.put("managed",true);
                    next=new Session(generation.bindings,generation.description,generation,sequence);
                    Session previous;
                    synchronized(this) {
                        ensureOpen();
                        if(!generation.process.isAlive())throw new IllegalStateException("新建的 Java 设备进程已退出");
                        previous=current;current=next;
                    }
                    previous.close();
                    return environmentView();
                }catch(Exception ex){if(next!=null)next.close();else generation.close();throw ex;}
            }
        }
        private void ensureOpen() {
            if(closed)throw new IllegalStateException("bridge 已关闭");
        }
        JsonNode environmentView() {
            Session session=current;ObjectNode value=session.environment.deepCopy();
            if(session.generation!=null&&!session.generation.process.isAlive())
                value.put("status","failed").put("error","Java 设备进程已退出，请重新创建模拟设备");
            return value;
        }
        void stream(HttpExchange exchange) throws IOException {current.stream(exchange);}
        @Override public synchronized void close() {
            closed=true;monitor.shutdownNow();trials.close();if(current!=null)current.close();
        }
    }

    static final class Handler implements com.sun.net.httpserver.HttpHandler {
        final Bridge bridge;Handler(Bridge bridge){this.bridge=bridge;}
        @Override public void handle(HttpExchange exchange) throws IOException {
            String path=exchange.getRequestURI().getPath();
            try {
                if("GET".equals(exchange.getRequestMethod())&&"/events".equals(path)){bridge.stream(exchange);return;}
                String[] parts=path.replaceFirst("^/","").split("/");JsonNode value;
                if("GET".equals(exchange.getRequestMethod())&&parts.length==2&&parts[0].equals("lab")&&parts[1].equals("environment"))value=bridge.environmentView();
                else if("GET".equals(exchange.getRequestMethod())&&parts.length==3&&parts[0].equals("lab")&&parts[1].equals("trials"))value=bridge.trials.view(parts[2]);
                else if("POST".equals(exchange.getRequestMethod())&&parts.length==2&&parts[0].equals("lab")&&parts[1].equals("validate"))value=bridge.trials.validate(body(exchange).path("xml").asText());
                else if("POST".equals(exchange.getRequestMethod())&&parts.length==2&&parts[0].equals("lab")&&parts[1].equals("trials"))value=bridge.trials.create(body(exchange).path("xml").asText());
                else if("POST".equals(exchange.getRequestMethod())&&parts.length==4&&parts[0].equals("lab")&&parts[1].equals("trials"))value=trialOperation(bridge,parts[2],parts[3],body(exchange));
                else if("GET".equals(exchange.getRequestMethod())&&parts.length==1&&parts[0].equals("devices"))value=bridge.catalog();
                else if("GET".equals(exchange.getRequestMethod())&&parts.length==2&&parts[0].equals("devices"))value=bridge.snapshot(parts[1]);
                else if("GET".equals(exchange.getRequestMethod())&&parts.length==3&&parts[0].equals("devices")&&parts[2].equals("history"))value=bridge.call(parts[1],"History",JSON.getNodeFactory().numberNode(200));
                else if("GET".equals(exchange.getRequestMethod())&&parts.length==4&&parts[0].equals("devices")&&parts[2].equals("commands"))value=bridge.call(parts[1],"GetResult",JSON.getNodeFactory().textNode(URLDecoder.decode(parts[3],StandardCharsets.UTF_8)));
                else if("POST".equals(exchange.getRequestMethod())&&parts.length==2&&parts[0].equals("lab")&&parts[1].equals("environment"))value=bridge.environment(body(exchange));
                else if("POST".equals(exchange.getRequestMethod())&&parts.length==3&&parts[0].equals("devices"))value=bridge.call(parts[1],commandName(parts[2]),body(exchange));
                else throw new NoSuchElementException("unknown endpoint");
                respond(exchange,200,value);
            }catch(NoSuchElementException ex){respond(exchange,404,error(ex.getMessage()));}
            catch(IllegalArgumentException ex){respond(exchange,400,error(ex.getMessage()));}
            catch(Exception ex){respond(exchange,409,error(ex.toString()));}
        }
        private static JsonNode trialOperation(Bridge bridge,String id,String operation,JsonNode body){return switch(operation){case "commands"->bridge.trials.command(id,body);case "simulate"->bridge.trials.simulate(id,body);case "events"->bridge.trials.event(id,body);default->throw new NoSuchElementException("unknown trial operation");};}
        private static JsonNode body(HttpExchange exchange) throws IOException {
            // Bound the actual bytes as well as accepting normal/chunked HTTP clients.
            byte[] bytes=exchange.getRequestBody().readNBytes(32_000_001);
            if(bytes.length==0||bytes.length>32_000_000)throw new IllegalArgumentException("请求为空或超过 32 MB");
            JsonNode value=JSON.readTree(bytes);
            if(value==null||!value.isObject())throw new IllegalArgumentException("body must be an object");return value;
        }
        private static String commandName(String command){return switch(command){case "commands"->"Execute";case "simulate"->"Simulate";case "complete"->"Complete";case "events"->"ReportEvent";case "conditions"->"SetConditions";default->throw new NoSuchElementException("unknown command");};}
        private static ObjectNode error(String message){return JSON.createObjectNode().put("error",message==null?"error":message);}
        private static void respond(HttpExchange exchange,int code,JsonNode value) throws IOException {byte[] bytes=encode(value).getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");exchange.sendResponseHeaders(code,bytes.length);try(OutputStream output=exchange.getResponseBody()){output.write(bytes);}}
    }

    private static String encode(JsonNode value){try{return JSON.writeValueAsString(value);}catch(Exception ex){throw new IllegalStateException(ex);}}
    public static void main(String[] args) throws Exception {
        Map<String,String> options=new HashMap<>();
        Set<String> valued=Set.of("--port","--devices","--ready-file","--data-dir","--simulator-jar");
        for(int i=0;i<args.length;i++) {
            if(args[i].equals("--help")){System.out.println("Java bridge: --port 8766 --data-dir data/java-simulator [--ready-file file.json] [--devices external-bindings.json] [--simulator-jar simulator.jar]");return;}
            if(valued.contains(args[i])&&i+1<args.length)options.put(args[i],args[++i]);
            else throw new IllegalArgumentException("未知或缺少值的参数："+args[i]);
        }
        int port=Integer.parseInt(options.getOrDefault("--port","8766"));
        if(port<0||port>65535)throw new IllegalArgumentException("无效的 HTTP 端口");
        Path config=options.containsKey("--devices")?Path.of(options.get("--devices")):null;
        Path jar=options.containsKey("--simulator-jar")?Path.of(options.get("--simulator-jar")):null;
        Bridge bridge=new Bridge(config,Path.of(options.getOrDefault("--data-dir","data/java-simulator")),jar);
        HttpServer server;
        // HTTP dispatcher threads may use the system loader, which cannot see
        // JacORB inside the executable jar. Keep Tango's reflective loads on the app loader.
        ExecutorService workers=Executors.newCachedThreadPool(task->{
            Thread worker=Executors.defaultThreadFactory().newThread(task);
            worker.setContextClassLoader(JavaControlBridgeApplication.class.getClassLoader());
            return worker;
        });
        try {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",port),0);
            server.createContext("/",new Handler(bridge));server.setExecutor(workers);server.start();
        }catch(Exception ex){workers.shutdownNow();bridge.close();throw ex;}
        Runtime.getRuntime().addShutdownHook(new Thread(()->{server.stop(0);bridge.close();workers.shutdownNow();},"java-bridge-shutdown"));
        ObjectNode ready=JSON.createObjectNode().put("url","http://127.0.0.1:"+server.getAddress().getPort()).put("runtime","java");
        ready.set("environment",bridge.environmentView());
        if(options.containsKey("--ready-file")) {
            Path file=Path.of(options.get("--ready-file")).toAbsolutePath();Files.createDirectories(file.getParent());
            Path temporary=Files.createTempFile(file.getParent(),"bridge-ready-",".tmp");
            Files.writeString(temporary,encode(ready));Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING);
        }
        System.out.println(encode(ready));new CountDownLatch(1).await();
    }
}
