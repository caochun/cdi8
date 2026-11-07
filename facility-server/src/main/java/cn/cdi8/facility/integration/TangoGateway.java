package cn.cdi8.facility.integration;

import cn.cdi8.tango.TangoAdapter;
import cn.cdi8.facility.simulation.SimulatorLauncher;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Facility-owned in-process Tango access and device observation publication. */
@Component
public class TangoGateway {
    public record Notice(JsonNode payload) {}
    private record Session(String id,TangoAdapter adapter,ObjectNode environment,SimulatorLauncher.Generation process) implements AutoCloseable {
        boolean alive(){return process==null||process.alive();}
        public void close(){adapter.close();if(process!=null)process.close();}
    }
    private final ObjectMapper json;
    private final ApplicationEventPublisher events;
    private final SimulatorLauncher launcher;
    private final String bindingsFile;
    private final boolean enabled;
    private final BlockingQueue<JsonNode> queue=new LinkedBlockingQueue<>(4096);
    private final ExecutorService reader=Executors.newSingleThreadExecutor(r->{
        Thread t=new Thread(r,"tango-observations");t.setDaemon(true);
        t.setContextClassLoader(TangoGateway.class.getClassLoader());return t;
    });
    private final Object lifecycle=new Object();
    private volatile Session current;
    private volatile boolean running=true,connected;
    private volatile String error="尚未建立 Tango 设备绑定";
    private volatile long lastEvent;
    private volatile String activated="";

    @Autowired public TangoGateway(ObjectMapper json,ApplicationEventPublisher events,SimulatorLauncher launcher,
            @Value("${control.tango-bindings:}") String bindingsFile,
            @Value("${control.events-enabled:true}") boolean enabled) {
        this.json=json;this.events=events;this.launcher=launcher;this.bindingsFile=bindingsFile;this.enabled=enabled;
        if(launcher!=null&&launcher.enabled()&&!bindingsFile.isBlank())throw new IllegalArgumentException("模拟环境与外部 Tango 绑定不能同时启用");
    }
    protected TangoGateway(ObjectMapper json,ApplicationEventPublisher events) {this(json,events,null,"",false);}
    private Session prepare(JsonNode bindings,ObjectNode environment,SimulatorLauncher.Generation process,boolean requireReady) throws Exception {
        String id=UUID.randomUUID().toString();
        var adapter=new TangoAdapter(bindings,event->enqueue(((ObjectNode)event.deepCopy()).put("generation_id",id)),requireReady);
        return new Session(id,adapter,environment,process);
    }
    private void enqueue(JsonNode event) {
        if(!running)return;
        synchronized(queue) {
            if(!queue.offer(event)) {
                queue.clear();
                queue.offer(json.createObjectNode().put("type","connection_lost").put("reason","设备观测缓冲区溢出，连续证据不可用")
                    .put("generation_id",event.path("generation_id").asText()));
                queue.offer(event);
            }
        }
    }
    public boolean accepts(Notice notice) {
        String generation=notice.payload().path("generation_id").asText();
        var session=current;return generation.isBlank()||session!=null&&session.id().equals(generation);
    }
    private void publish(JsonNode value) {
        if(!accepts(new Notice(value)))return;
        lastEvent=System.currentTimeMillis();
        if("ready".equals(value.path("type").asText())){connected=true;error="";}
        if("connection_lost".equals(value.path("type").asText())){connected=false;error=value.path("reason").asText();}
        events.publishEvent(new Notice(value));
    }
    private void bootstrap(Session session) {
        connected=false;
        enqueue(json.createObjectNode().put("type","connection_lost").put("reason","重新建立设备观测")
            .put("generation_id",session.id()));
        session.adapter().activate();
        enqueue(json.createObjectNode().put("type","ready").put("generation_id",session.id()));
        activated=session.id();
    }
    @EventListener(ApplicationReadyEvent.class) public void start() {
        if(!enabled)return;
        try {
            JsonNode bindings=bindingsFile.isBlank()?json.createObjectNode():json.readTree(Path.of(bindingsFile).toFile());
            ObjectNode environment=json.createObjectNode().put("id",UUID.randomUUID().toString()).put("runtime","java")
                .put("managed",launcher.enabled()).put("status",bindings.isEmpty()?"empty":"ready");
            var devices=environment.putArray("devices");
            current=prepare(bindings,environment,null,false);
            for(var device:current.adapter().catalog())devices.addObject().put("id",device.path("id").asText())
                .put("deviceBindingKey",device.path("deviceBindingKey").asText()).put("address",device.path("address").asText());
            bootstrap(current);
        }catch(Exception ex){throw new IllegalStateException("无法建立 Tango 设备绑定",ex);}
        reader.submit(()->{
            while(running) {
                try {JsonNode event=queue.poll(1,TimeUnit.SECONDS);if(event!=null)publish(event);}
                catch(InterruptedException ex){Thread.currentThread().interrupt();break;}
                catch(Exception ex){connected=false;error="设备观测处理失败："+ex.getMessage();}
            }
        });
    }
    private Session session(){var s=current;if(s==null)throw new GatewayException(503,"Tango 接入未就绪");return s;}
    @Scheduled(fixedDelay=1000) public void reconcile() {
        var s=current;if(s==null||!running||!s.id().equals(activated))return;
        if(!s.alive()) {
            enqueue(json.createObjectNode().put("type","connection_lost").put("generation_id",s.id()).put("reason","模拟设备进程已退出"));
            return;
        }
        s.adapter().reconcile();
        enqueue(json.createObjectNode().put("type","ready").put("generation_id",s.id()));
    }
    public JsonNode request(String method,String path,Object body) {
        try {
            JsonNode value=body==null?json.nullNode():json.valueToTree(body);
            if(path.equals("/lab/validate")&&method.equals("POST"))return launcher.validate(value.path("xml").asText());
            if(path.equals("/lab/environment")) {
                if(method.equals("GET"))return environment();
                if(method.equals("POST"))return replace(value);
            }
            String[] parts=path.replaceFirst("^/","").split("/");
            if(parts[0].equals("devices")) {
                if(method.equals("GET")) {
                    if(parts.length==1)return catalog();
                    if(parts.length==2)return snapshot(parts[1]);
                    if(parts.length==3&&parts[2].equals("history"))return call(parts[1],"History",json.getNodeFactory().numberNode(200));
                    if(parts.length==4&&parts[2].equals("commands"))return result(parts[1],parts[3]);
                }else if(method.equals("POST")&&parts.length==3) {
                    String command=switch(parts[2]) {
                        case "commands"->"Execute";case "simulate"->"Simulate";case "events"->"ReportEvent";
                        case "conditions"->"SetConditions";default->throw new IllegalArgumentException("未知设备操作");
                    };
                    return call(parts[1],command,value);
                }
            }
            throw new IllegalArgumentException("未知设备操作："+method+" "+path);
        }catch(GatewayException ex){throw ex;}
        catch(IllegalArgumentException|IllegalStateException ex){throw ex;}
        catch(Exception ex){throw new GatewayException(503,"设备操作失败："+ex.getMessage());}
    }
    private JsonNode replace(JsonNode request) throws Exception {
        if(!bindingsFile.isBlank())throw new IllegalStateException("外部设备模式不能重建模拟环境");
        var generation=launcher.prepare(request);Session next=null;
        try {
            next=prepare(generation.bindings(),generation.description(),generation,true);
            if(!running||!generation.alive())throw new IllegalStateException("模拟设备启动期间已退出");
        }catch(Exception ex){if(next!=null)next.close();else generation.close();throw ex;}
        Session previous;
        synchronized(lifecycle) {
            if(!running){next.close();throw new IllegalStateException("设施后端正在关闭");}
            previous=current;connected=false;current=next;
            bootstrap(next);
        }
        if(previous!=null)previous.close();
        return environment();
    }
    private JsonNode environment() {
        var s=session();var value=s.environment().deepCopy();
        if(!s.alive())value.put("status","failed").put("error","模拟设备进程已退出");
        return value;
    }
    public JsonNode catalog(){return session().adapter().catalog();}
    public JsonNode snapshot(String id) {
        try{return session().adapter().snapshot(id);}
        catch(Exception ex){throw new GatewayException(503,"读取 Tango 快照失败："+ex.getMessage());}
    }
    private JsonNode call(String device,String command,JsonNode argument) {
        try{return session().adapter().call(device,command,argument);}
        catch(NoSuchElementException ex){throw new GatewayException(404,ex.getMessage());}
        catch(Exception ex){throw new GatewayException(503,"Tango 命令失败："+ex.getMessage());}
    }
    public JsonNode execute(String device,String id,String action,Object parameters) {
        return call(device,"Execute",json.valueToTree(Map.of("command_id",id,"action",action,"parameters",parameters)));
    }
    public JsonNode result(String device,String id){return call(device,"GetResult",json.getNodeFactory().textNode(id));}
    public JsonNode setConditions(String device,Map<String,Boolean> values){return call(device,"SetConditions",json.valueToTree(values));}
    public void reconnect(){var session=current;if(session!=null)session.adapter().reconcile();}
    public boolean connected(){var session=current;return connected&&session!=null&&session.alive()&&System.currentTimeMillis()-lastEvent<15000;}
    public JsonNode cached(String id){return cachedSnapshots().get(id);}
    public Map<String,JsonNode> cachedSnapshots(){var session=current;return session==null||!session.alive()?Map.of():session.adapter().snapshots();}
    public Map<String,Object> status(){return Map.of("connected",connected(),"error",error,"transport","jtango");}
    @PreDestroy public void close() {
        Session session;
        synchronized(lifecycle){running=false;connected=false;session=current;current=null;}
        reader.shutdownNow();if(session!=null)session.close();
    }
    public static class GatewayException extends RuntimeException {
        private final int status;
        public int status(){return status;}
        public GatewayException(int status,String message){super(message);this.status=status;}
    }
}
