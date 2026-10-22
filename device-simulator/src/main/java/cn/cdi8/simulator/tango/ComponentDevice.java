package cn.cdi8.simulator.tango;

import cn.cdi8.simulator.runtime.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import fr.esrf.Tango.DevFailed;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import org.tango.server.annotation.*;
import org.tango.server.device.DeviceManager;
import org.tango.server.events.EventType;
import org.tango.utils.DevFailedUtils;
import static cn.cdi8.simulator.runtime.ComponentModel.*;

/** A real JTango device exposing the same command/snapshot contract as PyTango. */
@Device(transactionType=TransactionType.NONE)
public class ComponentDevice {
    public record Settings(ComponentModel model, Path database, boolean autoComplete, long delayMs,
                            boolean context, Map<String,Boolean> contextDefaults, String revision) {
        public Settings(ComponentModel model, Path database, boolean autoComplete, long delayMs) {
            this(model,database,autoComplete,delayMs,null);
        }
        public Settings(ComponentModel model, Path database, boolean autoComplete, long delayMs, String revision) {
            this(model,database,autoComplete,delayMs,false,Map.of(),revision);
        }
        public static Settings context(boolean autoComplete, long delayMs, Map<String,Boolean> defaults) {
            return new Settings(null,null,autoComplete,delayMs,true,Map.copyOf(defaults),null);
        }
    }
    private static final Map<String,Settings> settingsByDevice=new ConcurrentHashMap<>();
    public static void clearConfiguration() { settingsByDevice.clear(); }
    public static void configure(Settings value) { configure("*",value); }
    public static void configure(String deviceName, Settings value) { settingsByDevice.put(deviceName,value); }
    @DeviceManagement private DeviceManager device;
    public void setDevice(DeviceManager value) { this.device=value; }
    private Settings settings;
    private ComponentRuntime runtime;
    private ContextState context;
    private ScheduledExecutorService completions;
    private final Set<String> pending=ConcurrentHashMap.newKeySet();

    @Init public void init() throws DevFailed {
        try {
            delete();
            settings=settingsByDevice.getOrDefault(device.getName(),settingsByDevice.get("*"));
            if (settings==null) throw new IllegalStateException("未配置设备："+device.getName());
            if (settings.context()) context=new ContextState(settings.contextDefaults());
            else runtime=new ComponentRuntime(settings.model(),settings.database(),settings.revision());
            completions=Executors.newSingleThreadScheduledExecutor(task->{var thread=new Thread(task,"component-demo-feedback");thread.setDaemon(true);return thread;});
        } catch (RuntimeException ex) { throw DevFailedUtils.newDevFailed(ex); }
    }
    @Delete public void delete() {
        if (completions!=null) completions.shutdownNow();
        pending.clear();
        if (runtime!=null) { runtime.close();runtime=null; }
        context=null;
        settings=null;
    }
    @Attribute(name="Snapshot",pushChangeEvent=true,checkChangeEvent=false)
    public String getSnapshot() throws DevFailed {
        try { return wire(settings.context()?context.snapshot():runtime.snapshot()); }
        catch (RuntimeException ex) { throw DevFailedUtils.newDevFailed(ex); }
    }
    private String wire(JsonNode value) {
        // Match PyTango's ensure_ascii=True across Tango IDL string code sets.
        try { return JSON.writer().with(JsonWriteFeature.ESCAPE_NON_ASCII).writeValueAsString(value); }
        catch (Exception ex) { throw new IllegalArgumentException("无法编码设备 JSON",ex); }
    }
    private String publish(JsonNode value) throws DevFailed {
        device.pushEvent("Snapshot",EventType.CHANGE_EVENT);
        return wire(value);
    }
    @Command(name="Execute") public String execute(String payload) throws DevFailed {
        try {
            JsonNode request=parse(payload);
            JsonNode result=settings.context()?context.execute(request):runtime.execute(request);
            String response=publish(result);
            String id=result.path("command_id").asText();
            if (settings.autoComplete() && result.path("status").asText().equals("accepted") && pending.add(id)) {
                completions.schedule(()->{
                    try {
                        boolean accepted=settings.context()?context.result(id).path("status").asText().equals("accepted"):
                            runtime.result(id).path("status").asText().equals("accepted");
                        if (accepted) {
                            JsonNode completion=JSON.createObjectNode().put("command_id",id).put("outcome","success");
                            publish(settings.context()?context.complete(completion):runtime.simulate(completion));
                        }
                    } catch (Exception ex) { org.slf4j.LoggerFactory.getLogger(ComponentDevice.class).error("Demo feedback failed for {}",id,ex); }
                    finally { pending.remove(id); }
                },settings.delayMs(),TimeUnit.MILLISECONDS);
            }
            return response;
        } catch (RuntimeException ex) { throw DevFailedUtils.newDevFailed(ex); }
    }
    @Command(name="Complete") public String complete(String payload) throws DevFailed {
        try { return publish(settings.context()?context.complete(parse(payload)):runtime.complete(parse(payload))); }
        catch (RuntimeException ex) { throw DevFailedUtils.newDevFailed(ex); }
    }
    @Command(name="Simulate") public String simulate(String payload) throws DevFailed {
        try { return publish(settings.context()?context.complete(parse(payload)):runtime.simulate(parse(payload))); }
        catch (RuntimeException ex) { throw DevFailedUtils.newDevFailed(ex); }
    }
    @Command(name="ReportEvent") public String reportEvent(String payload) throws DevFailed {
        if (settings.context()) throw DevFailedUtils.newDevFailed("上下文设备不支持 ReportEvent");
        try { return publish(runtime.reportEvent(parse(payload))); }
        catch (RuntimeException ex) { throw DevFailedUtils.newDevFailed(ex); }
    }
    @Command(name="GetResult") public String result(String id) throws DevFailed {
        try { return wire(settings.context()?context.result(id):runtime.result(id)); }
        catch (RuntimeException ex) { throw DevFailedUtils.newDevFailed(ex); }
    }
    @Command(name="History") public String history(int limit) throws DevFailed {
        try { return wire(settings.context()?context.history(limit):runtime.history(limit)); }
        catch (RuntimeException ex) { throw DevFailedUtils.newDevFailed(ex); }
    }

    @Command(name="SetConditions") public String setConditions(String payload) throws DevFailed {
        if (!settings.context()) throw DevFailedUtils.newDevFailed("组件设备不支持 SetConditions");
        try { return publish(context.setConditions(parse(payload))); }
        catch (RuntimeException ex) { throw DevFailedUtils.newDevFailed(ex); }
    }

    private static final class ContextState {
        private static final Set<String> BASE_CONDITIONS=new LinkedHashSet<>(List.of(
            "safety_permit","preamp_repeat_ready","collimation_environment_ready","diagnosis_acquisition_ready",
            "crystal_pose_ready","charge_preparation_ready","preamp_single_recipe_ready","measurement_single_recipe_ready",
            "target_measurement_ready","switch_charge_ready","charge_complete","warning_music_stopped",
            "trigger_countdown_started","pump_trigger_ready","switch_trigger_ready","synchronized_triggered",
            "diagnostic_postprocess_ready","pump_data_collected","pump_standby_ready","preionization_prepared",
            "preionization_music_started","preionization_charge_complete","preionization_music_stopped",
            "preionization_countdown_started","preionization_pump_trigger_ready","preionization_sync_triggered",
            "preionization_data_collected","preionization_standby_ready","warning_light_off","laser_parameter_collected",
            "target_analysis_ready","target_retracted","switch_waveform_collected","switch_standby_ready",
            "slab_purge_complete","shielding_door_open"));
        private final Map<String,Boolean> conditions=new LinkedHashMap<>();
        private final Map<String,ObjectNode> results=new LinkedHashMap<>();
        private String active,last;
        ContextState(Map<String,Boolean> defaults) {
            for(String key:BASE_CONDITIONS) conditions.put(key,false);
            if(defaults!=null) for(var entry:defaults.entrySet()) {
                if(entry.getKey()==null||entry.getKey().isBlank()||entry.getValue()==null) throw new IllegalArgumentException("context_defaults 必须是命名布尔条件");
                conditions.put(entry.getKey(),entry.getValue());
            }
        }
        synchronized JsonNode snapshot() {
            ObjectNode value=JSON.createObjectNode().put("model","preparation-context-demo-v1").put("component","演示同步及环境条件");
            value.set("conditions",JSON.valueToTree(conditions));value.put("active_command_id",active);
            value.put("active_action",active==null?null:"preamp_repeat");
            value.set("last_result",last==null?NullNode.instance:results.get(last));return value;
        }
        synchronized JsonNode execute(JsonNode request) {
            requireObject(request);String id=identifier(request,"command_id");String action=text(request,"action");
            ObjectNode previous=results.get(id);
            if(previous!=null) { if(!request.equals(previous.path("request"))) throw new IllegalArgumentException("command ID reused"); return previous.deepCopy(); }
            if(!"preamp_repeat".equals(action)||active!=null) throw new IllegalArgumentException("unsupported action or command already active");
            conditions.put("preamp_repeat_ready",false);active=id;
            ObjectNode result=JSON.createObjectNode().put("command_id",id).put("action",action).put("status","accepted");result.set("request",request.deepCopy());results.put(id,result);return result.deepCopy();
        }
        synchronized JsonNode complete(JsonNode request) {
            requireObject(request);String id=identifier(request,"command_id");String outcome=text(request,"outcome");
            if(!Set.of("success","failure").contains(outcome)) throw new IllegalArgumentException("outcome must be success or failure");
            ObjectNode result=results.get(id);if(result==null) throw new IllegalArgumentException("unknown command: "+id);
            String expected="success".equals(outcome)?"succeeded":"failed";
            if(!"accepted".equals(result.path("status").asText())) { if(!expected.equals(result.path("status").asText())) throw new IllegalArgumentException("conflicting completion"); return result.deepCopy(); }
            if(!id.equals(active)) throw new IllegalArgumentException("feedback is not for active command");
            conditions.put("preamp_repeat_ready","success".equals(outcome));active=null;last=id;result.put("status",expected);
            ObjectNode snapshot=JSON.createObjectNode();snapshot.set("conditions",JSON.valueToTree(conditions));snapshot.putNull("active_command_id");result.set("snapshot",snapshot);return result.deepCopy();
        }
        synchronized JsonNode result(String id) { if(id==null||!results.containsKey(id)) throw new IllegalArgumentException("unknown command: "+id);return results.get(id).deepCopy(); }
        synchronized JsonNode history(int limit) { int count=Math.max(0,Math.min(limit,1000));ArrayNode value=JSON.createArrayNode();var values=new ArrayList<>(results.values());for(int i=Math.max(0,values.size()-count);i<values.size();i++)value.add(values.get(i));return value; }
        synchronized JsonNode setConditions(JsonNode request) { requireObject(request);if(request.isEmpty()) throw new IllegalArgumentException("expected named boolean conditions");for(var fields=request.fields();fields.hasNext();) {var entry=fields.next();if(!conditions.containsKey(entry.getKey())||!entry.getValue().isBoolean())throw new IllegalArgumentException("expected named boolean conditions");}request.fields().forEachRemaining(entry->conditions.put(entry.getKey(),entry.getValue().asBoolean()));return snapshot(); }
        private static void requireObject(JsonNode value){if(value==null||!value.isObject())throw new IllegalArgumentException("请求必须是 JSON 对象");}
        private static String identifier(JsonNode value,String key){String id=value.path(key).asText("");if(!id.matches("[A-Za-z0-9_-]{1,128}"))throw new IllegalArgumentException("非法任务编号");return id;}
        private static String text(JsonNode value,String key){String text=value.path(key).asText("");if(text.isBlank())throw new IllegalArgumentException(key+" 必须为非空字符串");return text;}
    }
}
