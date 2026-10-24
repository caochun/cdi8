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
            deviceAdmissionFromScxml(xml);
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
            systemStateLifecycle();
            fullSystemInitialization();
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

    void deviceAdmissionFromScxml(String xml) throws Exception {
        String guarded=xml.replace("<datamodel>","<datamodel><data id=\"quota\" expr=\"1\"/>")
            .replace("<transition event=\"activate.request\" />",
                "<transition event=\"activate.request\" cond=\"quota &gt; 0\"><assign location=\"quota\" expr=\"quota - 1\"/></transition>");
        ObjectNode environment=json.createObjectNode();
        environment.putArray("devices").addObject().put("id","guarded_01").put("component","demo")
            .put("revision","scxml-admission-e2e").put("xml",guarded).put("autoComplete",false);
        post(bridge,"/lab/environment",environment);
        String endpoint="/devices/guarded_01";
        for(int i=0;i<3;i++) assertTrue(get(bridge,endpoint).path("action_contracts").path("activate").path("ready").asBoolean());
        ObjectNode command=json.createObjectNode().put("command_id","scxml-quota-first").put("action","activate");
        JsonNode accepted=post(bridge,endpoint+"/commands",command);
        assertEquals("accepted",accepted.path("status").asText());
        assertEquals("idle",accepted.path("snapshot").path("state_id").asText());
        post(bridge,endpoint+"/simulate",json.createObjectNode().put("command_id","scxml-quota-first").put("outcome","failure"));
        JsonNode before=get(bridge,endpoint);
        assertEquals("idle",before.path("state_id").asText());
        assertFalse(before.path("action_contracts").path("activate").path("ready").asBoolean());
        command.put("command_id","scxml-quota-rejected");
        JsonNode refused=post(bridge,endpoint+"/commands",command);
        assertEquals("rejected",refused.path("status").asText());
        assertEquals(before,get(bridge,endpoint));
        assertEquals(refused,get(bridge,endpoint+"/commands/scxml-quota-rejected"));
        System.out.println("PASS Tango SCXML admission: readonly readiness, targetless acceptance, false guard rejects without state change");
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
            assertEquals("accepted",trial.path("commandResult").path("status").asText());
            assertEquals(command,trial.path("commandResult").path("command_id").asText());
            assertTrue(trial.path("availableActions").isEmpty());
            JsonNode refused=post(base,"/api/workbench/trials/"+id+"/commands",json.valueToTree(Map.of("action","activate")));
            assertEquals("rejected",refused.path("commandResult").path("status").asText());
            assertEquals(command,refused.path("snapshot").path("active_command_id").asText());
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
    void systemStateLifecycle() throws Exception {
        Map<String,JsonNode> models=new HashMap<>();
        for(JsonNode model:get(base,"/api/workbench/models"))models.put(model.path("id").asText(),model);
        ObjectNode config=initializationConfiguration(models);
        config=(ObjectNode)post(base,"/api/workbench/environment",config);
        // The Tango device must enforce the declared condition, even when its
        // SCXML permits the action and the caller's earlier read was different.
        ObjectNode guarded=json.createObjectNode().put("command_id","entry-condition-e2e").put("action","power_on_check");
        ObjectNode guard=guarded.putObject("parameters").putObject("entry_condition").put("states","powerOnChecked").put("idle",true);
        JsonNode before=get(bridge,"/devices/seed_01");
        assertTrue(before.path("supports_entry_condition").asBoolean());
        assertTrue(before.path("supports_action_contract").asBoolean());
        assertFalse(before.path("action_contracts").path("function_check").path("ready").asBoolean());
        ObjectNode direct=json.createObjectNode().put("command_id","intrinsic-function-e2e").put("action","function_check");
        JsonNode refused=post(bridge,"/devices/seed_01/commands",direct);
        assertEquals("rejected",refused.path("status").asText(),"Direct Tango calls must obey intrinsic device rules without BPMN guards");
        assertEquals(before,get(bridge,"/devices/seed_01"));
        assertEquals("rejected",post(bridge,"/devices/seed_01/commands",guarded).path("status").asText());
        assertEquals(before.path("state_id"),get(bridge,"/devices/seed_01").path("state_id"));
        assertFalse(get(bridge,"/devices/seed_01").hasNonNull("active_command_id"));
        guard.put("states","statusUnknown");
        guarded.put("command_id","entry-condition-e2e-accepted");
        assertEquals("accepted",post(bridge,"/devices/seed_01/commands",guarded).path("status").asText());
        JsonNode guardedCompletion=post(bridge,"/devices/seed_01/simulate",json.createObjectNode()
            .put("command_id","entry-condition-e2e-accepted").put("outcome","success"));
        assertTrue(get(bridge,"/devices/seed_01").path("action_contracts").path("function_check").path("ready").asBoolean());
        assertEquals(refused,post(bridge,"/devices/seed_01/commands",direct),"A rejected command must stay rejected after device readiness changes");
        assertEquals(guardedCompletion,post(bridge,"/devices/seed_01/commands",guarded),"Replay must return the original result after state advancement");
        config=(ObjectNode)post(base,"/api/workbench/environment",config);
        for(String device:List.of("seed_01","shg_01","preamp_01","multipass_01"))
            waitFor(()->{
                JsonNode snapshot=get(bridge,"/devices/"+device);
                return snapshot.path("source_id").isTextual()&&!snapshot.path("source_id").asText().isBlank();
            },15);
        JsonNode run=post(base,"/api/workbench/runs",config);String failedId=run.path("id").asText();

        waitFor(()->get(bridge,"/devices/seed_01").hasNonNull("active_command_id"),15);
        JsonNode seed=get(bridge,"/devices/seed_01");
        post(bridge,"/devices/seed_01/simulate",json.createObjectNode()
            .put("command_id",seed.path("active_command_id").asText()).put("outcome","success"));
        waitFor(()->"powerOnChecked".equals(get(bridge,"/devices/seed_01").path("state_id").asText()),10);
        waitFor(()->"powerOnChecked".equals(get(bridge,"/devices/seed_01").path("state_id").asText()),10);
        post(bridge,"/devices/seed_01/events",json.createObjectNode().put("event_id","system-state-e2e-transient-fault")
            .put("event","fault_confirmed").put("observed_outer_state","异常"));
        post(bridge,"/devices/seed_01/events",json.createObjectNode().put("event_id","system-state-e2e-recovery")
            .put("event","recovery_confirmed").put("observed_outer_state","未就绪"));
        post(bridge,"/devices/seed_01/commands",json.createObjectNode()
            .put("command_id","system-state-e2e-recheck").put("action","power_on_check"));
        post(bridge,"/devices/seed_01/simulate",json.createObjectNode()
            .put("command_id","system-state-e2e-recheck").put("outcome","success"));
        waitFor(()->"powerOnChecked".equals(get(bridge,"/devices/seed_01").path("state_id").asText()),10);

        waitFor(()->get(base,"/api/runs/"+failedId).hasNonNull("ended"),15);
        JsonNode failed=get(base,"/api/runs/"+failedId);
        assertEquals("FAILED",failed.path("outcome").asText(),failed.toString());
        assertFalse(failed.path("failureReason").asText().isBlank(),failed.toString());
        assertEquals("powerOnChecked",get(bridge,"/devices/seed_01").path("state_id").asText(),
            "The device recovered to a power-qualified state before Flowable inspected the event batch");
        assertTrue(failed.path("commands").findValuesAsText("commandAction").stream()
            .noneMatch(action->action.contains("function_check")),failed.path("commands").toString());
        assertFalse(failed.path("unresolvedDeviceActions").isEmpty(),"Workflow failure cannot claim the other active device actions stopped");

        // A live Tango device loss makes established evidence unknown and must
        // interrupt the run before any function-check command is dispatched.
        config=(ObjectNode)post(base,"/api/workbench/environment",config);
        run=post(base,"/api/workbench/runs",config);String disconnectedId=run.path("id").asText();
        waitFor(()->get(bridge,"/devices/seed_01").hasNonNull("active_command_id"),15);
        seed=get(bridge,"/devices/seed_01");
        post(bridge,"/devices/seed_01/simulate",json.createObjectNode()
            .put("command_id",seed.path("active_command_id").asText()).put("outcome","success"));
        waitFor(()->"powerOnChecked".equals(get(bridge,"/devices/seed_01").path("state_id").asText()),15);
        long devicePid=get(bridge,"/lab/environment").path("pid").asLong();
        ProcessHandle.of(devicePid).orElseThrow().destroyForcibly();
        waitFor(()->!ProcessHandle.of(devicePid).map(ProcessHandle::isAlive).orElse(false),10);
        waitFor(()->get(base,"/api/runs/"+disconnectedId).hasNonNull("ended"),20);
        JsonNode disconnected=get(base,"/api/runs/"+disconnectedId);
        assertEquals("FAILED",disconnected.path("outcome").asText(),disconnected.toString());
        assertFalse(disconnected.path("failureReason").asText().isBlank(),disconnected.toString());
        assertTrue(disconnected.path("commands").findValuesAsText("commandAction").stream()
            .noneMatch(action->action.contains("function_check")),disconnected.path("commands").toString());

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
                assertTrue(state.path("unresolvedDeviceActions").isEmpty());
                for(JsonNode device:get(bridge,"/devices")) if(!"context".equals(device.path("component").asText())) {
                    String deviceId=device.path("id").asText();
                    JsonNode last=get(bridge,"/devices/"+deviceId);
                    String commandId=last.path("last_result").path("command_id").asText();
                    assertFalse(get(bridge,"/devices/"+deviceId+"/commands/"+commandId)
                        .path("parameters").has("entry_condition"),"BPMN must reference the device contract without duplicating intrinsic states: "+deviceId);
                }
                for(JsonNode command:state.path("commands")) {
                    assertTrue(command.path("deviceActionContract").asBoolean());
                    assertTrue(command.path("dispatchAttempted").asBoolean());
                }
                assertTrue(state.path("commands").findValuesAsText("commandAction").stream()
                    .filter("power_on_check"::equals).count()==4,state.path("commands").toString());
                assertTrue(state.path("commands").findValuesAsText("commandAction").stream()
                    .filter("function_check"::equals).count()==4,state.path("commands").toString());
                assertEquals("initialized",systemState(state).path("state").asText());
                System.out.println("PASS Java system-state E2E: invalidation blocks function checks; all four components complete initialization");
                return;
            }
            Thread.sleep(80);
        }
        fail("System-state E2E timed out: "+get(base,"/api/runs/"+successId));
    }
    void fullSystemInitialization() throws Exception {
        Map<String,JsonNode> models=new HashMap<>();
        for(JsonNode model:get(base,"/api/workbench/models"))models.put(model.path("id").asText(),model);
        ObjectNode config=json.createObjectNode().put("entry","gxlf_system_initialization").put("autoComplete",false);
        config.putArray("processes").addObject().put("model","gxlf_system_initialization")
            .put("revision",latestRevision(models.get("gxlf_system_initialization")));
        ArrayNode devices=config.putArray("devices");
        for(String component:List.of("seed","shg","preamp","multipass","pump","measurement","frequency-conversion",
                "target-alignment","vacuum","diagnostics","synchronization","switch-driver","cooling","control-environment")) {
            String model=switch(component){case "seed"->"seed-source-state-machine";case "shg"->"shg-injection-state-machine";default->component+"-state-machine";};
            devices.addObject().put("model",model).put("revision",latestRevision(models.get(model)))
                .put("component",component).put("count",1).put("autoComplete",false).put("delayMs",50);
        }
        config.putObject("bindings").putArray("sourceTargets").add("seed_01").add("shg_01");
        config.putObject("variables").put("commandTimeout","PT30S").put("workflowTimeout","PT120S");

        // Missing a required component is rejected before starting any workflow.
        ObjectNode missing=config.deepCopy();((ArrayNode)missing.path("devices")).remove(13);
        missing=(ObjectNode)post(base,"/api/workbench/environment",missing);
        var rejected=exchange(base,"/api/workbench/runs",missing);
        assertEquals(409,rejected.statusCode(),rejected.body());
        assertTrue(rejected.body().contains("control-environment"),rejected.body());

        config=(ObjectNode)post(base,"/api/workbench/environment",config);
        String failedId=post(base,"/api/workbench/runs",config).path("id").asText();
        Set<String> powerDone=new HashSet<>();
        completeInitialization(failedId,powerDone,true);
        JsonNode failed=get(base,"/api/runs/"+failedId);
        assertEquals("FAILED",failed.path("outcome").asText(),failed.toString());
        assertEquals(14,powerDone.size(),"All power checks must finish before any function check");
        assertEquals("powered",systemState(failed).path("state").asText());
        assertFalse(failed.path("unresolvedDeviceActions").isEmpty(),"Failure must expose other active actions");

        config=(ObjectNode)post(base,"/api/workbench/environment",config);
        String successId=post(base,"/api/workbench/runs",config).path("id").asText();
        powerDone=new HashSet<>();completeInitialization(successId,powerDone,false);
        JsonNode succeeded=get(base,"/api/runs/"+successId);
        assertEquals("SUCCEEDED",succeeded.path("outcome").asText(),succeeded.toString());
        assertEquals(28,commandCount(succeeded));assertEquals(14,powerDone.size());
        assertTrue(succeeded.path("unresolvedDeviceActions").isEmpty());
        JsonNode state=systemState(succeeded);
        assertEquals("gxlf-experiment",state.path("model").asText());
        assertEquals("initialized",state.path("state").asText());
        assertEquals("valid",state.path("integrity").asText());assertEquals(2,state.path("history").size());
        for(String device:powerDone) {
            assertTrue(succeeded.path("initialDeviceSnapshots").has(device),device);
            assertTrue(state.path("evidence").has(device),device);
        }
        assertFalse(state.path("evidence").has("context_01"),"The demo condition device cannot prove system facts");
        System.out.println("PASS fourteen Java Tango components: missing device refused / global power barrier / function failure / 28 commands / verified system state");
    }
    void completeInitialization(String id,Set<String> powerDone,boolean failFunction) throws Exception {
        Set<String> handled=new HashSet<>();boolean failed=false;
        long deadline=System.nanoTime()+Duration.ofSeconds(90).toNanos();
        while(System.nanoTime()<deadline) {
            JsonNode run=get(base,"/api/runs/"+id);if(run.hasNonNull("ended"))return;
            for(JsonNode device:get(bridge,"/devices")) {
                String deviceId=device.path("id").asText();JsonNode snapshot=device.path("snapshot");
                if(!snapshot.hasNonNull("active_command_id"))continue;
                String commandId=snapshot.path("active_command_id").asText();if(!handled.add(commandId))continue;
                String action=get(bridge,"/devices/"+deviceId+"/commands/"+commandId).path("action").asText();
                if("function_check".equals(action)) {
                    assertEquals(14,powerDone.size(),"Function check cannot precede the full power barrier");
                    if(failFunction) {
                        if(!failed) {
                            post(bridge,"/devices/"+deviceId+"/simulate",json.createObjectNode()
                                .put("command_id",commandId).put("outcome","failure"));failed=true;
                        }
                        continue;
                    }
                }
                post(bridge,"/devices/"+deviceId+"/simulate",json.createObjectNode().put("command_id",commandId).put("outcome","success"));
                if("power_on_check".equals(action))powerDone.add(deviceId);
            }
            Thread.sleep(80);
        }
        fail("Fourteen-component initialization timed out: "+get(base,"/api/runs/"+id));
    }
    JsonNode systemState(JsonNode run) {
        String value=run.path("experimentSystemState").asText();
        try{return value.isBlank()?json.createObjectNode():json.readTree(value);}
        catch(Exception ex){throw new IllegalStateException("invalid experimentSystemState",ex);}
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
            "--control.pump-delay=1500","--control.workbench-directory="+temporary.resolve("workbench"),
            "--spring.datasource.url=jdbc:h2:mem:java-e2e"));
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
