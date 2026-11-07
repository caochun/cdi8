package cn.cdi8.facility;

import cn.cdi8.facility.runtime.*;
import cn.cdi8.facility.contract.*;
import cn.cdi8.facility.integration.TangoGateway;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP and independent Spring runtime; only the external device adapter is a test double. */
class FacilityBoundaryTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    @TempDir Path directory;
    ConfigurableApplicationContext app;
    Scenario devices;
    String base;

    @TestConfiguration(proxyBeanMethods=false)
    static class AdapterConfiguration {
        static Scenario scenario;
        @Bean @Primary TangoGateway testAdapter(){return new Adapter(scenario);}
    }
    static class Scenario {
        final Map<String,ObjectNode> snapshots=new LinkedHashMap<>();
        final Map<String,String> components=new LinkedHashMap<>();
        final Map<String,ObjectNode> results=new LinkedHashMap<>();
        int executions,replacements;
        boolean online=true,loseResponse;
        synchronized void add(String id,String component,String outer) {
            components.put(id,component);
            snapshots.put(id,JSON.createObjectNode().put("source_id",id+"-generation").put("state_revision",1)
                .put("outer_state",outer).put("state_id","functionReady").putNull("active_action").putNull("active_command_id"));
        }
        synchronized void change(String id,String outer) {
            var value=snapshots.get(id);value.put("outer_state",outer).put("state_revision",value.path("state_revision").asLong()+1);
        }
        synchronized void finish(String id) {results.get(id).put("status","succeeded");}
    }
    static class Adapter extends TangoGateway {
        final Scenario scenario;
        Adapter(Scenario scenario){super(JSON,event->{});this.scenario=scenario;}
        @Override public boolean connected(){return scenario.online;}
        @Override public Map<String,Object> status(){return Map.of("connected",scenario.online,"error","");}
        @Override public JsonNode catalog(){synchronized(scenario){
            var result=JSON.createArrayNode();scenario.components.forEach((id,component)->result.addObject().put("id",id).put("deviceBindingKey",component).put("connected",scenario.online));return result;
        }}
        @Override public JsonNode snapshot(String id){synchronized(scenario){return scenario.snapshots.get(id).deepCopy();}}
        @Override public JsonNode execute(String device,String id,String action,Object parameters){synchronized(scenario){
            scenario.executions++;
            var result=JSON.createObjectNode().put("command_id",id).put("action",action).put("status","accepted");
            scenario.results.put(id,result);
            if(scenario.loseResponse)throw new GatewayException(503,"Execute 已受理，但响应丢失");
            return result.deepCopy();
        }}
        @Override public JsonNode result(String device,String id){synchronized(scenario){
            if(!scenario.online)throw new GatewayException(503,"adapter offline");
            if(!scenario.results.containsKey(id))throw new GatewayException(404,"unknown command");
            return scenario.results.get(id).deepCopy();
        }}
        @Override public JsonNode request(String method,String path,Object body){
            if(path.equals("/lab/environment")){scenario.replacements++;return JSON.createObjectNode().put("replaced",true);}
            throw new AssertionError("Unexpected raw adapter access: "+method+" "+path);
        }
    }
    @BeforeEach void start() {
        devices=new Scenario();devices.add("seed_01","seed","ready");open();
    }
    void open() {
        AdapterConfiguration.scenario=devices;
        app=new SpringApplicationBuilder(FacilityServerApplication.class,AdapterConfiguration.class).run(
            "--server.port=0","--control.events-enabled=false","--control.facility-store="+directory,
            "--spring.main.banner-mode=off","--debug=false","--logging.level.root=ERROR");
        base="http://127.0.0.1:"+app.getEnvironment().getProperty("local.server.port")+"/api/facility";
    }
    @AfterEach void close(){if(app!=null)app.close();}
    void restart(){app.close();app=null;open();}
    HttpResponse<String> request(String method,String path,Object body)throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(10));
        if(body==null)builder.GET();else builder.header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        return HTTP.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode ok(String method,String path,Object body)throws Exception {
        var response=request(method,path,body);assertEquals(200,response.statusCode(),response.body());return JSON.readTree(response.body());
    }
    ObjectNode command(String id,String action){return JSON.createObjectNode().put("command_id",id).put("action",action).set("parameters",JSON.createObjectNode());}
    Map<String,Object> environment(){return Map.of("method","POST","path","/lab/environment","body",Map.of("devices",List.of()));}
    FacilityStateRuntime states(){return app.getBean(FacilityStateRuntime.class);}

    @Test void startsWithoutWorkbenchAndUnknownSafetyCannotBeBypassed()throws Exception {
        assertEquals("facility-server",ok("GET","/health",null).path("service").asText());
        assertThrows(ClassNotFoundException.class,()->Class.forName("org.flowable.engine.ProcessEngine"));
        var facts=ok("GET","/facts?refresh=true",null);
        assertFalse(facts.has("constraints"));assertEquals("unknown",facts.path("facts").path("interlockOperational").path("status").asText());
        var emit=command("unsafe","seed_source_emit");emit.putObject("admission").putObject("plan").put("status","valid");
        assertEquals(409,request("POST","/devices/seed_01/commands",emit).statusCode());
        assertEquals(409,request("POST","/adapter",Map.of("method","POST","path","/devices/seed_01/commands","body",emit)).statusCode());
        assertEquals(0,devices.executions);
    }
    @Test void responseLossAndRestartNeverDispatchTheSameCommandAgain()throws Exception {
        devices.loseResponse=true;var body=command("once","power_on_check");
        assertEquals(503,request("POST","/devices/seed_01/commands",body).statusCode());
        assertTrue(JSON.readTree(directory.resolve("commands.json").toFile()).path("once").has("attemptedAt"));
        restart();
        assertEquals("accepted",ok("POST","/devices/seed_01/commands",body).path("status").asText());
        assertEquals(1,devices.executions);
        assertEquals(409,request("POST","/devices/seed_01/commands",command("once","function_check")).statusCode());
        devices.finish("once");var terminal=ok("GET","/devices/seed_01/commands/once",null);
        devices.online=false;restart();
        assertEquals(terminal,ok("GET","/devices/seed_01/commands/once",null));
        assertEquals(terminal,ok("POST","/devices/seed_01/commands",body));assertEquals(1,devices.executions);
    }
    @Test void unresolvedCommandsPreventEnvironmentAndModelReplacement()throws Exception {
        ok("POST","/devices/seed_01/commands",command("pending","power_on_check"));
        assertEquals(409,request("POST","/adapter",environment()).statusCode());
        var models=ok("GET","/models",null);
        ok("POST","/models",models); // An identical bundle must remain a recovery no-op.
        ((ObjectNode)models).put("projectionXml",models.path("projectionXml").asText().replace("evidenceMaxAgeMs=\"2000\"","evidenceMaxAgeMs=\"3000\""));
        assertEquals(409,request("POST","/models",models).statusCode());
        devices.finish("pending");ok("GET","/devices/seed_01/commands/pending",null);
        ok("POST","/adapter",environment());assertEquals(1,devices.replacements);
    }
    @Test void failedTerminalJournalWriteCannotExposeAnUndurableCachedResult()throws Exception {
        ok("POST","/devices/seed_01/commands",command("durable","power_on_check"));
        devices.finish("durable");
        Path saved=directory.resolveSibling(directory.getFileName()+"-saved");
        Files.move(directory,saved);Files.writeString(directory,"not a directory");
        try {
            assertEquals(409,request("GET","/devices/seed_01/commands/durable",null).statusCode());
            assertEquals(409,request("GET","/devices/seed_01/commands/durable",null).statusCode());
        } finally {Files.delete(directory);Files.move(saved,directory);}
        assertEquals("succeeded",ok("GET","/devices/seed_01/commands/durable",null).path("status").asText());
        assertEquals("succeeded",JSON.readTree(directory.resolve("commands.json").toFile()).path("durable").path("result").path("status").asText());
        assertEquals(1,devices.executions);
    }
    @Test void physicalDutyAndIncidentSurviveSupervisorRestartAndDeviceFault()throws Exception {
        devices.add("pump_01","pump","charging");
        var constraint=ok("GET","/state?refresh=true",null).path("constraints").path("pump-energy");
        assertTrue(constraint.path("active").asBoolean());assertTrue(constraint.path("latched").asBoolean());
        var incident=constraint.path("lastIncident");
        devices.change("pump_01","fault");restart();
        var restored=ok("GET","/state?refresh=true",null).path("constraints").path("pump-energy");
        assertTrue(restored.path("active").asBoolean());assertEquals(incident,restored.path("lastIncident"));
        assertEquals(409,request("POST","/adapter",environment()).statusCode());assertEquals(0,devices.replacements);
    }
    @Test void privateDependencyLatchSurvivesWorkbenchWatchReleaseAndSupervisorRestart()throws Exception {
        var models=(ObjectNode)ok("GET","/models",null);
        models.put("definitionXml",FacilityTestModels.opticalOnly(models.path("definitionXml").asText()));
        ok("POST","/models",models);
        devices.change("seed_01","emitting");devices.add("preamp_01","preamp","wakeup");
        devices.add("synchronization_01","synchronization","repeatMode");devices.add("multipass_01","multipass","ready");states().refresh();
        var body=command("held","collimation");var context=body.putObject("admission");context.putArray("beamlines").add(1);context.put("defaultSource","seed");
        assertEquals("accepted",ok("POST","/devices/multipass_01/commands",body).path("status").asText());
        ok("POST","/dependencies/release",Map.of("id","held"));ok("POST","/dependencies/retain",List.of());
        devices.change("seed_01","fault");states().refresh();devices.change("seed_01","emitting");states().refresh();
        var journal=JSON.readTree(directory.resolve("commands.json").toFile()).path("held").path("evidence");
        assertTrue(journal.path("latched").asBoolean());assertEquals("violated",journal.path("status").asText());
        restart();devices.finish("held");
        var result=ok("GET","/devices/multipass_01/commands/held",null);
        assertEquals("violated",result.path("admissionEvidence").path("status").asText());assertEquals(1,devices.executions);
    }
    @Test void supervisorRestartCannotCertifyContinuousCommandEvidenceAcrossTheOutage()throws Exception {
        var models=(ObjectNode)ok("GET","/models",null);
        models.put("definitionXml",FacilityTestModels.opticalOnly(models.path("definitionXml").asText()));
        ok("POST","/models",models);
        devices.change("seed_01","emitting");devices.add("preamp_01","preamp","wakeup");
        devices.add("synchronization_01","synchronization","repeatMode");devices.add("multipass_01","multipass","ready");states().refresh();
        var body=command("continuous","collimation");var context=body.putObject("admission");
        context.putArray("beamlines").add(1);context.put("defaultSource","seed");
        var accepted=ok("POST","/devices/multipass_01/commands",body);
        assertEquals("valid",accepted.path("admissionEvidence").path("status").asText());
        restart();devices.finish("continuous");
        var result=ok("GET","/devices/multipass_01/commands/continuous",null);
        assertEquals("succeeded",result.path("status").asText());
        assertEquals("unknown",result.path("admissionEvidence").path("status").asText());
        assertTrue(result.path("admissionEvidence").path("latched").asBoolean());assertEquals(1,devices.executions);
    }
}
