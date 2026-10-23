package cn.cdi8.workbench;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit integration test: packaged Java services only, isolated models, SQLite and Flowable data.
 * Run after mvn package: mvn -pl server -Dexec.skip=true -Dtest=JavaWorkbenchE2eIT test
 */
class JavaWorkbenchE2eIT {
    @TempDir(cleanup=org.junit.jupiter.api.io.CleanupMode.ON_SUCCESS) Path temporary;
    final ObjectMapper json=new ObjectMapper();
    final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    final Path root=Path.of("").toAbsolutePath();
    final List<Process> processes=new ArrayList<>();
    String bridge,base;

    @Test void entireWorkbenchRunsWithJavaServices() throws Exception {
        try {
            startServices();
            assertEquals("java",get(bridge,"/lab/environment").path("runtime").asText());
            assertEquals("empty",get(bridge,"/lab/environment").path("status").asText());
            assertTrue(get(bridge,"/devices").isEmpty());
            String xml=Files.readString(root.resolve("examples/minimal/component.scxml"));
            String flow=Files.readString(root.resolve("examples/minimal/process.bpmn20.xml"))
                .replace("<bpmn:endEvent id=\"end\" />","<bpmn:receiveTask id=\"await_alignment\" lab:condition=\"alignment_ready\"/><bpmn:endEvent id=\"end\" />")
                .replace("sourceRef=\"activate\" targetRef=\"end\"","sourceRef=\"activate\" targetRef=\"await_alignment\"")
                .replace("</bpmn:process>","<bpmn:sequenceFlow id=\"f3\" sourceRef=\"await_alignment\" targetRef=\"end\" /></bpmn:process>");
            trialsAndValidation(xml);
            ObjectNode component=publish("java-component.scxml","scxml",xml),process=publish("java-process.bpmn20.xml","bpmn",flow);
            ObjectNode config=configuration(component,process,false);
            config=(ObjectNode)post(base,"/api/workbench/environment",config);
            assertEquals(3,get(bridge,"/devices").size());
            long firstPid=config.path("environment").path("pid").asLong();
            String firstEnvironment=config.path("environment").path("id").asText();
            assertEquals("idle",get(bridge,"/devices/demo_01").path("state_id").asText());
            assertEquals(component.path("revision").asText(),get(bridge,"/devices/demo_02").path("model_revision").asText());

            // Invalid replacement must preserve the live generation and its device state.
            ObjectNode invalid=json.createObjectNode();invalid.putArray("devices").addObject().put("id","bad_01")
                .put("component","bad").put("revision","bad").put("xml",xml.replace("initial=\"operating\"","initial=\"missing\""));
            assertEquals(400,exchange(bridge,"/lab/environment",invalid).statusCode());
            assertEquals(firstEnvironment,get(bridge,"/lab/environment").path("id").asText());
            assertTrue(ProcessHandle.of(firstPid).orElseThrow().isAlive());

            JsonNode run=post(base,"/api/workbench/runs",config);String runId=run.path("id").asText();
            assertEquals(409,exchange(base,"/api/workbench/environment",config).statusCode());
            waitFor(()->get(bridge,"/devices/demo_01").hasNonNull("active_command_id")&&get(bridge,"/devices/demo_02").hasNonNull("active_command_id"),15);
            for(String device:List.of("demo_01","demo_02")) {
                String command=get(bridge,"/devices/"+device).path("active_command_id").asText();
                JsonNode feedback=json.valueToTree(Map.of("command_id",command,"outcome","success"));
                JsonNode result=post(bridge,"/devices/"+device+"/simulate",feedback);
                assertEquals(result,post(bridge,"/devices/"+device+"/simulate",feedback));
                assertEquals("succeeded",get(bridge,"/devices/"+device+"/commands/"+command).path("status").asText());
                assertFalse(get(bridge,"/devices/"+device+"/history").isEmpty());
            }
            waitFor(()->active(get(base,"/api/runs/"+runId),"await_alignment"),15);
            assertEquals("RUNNING",get(base,"/api/runs/"+runId).path("outcome").asText());
            post(base,"/api/devices/context_01/conditions",json.valueToTree(Map.of("alignment_ready",true)));
            waitFor(()->get(base,"/api/runs/"+runId).hasNonNull("ended"),15);
            JsonNode ended=get(base,"/api/runs/"+runId);
            assertEquals("SUCCEEDED",ended.path("outcome").asText());
            assertEquals(2,ended.path("commands").size());
            assertEquals(3,ended.path("initialDeviceSnapshots").size());
            assertFalse(get(base,"/api/workbench/runs/"+runId+"/trace").isEmpty());

            // Publish changed XML and rebuild; both actual snapshots and rendered revision use the new version.
            post(base,"/api/workbench/models",json.valueToTree(Map.of("id",component.path("model").asText(),"name","java-component.scxml","kind","scxml","xml",xml.replace("idle","waiting"))));
            String revision=post(base,"/api/workbench/models/"+component.path("model").asText()+"/versions",json.createObjectNode()).path("id").asText();
            ((ObjectNode)config.path("devices").get(0)).put("revision",revision).put("autoComplete",true);
            ((ObjectNode)config.path("conditions")).put("alignment_ready",true);
            config=(ObjectNode)post(base,"/api/workbench/environment",config);
            assertNotEquals(firstEnvironment,config.path("environment").path("id").asText());
            assertFalse(ProcessHandle.of(firstPid).map(ProcessHandle::isAlive).orElse(false));
            assertEquals("waiting",get(bridge,"/devices/demo_01").path("state_id").asText());
            assertEquals(revision,get(bridge,"/devices/demo_01").path("model_revision").asText());
            JsonNode automatic=post(base,"/api/workbench/runs",config);String automaticId=automatic.path("id").asText();
            waitFor(()->get(base,"/api/runs/"+automaticId).hasNonNull("ended"),20);
            assertEquals("SUCCEEDED",get(base,"/api/runs/"+automaticId).path("outcome").asText());
            assertEquals(component.path("revision").asText(),get(base,"/api/runs/"+runId).path("initialDeviceSnapshots").path("demo_01").path("snapshot").path("model_revision").asText());

            // Device crash is visible and can be recovered by rebuilding with a fresh generation.
            long deadPid=config.path("environment").path("pid").asLong();
            ProcessHandle.of(deadPid).orElseThrow().destroyForcibly();
            waitFor(()->"failed".equals(get(bridge,"/lab/environment").path("status").asText()),10);
            waitFor(()->get(bridge,"/devices").findValues("connected").stream().noneMatch(JsonNode::asBoolean),15);
            config=(ObjectNode)post(base,"/api/workbench/environment",config);
            assertNotEquals(deadPid,config.path("environment").path("pid").asLong());
            assertEquals("waiting",get(bridge,"/devices/demo_02").path("state_id").asText());
            ((ObjectNode)config.path("devices").get(0)).put("autoComplete",false);
            ((ObjectNode)config.path("conditions")).put("alignment_ready",false);
            config=(ObjectNode)post(base,"/api/workbench/environment",config);
            String stoppedId=post(base,"/api/workbench/runs",config).path("id").asText();
            assertTrue(post(base,"/api/runs/"+stoppedId+"/stop",json.createObjectNode()).hasNonNull("ended"));
            qualificationLifecycle();
            gxlfLifecycle();

            // Stopping the bridge also stops its device JVM; restart never pretends the old environment is ready.
            long devicePid=get(bridge,"/lab/environment").path("pid").asLong();
            terminate(processes.get(0));
            waitFor(()->!ProcessHandle.of(devicePid).map(ProcessHandle::isAlive).orElse(false),10);
            startBridge();
            assertEquals("empty",get(bridge,"/lab/environment").path("status").asText());
            assertTrue(get(bridge,"/devices").isEmpty());
            waitFor(()->get(base,"/api/connection").path("connected").asBoolean(),10);
            assertEquals(409,exchange(base,"/api/workbench/runs",config).statusCode());
            post(base,"/api/workbench/environment",config);
            assertEquals("waiting",get(bridge,"/devices/demo_01").path("state_id").asText());
            ObjectNode contextOnly=json.createObjectNode();contextOnly.putArray("devices");contextOnly.putObject("conditions").put("alignment_ready",true);
            post(bridge,"/lab/environment",contextOnly);
            assertEquals(1,get(bridge,"/devices").size());
            assertTrue(get(bridge,"/devices/context_01").path("conditions").path("alignment_ready").asBoolean());
            System.out.println("PASS Java-only: validation / isolated trials / two Tango devices / pinned versions / manual and automatic feedback / conditions / Flowable / trace / rebuild / crash / restart / GXLF lifecycle");
        } finally {
            for(int i=processes.size()-1;i>=0;i--)terminate(processes.get(i));
        }
    }

    void trialsAndValidation(String xml) throws Exception {
        JsonNode validation=post(base,"/api/workbench/validate",json.valueToTree(Map.of("kind","scxml","xml",xml)));
        assertTrue(validation.path("valid").asBoolean());
        assertFalse(post(base,"/api/workbench/validate",json.valueToTree(Map.of("kind","scxml","xml",xml.replace("initial=\"operating\"","initial=\"missing\"")))).path("valid").asBoolean());
        JsonNode trial=post(base,"/api/workbench/trials",json.valueToTree(Map.of("xml",xml)));String id=trial.path("id").asText();
        assertEquals("apache-commons-scxml2",trial.path("snapshot").path("engine").asText());
        for(String outcome:List.of("failure","success")) {
            trial=post(base,"/api/workbench/trials/"+id+"/commands",json.valueToTree(Map.of("action","activate")));
            String command=trial.path("snapshot").path("active_command_id").asText();
            trial=post(base,"/api/workbench/trials/"+id+"/simulate",json.valueToTree(Map.of("command_id",command,"outcome",outcome)));
            assertEquals(outcome.equals("success")?"active":"idle",trial.path("snapshot").path("state_id").asText());
        }
        assertEquals(4,get(base,"/api/workbench/trials/"+id).path("history").size());
    }
    ObjectNode publish(String name,String kind,String xml) {
        JsonNode model=post(base,"/api/workbench/models",json.valueToTree(Map.of("name",name,"kind",kind,"xml",xml)));
        JsonNode revision=post(base,"/api/workbench/models/"+model.path("id").asText()+"/versions",json.createObjectNode());
        return json.createObjectNode().put("model",model.path("id").asText()).put("revision",revision.path("id").asText());
    }
    ObjectNode configuration(ObjectNode component,ObjectNode process,boolean auto) {
        ObjectNode config=json.createObjectNode().put("entry",process.path("model").asText()).put("autoComplete",false);
        config.putArray("processes").add(process);
        config.putArray("devices").add(component.deepCopy().put("component","demo").put("count",2).put("autoComplete",auto).put("delayMs",50));
        config.putObject("conditions").put("alignment_ready",false);
        config.putObject("variables").put("commandTimeout","PT30S").put("workflowTimeout","PT120S");return config;
    }
    void gxlfLifecycle() throws Exception {
        Map<String,JsonNode> models=new HashMap<>();for(JsonNode model:get(base,"/api/workbench/models"))models.put(model.path("id").asText(),model);
        ObjectNode config=json.createObjectNode().put("entry","emission_task_lifecycle").put("autoComplete",false);
        ArrayNode selected=config.putArray("processes");
        for(String key:List.of("component_initialization","parameter_dispatch","optical_frontend_preparation","formal_emission","post_emission_processing","emission_task_lifecycle")) {
            JsonNode revisions=models.get(key).path("revisions");selected.addObject().put("model",key).put("revision",revisions.get(revisions.size()-1).path("id").asText());
        }
        ArrayNode devices=config.putArray("devices");
        for(var entry:Map.of("seed-source-state-machine","seed","shg-injection-state-machine","shg","preamp-state-machine","preamp","multipass-state-machine","multipass").entrySet()) {
            JsonNode revisions=models.get(entry.getKey()).path("revisions");
            devices.addObject().put("model",entry.getKey()).put("revision",revisions.get(revisions.size()-1).path("id").asText())
                .put("component",entry.getValue()).put("count",1).put("autoComplete",false).put("delayMs",50);
        }
        config.putObject("bindings").putArray("sourceTargets").add("seed_01").add("shg_01");
        config.putObject("variables").put("commandTimeout","PT30S").put("workflowTimeout","PT120S");
        config=(ObjectNode)post(base,"/api/workbench/environment",config);
        JsonNode run=post(base,"/api/workbench/runs",config);String id=run.path("id").asText();
        Set<String> completed=new HashSet<>();boolean permitted=false;
        long deadline=System.nanoTime()+Duration.ofSeconds(90).toNanos();
        while(System.nanoTime()<deadline) {
            JsonNode state=get(base,"/api/runs/"+id);
            if(active(state,"wait_safety")&&!permitted) {
                Thread.sleep(350);assertTrue(active(get(base,"/api/runs/"+id),"wait_safety"));
                ObjectNode conditions=json.createObjectNode();get(bridge,"/devices/context_01").path("conditions").fieldNames().forEachRemaining(key->conditions.put(key,true));
                post(base,"/api/devices/context_01/conditions",conditions);permitted=true;
            }
            for(JsonNode device:get(bridge,"/devices")) {
                JsonNode command=device.path("snapshot").path("active_command_id");
                if(!command.isNull()&&!command.isMissingNode()&&completed.add(command.asText())) {
                    ObjectNode feedback=json.createObjectNode().put("command_id",command.asText()).put("outcome","success");
                    String path="/devices/"+device.path("id").asText()+"/simulate";
                    JsonNode result=post(bridge,path,feedback);assertEquals("succeeded",result.path("status").asText());assertEquals(result,post(bridge,path,feedback));
                }
            }
            if(state.hasNonNull("ended")) {
                assertEquals("SUCCEEDED",state.path("outcome").asText(),state.toString());
                assertTrue(permitted,"Safety permit must have been explicitly confirmed");
                assertEquals(commandCount(state),completed.size(),"Every Flowable command must receive exactly one simulated feedback");
                assertFalse(completed.isEmpty());
                for(String stage:List.of("initialize","dispatch","preparation","formal_emission","post_emission_processing"))
                    assertTrue(hasActivity(state,stage),"GXLF stage did not execute: "+stage+" in "+state);
                for(String device:List.of("seed_01","shg_01","preamp_01","multipass_01"))
                    assertFalse(get(bridge,"/devices/"+device+"/history").isEmpty(),"No Tango command history for "+device);
                assertEquals("emitting",get(bridge,"/devices/seed_01").path("outer_state").asText());
                assertEquals("emitting",get(bridge,"/devices/shg_01").path("outer_state").asText());
                assertEquals("standbyReady",get(bridge,"/devices/preamp_01").path("state_id").asText());
                assertEquals("collimationReady",get(bridge,"/devices/multipass_01").path("state_id").asText());
                System.out.println("PASS Java GXLF lifecycle, two light sources, "+completed.size()+" commands");return;
            }
            Thread.sleep(80);
        }
        fail("GXLF timed out: "+get(base,"/api/runs/"+id));
    }
    void qualificationLifecycle() throws Exception {
        Map<String,JsonNode> models=new HashMap<>();
        for(JsonNode model:get(base,"/api/workbench/models"))models.put(model.path("id").asText(),model);
        ObjectNode config=initializationConfiguration(models);
        config=(ObjectNode)post(base,"/api/workbench/environment",config);
        JsonNode run=post(base,"/api/workbench/runs",config);String failedId=run.path("id").asText();

        waitFor(()->get(bridge,"/devices/seed_01").hasNonNull("active_command_id"),15);
        JsonNode seed=get(bridge,"/devices/seed_01");
        post(bridge,"/devices/seed_01/simulate",json.createObjectNode()
            .put("command_id",seed.path("active_command_id").asText()).put("outcome","success"));
        waitFor(()->"powerOnChecked".equals(get(bridge,"/devices/seed_01").path("state_id").asText()),10);
        waitFor(()->get(base,"/api/runs/"+failedId).path("qualificationRegistry").asText()
            .contains("powerQualified|seed_01"),10);
        post(bridge,"/devices/seed_01/events",json.createObjectNode().put("event_id","qualification-e2e-fault")
            .put("event","fault_confirmed").put("observed_outer_state","异常"));

        waitFor(()->get(base,"/api/runs/"+failedId).hasNonNull("ended"),15);
        JsonNode failed=get(base,"/api/runs/"+failedId);
        assertEquals("FAILED",failed.path("outcome").asText(),failed.toString());
        assertTrue(failed.path("failureReason").asText().contains("资格"),failed.toString());
        assertTrue(failed.path("activities").toString().contains("qualification_boundary"),failed.toString());
        assertTrue(failed.path("commands").findValuesAsText("commandAction").stream()
            .noneMatch(action->action.contains("function_check")),failed.path("commands").toString());
        assertEquals("fault",get(bridge,"/devices/seed_01").path("outer_state").asText());

        // A fresh generation proves that verified power and function claims
        // allow the real Flowable initialization process to complete.
        config=(ObjectNode)post(base,"/api/workbench/environment",config);
        run=post(base,"/api/workbench/runs",config);String successId=run.path("id").asText();
        Set<String> completed=new HashSet<>();long deadline=System.nanoTime()+Duration.ofSeconds(90).toNanos();
        while(System.nanoTime()<deadline) {
            JsonNode state=get(base,"/api/runs/"+successId);
            for(JsonNode device:get(bridge,"/devices")) {
                JsonNode command=device.path("snapshot").path("active_command_id");
                if(!command.isNull()&&!command.isMissingNode()&&completed.add(command.asText())) {
                    String deviceId=device.path("id").asText(),commandId=command.asText();
                    post(bridge,"/devices/"+deviceId+"/simulate",json.createObjectNode()
                        .put("command_id",commandId).put("outcome","success"));
                }
            }
            if(state.hasNonNull("ended")) {
                assertEquals("SUCCEEDED",state.path("outcome").asText(),state.toString());
                assertEquals(8,commandCount(state),"four power checks and four function checks");
                assertTrue(state.path("commands").findValuesAsText("commandAction").stream()
                    .filter("power_on_check"::equals).count()==4,state.path("commands").toString());
                assertTrue(state.path("commands").findValuesAsText("commandAction").stream()
                    .filter("function_check"::equals).count()==4,state.path("commands").toString());
                System.out.println("PASS Java qualification E2E: invalidation blocks function checks; all four components complete initialization");
                return;
            }
            Thread.sleep(80);
        }
        fail("Qualification E2E timed out: "+get(base,"/api/runs/"+successId));
    }
    ObjectNode initializationConfiguration(Map<String,JsonNode> models) {
        ObjectNode config=json.createObjectNode().put("entry","component_initialization").put("autoComplete",false);
        config.putArray("processes").addObject().put("model","component_initialization")
            .put("revision",latestRevision(models.get("component_initialization")));
        ArrayNode devices=config.putArray("devices");
        for(var entry:List.of(Map.entry("seed-source-state-machine","seed"),Map.entry("shg-injection-state-machine","shg"),
                Map.entry("preamp-state-machine","preamp"),Map.entry("multipass-state-machine","multipass"))) {
            devices.addObject().put("model",entry.getKey()).put("revision",latestRevision(models.get(entry.getKey())))
                .put("component",entry.getValue()).put("count",1).put("autoComplete",false).put("delayMs",50);
        }
        config.putObject("variables").put("commandTimeout","PT30S").put("workflowTimeout","PT120S");
        return config;
    }
    String latestRevision(JsonNode model) {
        JsonNode revisions=model.path("revisions");return revisions.get(revisions.size()-1).path("id").asText();
    }
    boolean active(JsonNode run,String activity) {
        for(JsonNode id:run.path("activeActivities"))if(activity.equals(id.asText()))return true;
        for(JsonNode child:run.path("children"))if(active(child,activity))return true;return false;
    }
    boolean hasActivity(JsonNode run,String activity) {
        for(JsonNode item:run.path("activities"))if(activity.equals(item.path("id").asText()))return true;
        for(JsonNode child:run.path("children"))if(hasActivity(child,activity))return true;return false;
    }
    int commandCount(JsonNode run) {
        int count=run.path("commands").size();for(JsonNode child:run.path("children"))count+=commandCount(child);return count;
    }
    void startServices() throws Exception {
        startBridge();
        try(var socket=new ServerSocket(0)){base="http://127.0.0.1:"+socket.getLocalPort();}
        start("workbench",root.resolve("server/target/workbench-server-0.1.0.jar"),List.of(
            "--server.port="+URI.create(base).getPort(),"--control.tango-url="+bridge,
            "--control.workbench-directory="+temporary.resolve("workbench"),"--spring.datasource.url=jdbc:h2:mem:java-e2e"));
        waitFor(()->{try{return get(base,"/api/connection").path("connected").asBoolean();}catch(Exception ex){return false;}},60);
        assertEquals(200,exchange(base,"/",null).statusCode());
    }
    void startBridge() throws Exception {
        Path ready=temporary.resolve("bridge-ready-"+processes.size()+".json");
        start("bridge-"+processes.size(),root.resolve("device-simulator/target/device-simulator-0.1.0.jar"),
            List.of("--bridge","--port",bridge==null?"0":Integer.toString(URI.create(bridge).getPort()),"--data-dir",temporary.resolve("simulator").toString(),"--ready-file",ready.toString()));
        waitFor(()->Files.exists(ready),20);
        bridge=json.readTree(ready.toFile()).path("url").asText();
    }
    void start(String name,Path jar,List<String> arguments) throws IOException {
        assertTrue(Files.exists(jar),"先执行 mvn package："+jar);
        List<String> command=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-jar",jar.toString()));command.addAll(arguments);
        processes.add(new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).redirectOutput(temporary.resolve(name+".log").toFile()).start());
    }
    void terminate(Process process) throws Exception {
        if(!process.isAlive())return;List<ProcessHandle> children=process.descendants().toList();process.destroy();
        if(!process.waitFor(8,TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);}
        for(ProcessHandle child:children)if(child.isAlive())child.destroyForcibly();
    }
    void waitFor(BooleanSupplier condition,int seconds) throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(seconds).toNanos();
        while(System.nanoTime()<deadline){if(condition.getAsBoolean())return;Thread.sleep(100);}
        fail("等待条件超时；Java 服务日志："+temporary);
    }
    JsonNode get(String origin,String path){return request(origin,path,null);}
    JsonNode post(String origin,String path,JsonNode payload){return request(origin,path,payload);}
    JsonNode request(String origin,String path,JsonNode payload) {
        try {
            var response=exchange(origin,path,payload);
            assertEquals(200,response.statusCode(),path+": "+response.body());return json.readTree(response.body());
        }catch(Exception ex){throw new IllegalStateException(path,ex);}
    }
    HttpResponse<String> exchange(String origin,String path,JsonNode payload) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(origin+path)).timeout(Duration.ofSeconds(60));
        if(payload==null)builder.GET();else builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(payload.toString()));
        return http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
}
