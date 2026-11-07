package cn.cdi8.tango;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import fr.esrf.TangoApi.*;
import fr.esrf.TangoApi.events.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** In-process Tango connections. Owns no HTTP server, simulator or facility policy. */
public final class TangoAdapter implements AutoCloseable {
    private static final ObjectMapper JSON=new ObjectMapper();
    public interface Connection extends AutoCloseable {
        JsonNode snapshot() throws Exception;
        JsonNode call(String command,JsonNode argument) throws Exception;
        @Override void close();
    }
    @FunctionalInterface public interface Connector {
        Connection connect(String address,Consumer<JsonNode> snapshot,Consumer<Exception> failure) throws Exception;
    }
    private final class Binding {
        final String id,deviceBindingKey,address;
        volatile Connection connection;
        Binding(String id,JsonNode config) {
            this.id=id;deviceBindingKey=config.path("deviceBindingKey").asText(config.path("component").asText());address=config.path("address").asText();
            if(config.has("deviceBindingKey")&&config.has("component")&&!config.get("deviceBindingKey").equals(config.get("component")))
                throw new IllegalArgumentException("设备绑定键字段冲突：deviceBindingKey / component");
            if(!id.matches("[A-Za-z0-9_.:-]{1,128}")||deviceBindingKey.isBlank()||address.isBlank())
                throw new IllegalArgumentException("设备映射缺少有效 id、deviceBindingKey 或 address");
        }
        synchronized Connection connect() throws Exception {
            if(closed)throw new IllegalStateException("Tango 连接已关闭");
            if(connection==null)connection=connector.connect(address,
                value->publish(id,value,"tango_event"),ex->failure(id,ex));
            return connection;
        }
        synchronized void close(){if(connection!=null)connection.close();connection=null;}
    }
    private final Map<String,Binding> bindings=new LinkedHashMap<>();
    private final Map<String,ObjectNode> latest=new ConcurrentHashMap<>();
    private final Connector connector;
    private final Consumer<JsonNode> notices;
    private volatile boolean closed,active;

    public TangoAdapter(JsonNode config,Consumer<JsonNode> notices,boolean requireReady) throws Exception {
        this(config,notices,requireReady,NativeConnection::new);
    }
    public TangoAdapter(JsonNode config,Consumer<JsonNode> notices,boolean requireReady,Connector connector) throws Exception {
        this.notices=notices;this.connector=connector;
        if(config==null||!config.isObject())throw new IllegalArgumentException("设备配置必须为 JSON 对象");
        config.fields().forEachRemaining(e->bindings.put(e.getKey(),new Binding(e.getKey(),e.getValue())));
        try {
            for(var binding:bindings.values()) {
                try {publish(binding.id,binding.connect().snapshot(),"startup");}
                catch(Exception ex){failure(binding.id,ex);if(requireReady)throw ex;}
            }
        }catch(Exception ex){close();throw ex;}
    }
    private Binding binding(String id) {
        var binding=bindings.get(id);
        if(binding==null)throw new NoSuchElementException("未知设备："+id);
        return binding;
    }
    private synchronized void publish(String id,JsonNode snapshot,String origin) {
        if(closed)return;
        ObjectNode event=JSON.createObjectNode().put("type","snapshot").put("device_id",id)
            .put("origin",origin).put("connected",true);
        event.set("snapshot",normalizeSnapshot(snapshot));latest.put(id,event);if(active)notices.accept(event);
    }
    private synchronized void failure(String id,Exception ex) {
        if(closed)return;
        var event=JSON.createObjectNode().put("type","device_error").put("device_id",id)
            .put("connected",false).put("error",ex.toString());
        latest.put(id,event);if(active)notices.accept(event);
    }
    /** A prepared session remains silent until its owner commits the replacement. */
    public synchronized void activate() {
        if(closed)throw new IllegalStateException("Tango 连接已关闭");
        latest.values().forEach(event->notices.accept(event.deepCopy()));
        active=true;
    }
    public JsonNode snapshot(String id) throws Exception {
        try {return normalizeSnapshot(binding(id).connect().snapshot());}
        catch(Exception ex){failure(id,ex);throw ex;}
    }
    private static JsonNode normalizeSnapshot(JsonNode raw) {
        JsonNode value=raw.deepCopy();
        if(value instanceof ObjectNode snapshot&&snapshot.has("component")) {
            if(snapshot.has("componentLabel")&&!snapshot.get("componentLabel").equals(snapshot.get("component")))
                throw new IllegalArgumentException("组件显示名称字段冲突：componentLabel / component");
            if(!snapshot.has("componentLabel"))snapshot.set("componentLabel",snapshot.get("component"));
            snapshot.remove("component");
        }
        return value;
    }
    public JsonNode call(String id,String command,JsonNode argument) throws Exception {
        JsonNode result=binding(id).connect().call(command,argument);
        try {publish(id,snapshot(id),"command");}catch(Exception ignored){}
        return result;
    }
    public void reconcile() {
        for(var binding:bindings.values()) {
            if(closed)return;
            try {
                JsonNode value=normalizeSnapshot(binding.connect().snapshot()),previous=latest.get(binding.id);
                if(previous==null||!previous.path("connected").asBoolean()||!previous.path("snapshot").equals(value))
                    publish(binding.id,value,"reconcile");
            }catch(Exception ex){failure(binding.id,ex);}
        }
    }
    public ArrayNode catalog() {
        var result=JSON.createArrayNode();
        for(var binding:bindings.values()) {
            var value=result.addObject().put("id",binding.id).put("deviceBindingKey",binding.deviceBindingKey).put("address",binding.address);
            var event=latest.get(binding.id);if(event!=null)value.setAll(event);else value.put("connected",false);
        }
        return result;
    }
    public Map<String,JsonNode> snapshots() {
        var result=new LinkedHashMap<String,JsonNode>();
        latest.forEach((id,event)->{if(event.path("connected").asBoolean())result.put(id,event.path("snapshot").deepCopy());});
        return result;
    }
    @Override public void close(){closed=true;bindings.values().forEach(Binding::close);}

    private static final class NativeConnection implements Connection {
        final DeviceProxy proxy;
        TangoEventsAdapter events;
        ITangoChangeListener listener;
        NativeConnection(String address,Consumer<JsonNode> snapshot,Consumer<Exception> failure) throws Exception {
            proxy=new DeviceProxy(address);proxy.set_timeout_millis(2500);proxy.set_source(fr.esrf.Tango.DevSource.DEV);
            listener=event->{
                try {snapshot.accept(event==null||event.getValue()==null?snapshot():JSON.readTree(event.getValue().extractString()));}
                catch(Exception ex){failure.accept(ex);}
            };
            try {events=new TangoEventsAdapter(proxy);events.addTangoChangeListener(listener,"Snapshot",true);}
            catch(Exception ignored){close();} // Snapshot reconciliation also supports servers without ZMQ.
        }
        public JsonNode snapshot() throws Exception {return JSON.readTree(proxy.read_attribute("Snapshot").extractString());}
        public JsonNode call(String command,JsonNode argument) throws Exception {
            var input=new DeviceData();
            if("History".equals(command))input.insert(argument.asInt());
            else if("GetResult".equals(command))input.insert(argument.asText());
            else input.insert(JSON.writeValueAsString(argument));
            return JSON.readTree(proxy.command_inout(command,input).extractString());
        }
        public void close() {
            if(events!=null)try{events.removeTangoChangeListener(listener,"Snapshot");}catch(Exception ignored){}
            events=null;
        }
    }
}
