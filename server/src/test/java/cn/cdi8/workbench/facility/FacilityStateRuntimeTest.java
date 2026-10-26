package cn.cdi8.workbench.facility;

import cn.cdi8.workbench.integration.tango.TangoGateway;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Event integrity rules use an in-memory transport; HTTP/JTango is covered by NewChainIT. */
class FacilityStateRuntimeTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static final String DEVICE="a-device-without-component-prefix";
    static final String PROJECTION="""
        <facility-state-projection xmlns="https://cdi8.cn/facility-state/v1" id="test-state"
          definitionRef="gxlf-facility" bindingRef="gxlf-facility-binding" evidenceMaxAgeMs="10000">
          <observations><observation id="seedState" adapter="device-state"><source instanceSetRef="seed" portRef="state-out" /></observation></observations>
          <facts><fact id="seedReady" label="种子源完成上电检查"><condition><devicePredicate instanceSetRef="seed" states="powerOnChecked" /></condition></fact></facts>
          <guards><guard id="ready" label="可继续"><condition><factPredicate factRef="seedReady" /></condition></guard></guards>
        </facility-state-projection>
        """;
    static class Transport extends TangoGateway {
        boolean online=true, present=true;
        ObjectNode value=JSON.createObjectNode().put("source_id","source-a").put("state_revision",10).put("state_id","powerOnChecked");
        Transport(){super(JSON,event->{},"http://unused",false);}
        @Override public boolean connected(){return online;}
        @Override public JsonNode snapshot(String id){return value.deepCopy();}
        @Override public JsonNode catalog(){
            ArrayNode result=JSON.createArrayNode();
            if(present)result.addObject().put("id",DEVICE).put("component","seed").put("connected",online);
            return result;
        }
    }
    Transport transport;
    FacilityStateRuntime runtime;
    @BeforeEach void setUp() throws Exception {
        transport=new Transport();runtime=new FacilityStateRuntime(JSON,transport,event->{});
        String defaults=resource("gxlf-facility-state-projection.xml");
        String deviceFacts=defaults.substring(defaults.indexOf("<deviceFacts>"),defaults.indexOf("</deviceFacts>")+"</deviceFacts>".length());
        runtime.activate(resource("gxlf-facility.system-definition.xml"),resource("gxlf-facility-binding.xml"),PROJECTION.replace("<facts>",deviceFacts+"<facts>"));
    }
    @AfterEach void close(){transport.close();}
    String status(){return runtime.current().path("guards").path("ready").path("status").asText();}
    String resource(String name) throws Exception {
        try(var input=getClass().getResourceAsStream("/system-models/"+name)){return new String(input.readAllBytes(),StandardCharsets.UTF_8);}
    }
    @Test void resolvesBindingWithoutGuessingDevicePrefix() {
        assertEquals("valid",status());assertDoesNotThrow(()->runtime.requireGuard("ready"));
    }
    @Test void oldRevisionCannotRestoreAStateFact() {
        runtime.observe(new TangoGateway.Notice(JSON.createObjectNode().put("type","snapshot").put("connected",true)
            .put("device_id",DEVICE).set("snapshot",transport.value.deepCopy().put("state_revision",9))));
        assertEquals("unknown",status());runtime.refresh();
        assertEquals("unknown",status(),"快照补读不自动清除回退锁存");
        assertThrows(IllegalStateException.class,()->runtime.requireGuard("ready"));
    }
    @Test void newSourceDoesNotReusePreviousEvidence() {
        transport.value.put("source_id","source-b");runtime.refresh();assertEquals("unknown",status());
    }
    @Test void disconnectImmediatelyInvalidatesFact() {
        transport.online=false;
        runtime.observe(new TangoGateway.Notice(JSON.createObjectNode().put("type","connection_lost")));
        assertEquals("unknown",status());
    }
    @Test void missingDeviceTypeNeverPassesUniversalRequirement() {
        transport.present=false;runtime.refresh();assertEquals("unknown",status());
    }

    @Test void publishesFactChangesWithoutIssuingWorkflowCommands() {
        var notices=new ArrayList<Object>();
        runtime=new FacilityStateRuntime(JSON,transport,notices::add);
        runtime.refresh();assertFalse(notices.isEmpty());
        notices.clear();runtime.refresh();assertTrue(notices.isEmpty(),"重复快照不产生重复事实变化通知");
        transport.value.put("state_id","statusUnknown").put("state_revision",11);
        runtime.refresh();assertFalse(notices.isEmpty());
        assertTrue(notices.stream().allMatch(item->item instanceof FacilityStateRuntime.Changed));
    }

    @Test void completionRechecksAConditionAfterItsWakeUp() {
        var completions=new FacilityCompletionRuntime(JSON,runtime);
        var wait=JSON.createObjectNode().put("id","wait-1").put("kind","fact").put("condition","seedReady")
            .put("deadline",System.currentTimeMillis()+10_000);
        assertTrue(completions.evaluate(wait.toString(),true).path("met").asBoolean());
        transport.value.put("state_id","statusUnknown").put("state_revision",11);
        runtime.observe(new TangoGateway.Notice(JSON.createObjectNode().put("type","snapshot").put("connected",true)
            .put("device_id",DEVICE).set("snapshot",transport.value.deepCopy())));
        assertFalse(completions.evaluate(wait.toString(),false).path("met").asBoolean(),"旧的成立通知不能替代最新事实");
    }
    void activateFacts(String facts,String guard)throws Exception {
        String xml=resource("gxlf-facility-state-projection.xml");
        xml=xml.substring(0,xml.indexOf("<facts>"))+"<facts>"+facts+"</facts>"+xml.substring(xml.indexOf("</facts>")+8);
        xml=xml.substring(0,xml.indexOf("<guards>"))+"<guards><guard id=\"ready\" label=\"条件\"><condition>"+guard+"</condition></guard></guards>"+xml.substring(xml.indexOf("</guards>")+9);
        runtime.activate(resource("gxlf-facility.system-definition.xml"),resource("gxlf-facility-binding.xml"),xml);
    }
    @Test void nestedAllAnyUseThreeValuedLogicWithoutTreatingUnknownAsTrue()throws Exception {
        String facts="""
            <fact id="good" label="成功"><condition><devicePredicate instanceSetRef="seed" states="powerOnChecked" /></condition></fact>
            <fact id="bad" label="失败"><condition><devicePredicate instanceSetRef="seed" states="stableEmission" /></condition></fact>
            <fact id="missing" label="未知"><condition><observationPredicate observationRef="shieldDoor" expected="locked" /></condition></fact>
            """;
        for(String[] example:new String[][]{{"all","good","missing","unknown"},{"all","bad","missing","violated"},
            {"any","good","missing","valid"},{"any","bad","missing","unknown"},{"any","bad","bad","violated"}}) {
            activateFacts(facts,"<"+example[0]+"><factPredicate factRef=\""+example[1]+"\" /><factPredicate factRef=\""+example[2]+"\" /></"+example[0]+">");
            assertEquals(example[3],status(),java.util.Arrays.toString(example));
        }
        activateFacts(facts,"<all><factPredicate factRef=\"good\" /><any><factPredicate factRef=\"bad\" /><factPredicate factRef=\"good\" /></any></all>");
        assertEquals("valid",status());
    }
    @Test void forwardFactReferencesAreEvaluatedBeforeTheirDependents()throws Exception {
        activateFacts("""
            <fact id="first" label="先声明引用"><condition><factPredicate factRef="later" /></condition></fact>
            <fact id="later" label="后声明来源"><condition><devicePredicate instanceSetRef="seed" states="powerOnChecked" /></condition></fact>
            ""","<factPredicate factRef=\"first\" />");
        assertEquals("valid",status());
        transport.value.put("state_id","fault").put("state_revision",11);runtime.refresh();
        assertEquals("violated",status());
    }
    @Test void connectivityAlsoExpiresWhenNoFreshSnapshotArrives()throws Exception {
        String xml=resource("gxlf-facility-state-projection.xml").replace("evidenceMaxAgeMs=\"2000\"","evidenceMaxAgeMs=\"30\"");
        runtime.activate(resource("gxlf-facility.system-definition.xml"),resource("gxlf-facility-binding.xml"),xml);
        assertEquals("valid",runtime.current().path("facts").path("simulationDevicesObserved").path("status").asText());
        Thread.sleep(60);
        assertEquals("unknown",runtime.assessment(false).path("facts").path("simulationDevicesObserved").path("status").asText());
    }
    @Test void failedCrossModelActivationLeavesCurrentProjectionIntact()throws Exception {
        assertEquals("valid",status());
        String xml=resource("gxlf-facility-state-projection.xml").replace("instanceSetRef=\"seed\"","instanceSetRef=\"missing\"");
        assertThrows(IllegalArgumentException.class,()->runtime.activate(resource("gxlf-facility.system-definition.xml"),resource("gxlf-facility-binding.xml"),xml));
        assertEquals("test-state",runtime.projection().id());assertEquals("valid",status());
    }

}
