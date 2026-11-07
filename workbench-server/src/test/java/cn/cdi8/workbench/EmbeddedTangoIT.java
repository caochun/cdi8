package cn.cdi8.workbench;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Actual process and protocol boundaries, independent of Flowable test fixtures. */
class EmbeddedTangoIT {
    static final ObjectMapper JSON=new ObjectMapper();
    static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    static final Path SIMULATOR=Path.of("device-simulator/target/device-simulator-0.1.0.jar").toAbsolutePath();
    @TempDir Path directory;
    Process facility,external;
    String base;
    List<ProcessHandle> children=List.of();

    void startFacility(String... options) throws Exception {
        int port;try(var socket=new ServerSocket(0)){port=socket.getLocalPort();}
        var args=new ArrayList<>(List.of(java(),"-jar",Path.of("facility-server/target/facility-server-0.1.0.jar").toAbsolutePath().toString(),
            "--server.port="+port,"--control.facility-store="+directory.resolve("facility"),
            "--control.simulation.jar="+SIMULATOR,"--control.simulation.directory="+directory.resolve("simulator"),
            "--debug=false","--logging.level.root=WARN"));
        args.addAll(List.of(options));base="http://127.0.0.1:"+port+"/api/facility";
        facility=new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(directory.resolve("facility.log").toFile()).start();
        long deadline=System.nanoTime()+Duration.ofSeconds(30).toNanos();
        while(System.nanoTime()<deadline&&facility.isAlive()) {
            try {if(call("GET","/connection",null).path("connected").asBoolean())return;}catch(Exception ignored){}
            Thread.sleep(100);
        }
        fail("设施后端启动失败："+Files.readString(directory.resolve("facility.log")));
    }
    static String java(){return Path.of(System.getProperty("java.home"),"bin","java").toString();}
    HttpResponse<String> response(String method,String path,Object body) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(50));
        if(body==null)builder.GET();else builder.header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        return HTTP.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode call(String method,String path,Object body) throws Exception {
        var response=response(method,path,body);assertEquals(200,response.statusCode(),response.body());return JSON.readTree(response.body());
    }
    Map<String,Object> operation(String method,String path,Object body) {
        var value=new LinkedHashMap<String,Object>();value.put("method",method);value.put("path",path);value.put("body",body);return value;
    }
    JsonNode device(String path) throws Exception {return call("POST","/adapter",operation("GET",path,null));}
    ObjectNode configuration() throws Exception {
        var request=JSON.createObjectNode();request.putArray("devices").addObject().put("id","seed_01").put("deviceBindingKey","seed")
            .put("revision","integration-test").put("xml",Files.readString(Path.of("examples/gxlf/components/seed-source-state-machine.scxml")));
        return request;
    }
    @Test void facilityOwnsOneDeviceProcessAndReplacementIsAtomic() throws Exception {
        startFacility("--control.simulation.enabled=true");
        assertEquals("jtango",call("GET","/connection",null).path("transport").asText());
        JsonNode first=call("POST","/adapter",operation("POST","/lab/environment",configuration()));
        children=facility.children().filter(ProcessHandle::isAlive).toList();assertEquals(1,children.size());
        assertEquals(first.path("pid").asLong(),children.get(0).pid());
        assertFalse(Arrays.asList(children.get(0).info().arguments().orElseThrow()).contains("--bridge"));
        String source=device("/devices/seed_01").path("source_id").asText();
        var request=Map.of("command_id","initialize","action","power_on_check","parameters",Map.of());
        assertEquals("accepted",call("POST","/devices/seed_01/commands",request).path("status").asText());
        assertEquals(409,response("POST","/adapter",operation("POST","/lab/environment",configuration())).statusCode(),"活动命令必须阻止重建");
        call("POST","/adapter",operation("POST","/devices/seed_01/simulate",Map.of("command_id","initialize","outcome","success")));
        assertEquals("succeeded",call("GET","/devices/seed_01/commands/initialize",null).path("status").asText());
        var facts=call("GET","/facts?refresh=true",null);
        assertEquals(source,facts.path("observations").path("seed_01").path("source_id").asText());
        assertTrue(facts.path("observations").path("seed_01").path("observed").asBoolean());
        assertEquals("powerOnChecked",device("/devices/seed_01").path("state_id").asText());

        var invalid=configuration();((ObjectNode)invalid.path("devices").get(0)).put("xml","<invalid/>");
        assertTrue(response("POST","/adapter",operation("POST","/lab/environment",invalid)).statusCode()>=400);
        assertEquals(first.path("id"),device("/lab/environment").path("id"));
        assertEquals(source,device("/devices/seed_01").path("source_id").asText());
        assertTrue(children.get(0).isAlive());
        var second=call("POST","/adapter",operation("POST","/lab/environment",configuration()));
        assertNotEquals(first.path("id"),second.path("id"));
        assertFalse(children.get(0).isAlive());
        children=facility.children().filter(ProcessHandle::isAlive).toList();assertEquals(1,children.size());
        stopFacility();
        assertFalse(children.get(0).isAlive(),"设施停止应回收其模拟设备");
    }

    @Test void externalDeviceBindingNeedsNoSimulatorLifecycle() throws Exception {
        Path ready=directory.resolve("device-ready.json");
        external=new ProcessBuilder(java(),"-jar",SIMULATOR.toString(),"--port","0","--ready-file",ready.toString(),
            "--db",directory.resolve("external.sqlite3").toString()).redirectErrorStream(true).redirectOutput(directory.resolve("device.log").toFile()).start();
        long deadline=System.nanoTime()+Duration.ofSeconds(25).toNanos();
        while(!Files.exists(ready)&&external.isAlive()&&System.nanoTime()<deadline)Thread.sleep(100);
        assertTrue(Files.exists(ready),"外部设备未就绪");
        String address=JSON.readTree(ready.toFile()).path("address").asText();
        var bindings=JSON.createObjectNode();bindings.putObject("seed_01").put("deviceBindingKey","source-adapter").put("address",address);
        Path file=directory.resolve("bindings.json");JSON.writeValue(file.toFile(),bindings);
        startFacility("--control.tango-bindings="+file,"--control.simulation.enabled=false","--control.simulation.jar=/missing/simulator.jar");
        assertEquals(0,facility.children().filter(ProcessHandle::isAlive).count());
        var models=(ObjectNode)call("GET","/models",null);
        models.put("bindingXml",models.path("bindingXml").asText().replace("deviceBindingKey=\"seed\"","deviceBindingKey=\"source-adapter\""));
        call("POST","/models",models);
        var catalog=device("/devices").get(0);
        assertEquals("source-adapter",catalog.path("deviceBindingKey").asText());
        assertFalse(catalog.has("component"));
        assertEquals("光纤种子源组件",catalog.path("snapshot").path("componentLabel").asText());
        assertFalse(catalog.path("snapshot").has("component"));
        assertFalse(device("/lab/environment").path("managed").asBoolean());
        assertFalse(device("/devices/seed_01").path("source_id").asText().isBlank());
        assertTrue(call("GET","/facts?refresh=true",null).path("observations").path("seed_01").path("observed").asBoolean());
        var deviceFacts=call("GET","/facts?refresh=true",null).path("deviceFacts").path("seed_01");
        assertFalse(deviceFacts.isEmpty());
        for(var fact:deviceFacts) {
            assertEquals("seed",fact.path("componentType").asText());assertFalse(fact.has("component"));
        }
        assertEquals(409,response("POST","/adapter",operation("POST","/lab/environment",configuration())).statusCode());
        stopFacility();assertTrue(external.isAlive(),"设施后端不拥有外部设备进程");
    }
    void stopFacility() throws Exception {
        if(facility==null)return;
        facility.destroy();
        if(!facility.waitFor(12,TimeUnit.SECONDS)){facility.destroyForcibly();facility.waitFor(5,TimeUnit.SECONDS);}
        facility=null;
        for(var child:children)if(child.isAlive())child.onExit().get(8,TimeUnit.SECONDS);
    }
    @AfterEach void cleanup() throws Exception {
        try {stopFacility();}
        finally {
            for(var child:children)if(child.isAlive())child.destroyForcibly();
            if(external!=null){external.destroy();if(!external.waitFor(8,TimeUnit.SECONDS))external.destroyForcibly();}
        }
    }
}
