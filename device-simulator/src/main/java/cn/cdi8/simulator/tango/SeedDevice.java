package cn.cdi8.simulator.tango;

import cn.cdi8.simulator.runtime.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import fr.esrf.Tango.DevFailed;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.*;
import org.tango.server.annotation.*;
import org.tango.server.device.DeviceManager;
import org.tango.server.events.EventType;
import org.tango.utils.DevFailedUtils;
import static cn.cdi8.simulator.runtime.ComponentModel.*;

/** A real JTango device exposing the same command/snapshot contract as PyTango. */
@Device(transactionType=TransactionType.NONE)
public class SeedDevice {
    public record Settings(ComponentModel model, Path database, boolean autoComplete, long delayMs) { }
    private static Settings settings;
    public static void configure(Settings value) { settings=value; }
    @DeviceManagement private DeviceManager device;
    public void setDevice(DeviceManager value) { this.device=value; }
    private SeedRuntime runtime;
    private ScheduledExecutorService completions;
    private final Set<String> pending=ConcurrentHashMap.newKeySet();

    @Init public void init() throws DevFailed {
        try {
            delete();
            runtime=new SeedRuntime(settings.model(),settings.database());
            completions=Executors.newSingleThreadScheduledExecutor(task->{var thread=new Thread(task,"seed-demo-feedback");thread.setDaemon(true);return thread;});
        } catch (RuntimeException ex) { throw DevFailedUtils.newDevFailed(ex); }
    }
    @Delete public void delete() {
        if (completions!=null) completions.shutdownNow();
        pending.clear();
        if (runtime!=null) { runtime.close();runtime=null; }
    }
    @Attribute(name="Snapshot",pushChangeEvent=true,checkChangeEvent=false)
    public String getSnapshot() throws DevFailed {
        try { return wire(runtime.snapshot()); }
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
            JsonNode result=runtime.execute(parse(payload));String response=publish(result);
            String id=result.path("command_id").asText();
            if (settings.autoComplete() && result.path("status").asText().equals("accepted") && pending.add(id)) {
                completions.schedule(()->{
                    try {
                        if (runtime.result(id).path("status").asText().equals("accepted"))
                            publish(runtime.simulate(JSON.createObjectNode().put("command_id",id).put("outcome","success")));
                    } catch (Exception ex) { org.slf4j.LoggerFactory.getLogger(SeedDevice.class).error("Demo feedback failed for {}",id,ex); }
                    finally { pending.remove(id); }
                },settings.delayMs(),TimeUnit.MILLISECONDS);
            }
            return response;
        } catch (RuntimeException ex) { throw DevFailedUtils.newDevFailed(ex); }
    }
    @Command(name="Complete") public String complete(String payload) throws DevFailed {
        try { return publish(runtime.complete(parse(payload))); }
        catch (RuntimeException ex) { throw DevFailedUtils.newDevFailed(ex); }
    }
    @Command(name="Simulate") public String simulate(String payload) throws DevFailed {
        try { return publish(runtime.simulate(parse(payload))); }
        catch (RuntimeException ex) { throw DevFailedUtils.newDevFailed(ex); }
    }
    @Command(name="ReportEvent") public String reportEvent(String payload) throws DevFailed {
        try { return publish(runtime.reportEvent(parse(payload))); }
        catch (RuntimeException ex) { throw DevFailedUtils.newDevFailed(ex); }
    }
    @Command(name="GetResult") public String result(String id) throws DevFailed {
        try { return wire(runtime.result(id)); }
        catch (RuntimeException ex) { throw DevFailedUtils.newDevFailed(ex); }
    }
    @Command(name="History") public String history(int limit) throws DevFailed {
        try { return wire(runtime.history(limit)); }
        catch (RuntimeException ex) { throw DevFailedUtils.newDevFailed(ex); }
    }
}
