package cn.cdi8.workbench.workflow.facility;

import cn.cdi8.workbench.integration.facility.FacilityDependencyClient;
import cn.cdi8.workbench.integration.facility.FacilityStateClient;

import cn.cdi8.workbench.integration.facility.FacilityClient;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import org.flowable.engine.delegate.DelegateExecution;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercise HTTP failures and BPMN callbacks at the new remote boundary. */
class FacilityRemoteBoundaryTest {
    static final ObjectMapper JSON=new ObjectMapper();
    HttpServer remote;FacilityClient client;FacilityStateClient states;
    volatile int status;volatile JsonNode response;
    final List<String> requests=new ArrayList<>();
    JsonNode lastBody;
    @BeforeEach void setup()throws Exception {
        status=200;response=JSON.createObjectNode();
        remote=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        remote.createContext("/api/facility",exchange->{
            requests.add(exchange.getRequestMethod()+" "+exchange.getRequestURI());
            byte[] body=exchange.getRequestBody().readAllBytes();lastBody=body.length==0?null:JSON.readTree(body);
            byte[] bytes=JSON.writeValueAsBytes(response);exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });
        remote.start();
        client=new FacilityClient(JSON,event->{},"http://127.0.0.1:"+remote.getAddress().getPort(),false);
        states=new FacilityStateClient(JSON,client,event->{});
    }
    @AfterEach void close(){client.close();remote.stop(0);}
    ObjectNode waitRecord(){return JSON.createObjectNode().put("id","wait").put("kind","fact").put("condition","ready").put("deadline",System.currentTimeMillis()+60000);}
    @Test void unavailableSupervisorCannotReleaseGuardsCompletionOrDependencies() {
        status=503;response=JSON.createObjectNode().put("error","supervisor unavailable");
        var assessment=states.assessment(true);assertFalse(assessment.path("connected").asBoolean());assertEquals("unknown",assessment.path("integrity").asText());
        assertThrows(IllegalStateException.class,()->states.requireGuard("ready"));
        assertFalse(new FacilityCompletionRuntime(JSON,states).evaluate(waitRecord().toString(),true).path("met").asBoolean());
        var dependency=new FacilityDependencyClient(JSON,client).check(JSON.createObjectNode().put("id","held").toString(),true,true);
        assertEquals("unknown",dependency.path("status").asText());assertTrue(dependency.path("latched").asBoolean());
    }
    @Test void actualFlowableCallbackRechecksRemoteEvidence() {
        var assessment=JSON.createObjectNode().put("connected",true);assessment.putObject("facts").putObject("ready").put("status","valid");response=assessment;
        var completion=new FacilityCompletionRuntime(JSON,states);var record=completion.evaluate(waitRecord().toString(),true);
        assertTrue(record.path("met").asBoolean());
        assessment.withObject("/facts/ready").put("status","unknown");
        var execution=mock(DelegateExecution.class);when(execution.getVariableLocal(FacilityCompletionRuntime.VARIABLE)).thenReturn(record.toString());
        assertFalse(completion.resume(execution));verify(execution,never()).removeVariableLocal(FacilityCompletionRuntime.VARIABLE);
    }
    @Test void productionCommandsUseSupervisionAdmissionEndpoint() {
        client.execute("seed_01","cmd","power_on_check",Map.of(),Map.of("beamlines",List.of(1)));
        assertEquals(List.of("POST /api/facility/devices/seed_01/commands"),requests);
        assertEquals(1,lastBody.path("admission").path("beamlines").get(0).asInt());
        client.catalog();assertEquals("POST /api/facility/adapter",requests.get(1));assertEquals("/devices",lastBody.path("path").asText());
    }
    @Test void modelChangeNotificationInvalidatesWorkbenchModelCache()throws Exception {
        String definition=Files.readString(Path.of("examples/gxlf/system/gxlf-facility.system-definition.xml"));
        response=JSON.createObjectNode().put("definitionXml",definition);
        assertEquals("gxlf-facility",states.definition().id());
        response=JSON.createObjectNode().put("definitionXml",definition.replace("id=\"gxlf-facility\"","id=\"updated-facility\""));
        states.notice(new FacilityClient.Notice(JSON.createObjectNode().put("type","models_changed")));
        assertEquals("updated-facility",states.definition().id());assertEquals(2,requests.size());
    }
}
