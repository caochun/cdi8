package cn.cdi8.facility.api;

import cn.cdi8.facility.runtime.FacilityStateRuntime;
import cn.cdi8.facility.integration.TangoGateway;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.context.event.EventListener;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** Live notifications accelerate reconciliation; latched evidence lives in the supervision runtime. */
@RestController
public final class FacilityEvents {
    private final ObjectMapper json;
    private final TangoGateway gateway;
    private final Set<BlockingQueue<JsonNode>> subscribers=ConcurrentHashMap.newKeySet();
    public FacilityEvents(ObjectMapper json,TangoGateway gateway){this.json=json;this.gateway=gateway;}
    @EventListener public void notice(TangoGateway.Notice event){if(gateway.accepts(event))publish(event.payload());}
    @EventListener public void changed(FacilityStateRuntime.Changed event){publish(json.createObjectNode().put("type","facility_changed").put("sequence",event.sequence()));}
    @EventListener public void modelsChanged(FacilityStateRuntime.ModelsChanged event){publish(json.createObjectNode().put("type","models_changed"));}
    private synchronized void publish(JsonNode event){for(var queue:subscribers)if(!queue.offer(event)){queue.clear();queue.offer(json.createObjectNode().put("type","connection_lost").put("reason","监督通知缓冲区溢出，需重新核验"));}}
    @GetMapping(value={"/api/facility/events","/api/supervision/events"},produces="application/x-ndjson")
    public synchronized StreamingResponseBody events(){
        var queue=new LinkedBlockingQueue<JsonNode>(1024);
        String generationId=gateway.generationId();
        // Supply an initial device view to a newly started workbench. System latches
        // remain authoritative even if notifications were missed before subscribing.
        // Registration and bootstrap share the publication lock; no network call
        // or transition can fall between the initial view and subscription.
        gateway.cachedSnapshots().forEach((id,snapshot)->queue.offer(json.createObjectNode().put("type","snapshot").put("device_id",id).put("connected",true)
            .put("generation_id",generationId).set("snapshot",snapshot)));
        queue.offer(json.createObjectNode().put("type",gateway.connected()?"ready":"connection_lost").put("generation_id",generationId));
        subscribers.add(queue);
        return output->{
            try {
                while(!Thread.currentThread().isInterrupted()){
                    JsonNode event=queue.poll(3,TimeUnit.SECONDS);
                    if(event==null)event=json.createObjectNode().put("type",gateway.connected()?"ready":"connection_lost").put("reason","设备观测通道不可用");
                    output.write(json.writeValueAsBytes(event));output.write(10);output.flush();
                }
            }catch(InterruptedException ex){Thread.currentThread().interrupt();}
            catch(java.io.IOException ignored){ /* subscriber disconnected */ }
            finally{subscribers.remove(queue);}
        };
    }
}
