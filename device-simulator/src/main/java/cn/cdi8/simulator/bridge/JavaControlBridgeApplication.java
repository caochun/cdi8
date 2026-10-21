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

/** Java replacement for the Python Tango HTTP/NDJSON bridge. */
public final class JavaControlBridgeApplication {
    private static final ObjectMapper JSON=new ObjectMapper();

    private static final class Binding {
        final String id,component,address;
        final DeviceProxy proxy;
        final TangoEventsAdapter events;
        final ITangoChangeListener listener;
        Binding(String id,String component,String address,DeviceProxy proxy,TangoEventsAdapter events,ITangoChangeListener listener) {
            this.id=id;this.component=component;this.address=address;this.proxy=proxy;this.events=events;this.listener=listener;
        }
    }

    private static final class Bridge implements AutoCloseable {
        final Map<String,Binding> devices=new LinkedHashMap<>();
        final Map<String,ObjectNode> latest=new ConcurrentHashMap<>();
        final Set<BlockingQueue<String>> clients=ConcurrentHashMap.newKeySet();
        final AtomicLong sequence=new AtomicLong();
        final TrialManager trials;
        volatile ObjectNode currentEnvironment;
        final ScheduledExecutorService monitor=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"java-control-bridge-reconcile");t.setDaemon(true);return t;});
        volatile boolean closed;

        Bridge(Path config) throws Exception {
            trials=new TrialManager(Path.of("data/java-workbench-trials"));
            JsonNode root=JSON.readTree(Files.readString(config));
            if(!root.isObject() || root.isEmpty()) throw new IllegalArgumentException("设备配置必须为非空 JSON 对象");
            var fields=root.fields();
            try {
                while(fields.hasNext()) {
                    var entry=fields.next();String id=entry.getKey();JsonNode value=entry.getValue();
                    String component=value.path("component").asText("");String address=value.path("address").asText("");
                    if(id.isBlank()||component.isBlank()||address.isBlank()) throw new IllegalArgumentException("设备映射缺少 id、component 或 address");
                    DeviceProxy proxy=new DeviceProxy(address);proxy.set_timeout_millis(2500);
                    TangoEventsAdapter events=null;
                    ITangoChangeListener listener=event->receive(id,event);
                    try {
                        events=new TangoEventsAdapter(proxy);
                        events.addTangoChangeListener(listener,"Snapshot",true);
                    } catch (Exception eventFailure) {
                        // Some Tango deployments expose commands/attributes but do not
                        // run the ZMQ event service. Polling below keeps the same bridge
                        // contract and automatically upgrades to events when available.
                    }
                    devices.put(id,new Binding(id,component,address,proxy,events,listener));
                }
                for(String id:devices.keySet()) publish(id,snapshot(id),"startup");
            } catch(Exception ex) { close();throw ex; }
            monitor.scheduleWithFixedDelay(this::reconcile,1,1,TimeUnit.SECONDS);
        }

        JsonNode snapshot(String id) throws Exception { return JSON.readTree(devices.get(id).proxy.read_attribute("Snapshot").extractString()); }
        private void receive(String id,TangoChangeEvent event) {
            try { if(event==null || event.getValue()==null) publish(id,snapshot(id),"event-reconcile"); else publish(id,JSON.readTree(event.getValue().extractString()),"tango_event"); }
            catch(Exception ex) { publishError(id,ex.toString()); }
        }
        private void publish(String id,JsonNode snapshot,String origin) {
            ObjectNode value=JSON.createObjectNode().put("type","snapshot").put("sequence",sequence.incrementAndGet()).put("device_id",id).put("origin",origin).put("connected",true);value.set("snapshot",snapshot);latest.put(id,value);broadcast(value);
        }
        private void publishError(String id,String error) { ObjectNode value=JSON.createObjectNode().put("type","device_error").put("sequence",sequence.incrementAndGet()).put("device_id",id).put("origin","error").put("connected",false).put("error",error);latest.put(id,value);broadcast(value); }
        private void broadcast(ObjectNode value) { String text=encode(value);for(var queue:clients)if(!queue.offer(text))clients.remove(queue); }
        private void reconcile() { for(String id:devices.keySet())try { JsonNode value=snapshot(id);JsonNode old=latest.get(id);if(old==null||!old.path("snapshot").equals(value)||!old.path("connected").asBoolean())publish(id,value,"reconcile"); }catch(Exception ex){publishError(id,ex.toString());} }
        ArrayNode catalog() { ArrayNode result=JSON.createArrayNode();for(Binding b:devices.values()){ObjectNode value=JSON.createObjectNode().put("id",b.id).put("component",b.component).put("address",b.address);ObjectNode current=latest.get(b.id);if(current!=null)value.setAll(current);else value.put("connected",false);result.add(value);}return result; }
        JsonNode call(String id,String command,JsonNode argument) throws Exception {
            Binding binding=devices.get(id);if(binding==null)throw new NoSuchElementException("unknown device: "+id);
            DeviceData input=new DeviceData();
            if("History".equals(command)) input.insert(argument.asInt());
            else if("GetResult".equals(command)) input.insert(argument.asText());
            else input.insert(JSON.writeValueAsString(argument));
            JsonNode result=JSON.readTree(binding.proxy.command_inout(command,input).extractString());
            try { publish(id,snapshot(id),"command"); } catch(Exception ignored) { }
            return result;
        }
        JsonNode environment(JsonNode payload) {
            if (payload==null || !payload.isObject() || !payload.path("devices").isArray())
                throw new IllegalArgumentException("body 必须包含 devices 数组");
            for(JsonNode row:payload.path("devices")){String id=row.path("id").asText();Binding b=devices.get(id);if(b==null||!b.component.equals(row.path("component").asText()))throw new IllegalArgumentException("外部设备未配置："+id);}
            ObjectNode value=JSON.createObjectNode().put("id","external").put("status","ready");
            value.set("devices",payload.path("devices"));
            currentEnvironment=value;
            return value;
        }
        JsonNode environmentView() {
            if (currentEnvironment!=null) return currentEnvironment;
            ObjectNode value=JSON.createObjectNode().put("id","external").put("status","ready");
            ArrayNode configured=value.putArray("devices");
            for(Binding b:devices.values()) configured.addObject().put("id",b.id).put("component",b.component).put("address",b.address);
            return value;
        }
        void stream(HttpExchange exchange) throws IOException {
            BlockingQueue<String> queue=new LinkedBlockingQueue<>(1024);clients.add(queue);exchange.getResponseHeaders().set("Content-Type","application/x-ndjson; charset=utf-8");exchange.getResponseHeaders().set("Cache-Control","no-cache");exchange.getResponseHeaders().set("Connection","close");exchange.sendResponseHeaders(200,0);
            try(OutputStream output=exchange.getResponseBody()){
                for(ObjectNode value:latest.values())writeLine(output,encode(value));writeLine(output,"{\"type\":\"ready\"}");
                while(!closed){String value=queue.poll(3,TimeUnit.SECONDS);writeLine(output,value==null?"{\"type\":\"heartbeat\"}":value);}
            }catch(Exception ignored){}finally{clients.remove(queue);}
        }
        private static void writeLine(OutputStream output,String value) throws IOException { output.write(value.getBytes(StandardCharsets.UTF_8));output.write('\n');output.flush(); }
        @Override public void close(){closed=true;monitor.shutdownNow();trials.close();for(Binding b:devices.values())try{if(b.events!=null)b.events.removeTangoChangeListener(b.listener,"Snapshot");}catch(Exception ignored){}clients.clear();}
    }

    private static final class Handler implements com.sun.net.httpserver.HttpHandler {
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
            } catch(NoSuchElementException ex){respond(exchange,404,error(ex.getMessage()));}
            catch(IllegalArgumentException ex){respond(exchange,400,error(ex.getMessage()));}
            catch(Exception ex){respond(exchange,409,error(ex.toString()));}
        }
        private static JsonNode trialOperation(Bridge bridge,String id,String operation,JsonNode body){return switch(operation){case "commands"->bridge.trials.command(id,body);case "simulate"->bridge.trials.simulate(id,body);case "events"->bridge.trials.event(id,body);default->throw new NoSuchElementException("unknown trial operation");};}
        private static JsonNode body(HttpExchange exchange) throws IOException {
            String length=exchange.getRequestHeaders().getFirst("Content-Length");
            if(length==null || Long.parseLong(length)<=0 || Long.parseLong(length)>4_000_000) throw new IllegalArgumentException("invalid body length");
            try(InputStream input=exchange.getRequestBody()) { JsonNode value=JSON.readTree(input); if(value==null || !value.isObject()) throw new IllegalArgumentException("body must be an object"); return value; }
        }
        private static String commandName(String command){return switch(command){case "commands"->"Execute";case "simulate"->"Simulate";case "complete"->"Complete";case "events"->"ReportEvent";case "conditions"->"SetConditions";default->throw new NoSuchElementException("unknown command");};}
        private static ObjectNode error(String message){return JSON.createObjectNode().put("error",message==null?"error":message);}
        private static void respond(HttpExchange exchange,int code,JsonNode value) throws IOException {byte[] bytes=encode(value).getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");exchange.sendResponseHeaders(code,bytes.length);try(OutputStream output=exchange.getResponseBody()){output.write(bytes);}}
    }

    private static String encode(JsonNode value){try{return JSON.writeValueAsString(value);}catch(Exception ex){throw new IllegalStateException(ex);}}
    public static void main(String[] args) throws Exception {
        Map<String,String> options=new HashMap<>();for(int i=0;i<args.length;i++){if((args[i].equals("--port")||args[i].equals("--devices")||args[i].equals("--ready-file"))&&i+1<args.length)options.put(args[i],args[++i]);else throw new IllegalArgumentException("未知或缺少值的参数："+args[i]);}
        int port=Integer.parseInt(options.getOrDefault("--port","8766"));if(port<0||port>65535)throw new IllegalArgumentException("无效的 HTTP 端口");Path config=Path.of(Objects.requireNonNull(options.get("--devices"),"需要 --devices"));Bridge bridge=new Bridge(config);HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",port),0);server.createContext("/",new Handler(bridge));server.setExecutor(Executors.newCachedThreadPool());server.start();
        ObjectNode ready=JSON.createObjectNode().put("url","http://127.0.0.1:"+server.getAddress().getPort()).set("devices",JSON.readTree(Files.readString(config)));String text=encode(ready);if(options.containsKey("--ready-file")){Path file=Path.of(options.get("--ready-file")).toAbsolutePath();Files.createDirectories(file.getParent());Files.writeString(file,text);}System.out.println(text);Runtime.getRuntime().addShutdownHook(new Thread(()->{server.stop(0);bridge.close();}));new CountDownLatch(1).await();
    }
}
