package cn.cdi8.tango;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TangoAdapterBoundaryTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static JsonNode bindings(){return JSON.createObjectNode().set("seed_01",JSON.createObjectNode().put("deviceBindingKey","seed").put("address","test"));}
    static class Device implements TangoAdapter.Connection {
        Consumer<JsonNode> callback;
        boolean closed,offline;
        JsonNode value=JSON.createObjectNode().put("state_id","idle");
        public JsonNode snapshot(){if(offline)throw new IllegalStateException("offline");return value;}
        public JsonNode call(String name,JsonNode argument){return JSON.createObjectNode().put("command",name).set("argument",argument);}
        public void close(){closed=true;}
    }
    @Test void unavailableReplacementClosesItsConnections() {
        var device=new Device();device.offline=true;
        assertThrows(IllegalStateException.class,()->new TangoAdapter(bindings(),e->{},true,(address,snapshot,error)->device));
        assertTrue(device.closed);
    }
    @Test void lateEventsFromClosedSessionAreDiscarded() throws Exception {
        var device=new Device();var events=new ArrayList<JsonNode>();
        try(var adapter=new TangoAdapter(bindings(),events::add,true,(address,snapshot,error)->{device.callback=snapshot;return device;})) {
            adapter.activate();
            assertEquals("idle",adapter.snapshot("seed_01").path("state_id").asText());
            assertEquals("GetResult",adapter.call("seed_01","GetResult",JSON.getNodeFactory().textNode("one")).path("command").asText());
        }
        int before=events.size();device.callback.accept(JSON.createObjectNode().put("state_id","active"));
        assertEquals(before,events.size());assertTrue(device.closed);
    }
    @Test void pollingRecoversDeviceAfterAnOutage() throws Exception {
        var device=new Device();
        try(var adapter=new TangoAdapter(bindings(),e->{},false,(address,snapshot,error)->device)) {
            device.offline=true;adapter.reconcile();assertFalse(adapter.catalog().get(0).path("connected").asBoolean());
            device.offline=false;adapter.reconcile();assertTrue(adapter.catalog().get(0).path("connected").asBoolean());
        }
    }
    @Test void legacyBindingKeyAndSnapshotLabelHaveSeparateMeanings() throws Exception {
        var config=JSON.createObjectNode();var entry=config.putObject("source_01").put("component","source-adapter").put("address","test");
        var device=new Device();device.value=JSON.createObjectNode().put("component","光纤种子源组件").put("state_id","idle");
        try(var adapter=new TangoAdapter(config,e->{},true,(address,snapshot,error)->device)) {
            var catalog=adapter.catalog().get(0);
            assertEquals("source-adapter",catalog.path("deviceBindingKey").asText());
            assertFalse(catalog.has("component"));
            assertEquals("光纤种子源组件",adapter.snapshot("source_01").path("componentLabel").asText());
            assertFalse(adapter.snapshot("source_01").has("component"));
        }
        entry.put("deviceBindingKey","conflicting-source");
        assertThrows(IllegalArgumentException.class,()->new TangoAdapter(config,e->{},true,(address,snapshot,error)->device));
    }
}
