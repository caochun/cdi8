package cn.cdi8.tango;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TangoAdapterBoundaryTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static JsonNode bindings(){return JSON.createObjectNode().set("seed_01",JSON.createObjectNode().put("deviceBindingKey","seed").put("address","test"));}
    static ObjectNode snapshot(String state) {
        ObjectNode value=JSON.createObjectNode();
        value.put("snapshot_kind","component").put("source_id","source-a").put("state_revision",1)
            .put("model","seed-source-reference-v2").put("model_version",1)
            .put("model_digest","0000000000000000000000000000000000000000000000000000000000000000")
            .putNull("model_revision").put("componentLabel","seed").put("engine","apache-commons-scxml2")
            .put("state_id",state).put("outer_state","ready").put("outer_state_label","ready")
            .put("inner_state",state).put("inner_state_label",state).putNull("active_command_id")
            .putNull("active_action").put("task_state","idle").put("supports_entry_condition",true)
            .put("supports_action_contract",true).putNull("last_result");
        value.set("context",JSON.createObjectNode());
        value.set("action_contracts",JSON.createObjectNode());
        return value;
    }
    static class Device implements TangoAdapter.Connection {
        Consumer<JsonNode> callback;
        boolean closed,offline;
        JsonNode value=TangoAdapterBoundaryTest.snapshot("idle");
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
        int before=events.size();device.callback.accept(snapshot("active"));
        assertEquals(before,events.size());assertTrue(device.closed);
    }
    @Test void pollingRecoversDeviceAfterAnOutage() throws Exception {
        var device=new Device();
        try(var adapter=new TangoAdapter(bindings(),e->{},false,(address,snapshot,error)->device)) {
            device.offline=true;adapter.reconcile();assertFalse(adapter.catalog().get(0).path("connected").asBoolean());
            device.offline=false;adapter.reconcile();assertTrue(adapter.catalog().get(0).path("connected").asBoolean());
        }
    }
    @Test void duplicateChangeEventsDoNotCreateDuplicateObservations() throws Exception {
        var device=new Device();var events=new ArrayList<JsonNode>();
        try(var adapter=new TangoAdapter(bindings(),events::add,true,(address,snapshot,error)->{
            device.callback=snapshot;return device;
        })) {
            adapter.activate();
            int before=events.size();
            device.callback.accept(device.value.deepCopy());
            assertEquals(before,events.size());
            assertEquals(1,adapter.snapshots().get("seed_01").path("state_revision").asInt());
        }
    }
    @Test void reconciliationPublishesAChangedSnapshotWhenChangeEventWasLost() throws Exception {
        var device=new Device();var events=new ArrayList<JsonNode>();
        try(var adapter=new TangoAdapter(bindings(),events::add,true,(address,snapshot,error)->device)) {
            adapter.activate();events.clear();
            device.value=snapshot("active").put("state_revision",2);
            adapter.reconcile();
            assertEquals(1,events.size());
            assertEquals("reconcile",events.get(0).path("origin").asText());
            assertEquals("active",adapter.snapshots().get("seed_01").path("state_id").asText());
        }
    }
    @Test void commandRefreshesTheCachedSnapshotImmediately() throws Exception {
        var device=new Device();var events=new ArrayList<JsonNode>();
        try(var adapter=new TangoAdapter(bindings(),events::add,true,(address,snapshot,error)->device)) {
            adapter.activate();events.clear();
            device.value=snapshot("active").put("state_revision",2);
            adapter.call("seed_01","GetResult",JSON.getNodeFactory().textNode("command-1"));
            assertEquals("command",events.get(events.size()-1).path("origin").asText());
            assertEquals(2,adapter.snapshots().get("seed_01").path("state_revision").asInt());
        }
    }
    @Test void legacyBindingKeyAndSnapshotLabelHaveSeparateMeanings() throws Exception {
        var config=JSON.createObjectNode();var entry=config.putObject("source_01").put("component","source-adapter").put("address","test");
        var device=new Device();device.value=snapshot("idle").put("component","光纤种子源组件").put("componentLabel","光纤种子源组件");
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
    @Test void incompleteOrInconsistentSnapshotIsRejectedAtTheTangoBoundary() {
        var deviceMissing=new Device();
        ((ObjectNode)deviceMissing.value).remove("state_revision");
        assertThrows(IllegalArgumentException.class,()->new TangoAdapter(bindings(),e->{},true,(address,snapshot,error)->deviceMissing));

        var deviceWithoutKind=new Device();
        ((ObjectNode)deviceWithoutKind.value).remove("snapshot_kind");
        assertThrows(IllegalArgumentException.class,()->new TangoAdapter(bindings(),e->{},true,(address,snapshot,error)->deviceWithoutKind));

        var deviceWithoutActiveFields=new Device();
        ((ObjectNode)deviceWithoutActiveFields.value).remove("active_action");
        assertThrows(IllegalArgumentException.class,()->new TangoAdapter(bindings(),e->{},true,(address,snapshot,error)->deviceWithoutActiveFields));

        var deviceInconsistent=new Device();
        ((ObjectNode)deviceInconsistent.value).put("active_command_id","command-1");
        assertThrows(IllegalArgumentException.class,()->new TangoAdapter(bindings(),e->{},true,(address,snapshot,error)->deviceInconsistent));
    }
    @Test void conditionSourceUsesTheSameObservationEnvelope() {
        ObjectNode value=JSON.createObjectNode().put("snapshot_kind","condition-source")
            .put("source_id","context-source").put("state_revision",0)
            .put("model","preparation-context-demo-v1").put("componentLabel","演示同步及环境条件")
            .put("engine","condition-source").put("task_state","idle")
            .putNull("active_command_id").putNull("active_action").putNull("last_result");
        value.set("conditions",JSON.createObjectNode().put("safety_permit",false));
        assertDoesNotThrow(()->SnapshotContract.validate(value));
        value.put("active_command_id","command-1");
        assertThrows(IllegalArgumentException.class,()->SnapshotContract.validate(value));
    }
}
