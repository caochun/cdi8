package cn.cdi8.workbench;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Predicate;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.junit.jupiter.api.Assertions.*;

/** HTTP -> Flowable -> Java bridge -> real JTango -> SCXML. Isolated ports and storage. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"debug=false","logging.level.org.springframework=WARN"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NewChainIT {
    static final ObjectMapper JSON=new ObjectMapper();
    static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    static Path directory;
    static Process bridge;
    static String bridgeUrl;
    @LocalServerPort int port;
    @org.springframework.beans.factory.annotation.Autowired cn.cdi8.workbench.facility.FacilityStateRuntime facilityStates;
    @org.springframework.beans.factory.annotation.Autowired cn.cdi8.workbench.facility.FacilityDependencyRuntime dependencyMonitors;
    @org.springframework.beans.factory.annotation.Autowired org.flowable.engine.RuntimeService engineRuntime;
    @org.springframework.beans.factory.annotation.Autowired org.flowable.engine.RepositoryService repository;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) throws Exception {
        directory=Files.createTempDirectory("cdi8-new-chain-");
        Path jar=Path.of("device-simulator/target/device-simulator-0.1.0.jar").toAbsolutePath();
        assertTrue(Files.exists(jar),"先执行根目录 mvn -Pnew-chain-e2e verify");
        Path ready=directory.resolve("bridge-ready.json");
        bridge=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-jar",jar.toString(),
            "--bridge","--port","0","--ready-file",ready.toString(),"--data-dir",directory.resolve("simulator").toString())
            .redirectErrorStream(true).redirectOutput(directory.resolve("bridge.log").toFile()).start();
        long deadline=System.nanoTime()+Duration.ofSeconds(40).toNanos();
        while(!Files.exists(ready)) {
            if(!bridge.isAlive()||System.nanoTime()>deadline)throw new IllegalStateException("Bridge 启动失败："+directory);
            Thread.sleep(100);
        }
        bridgeUrl=JSON.readTree(ready.toFile()).path("url").asText();
        registry.add("control.tango-url",()->bridgeUrl);
        registry.add("control.workbench-directory",()->directory.resolve("workbench").toString());
        registry.add("spring.datasource.url",()->"jdbc:h2:mem:new-chain;DB_CLOSE_DELAY=-1");
        registry.add("control.pump-delay",()->"50");
    }

    @AfterAll static void cleanup() throws Exception {
        if(bridge!=null) {
            var children=bridge.descendants().toList();bridge.destroy();
            if(!bridge.waitFor(10,java.util.concurrent.TimeUnit.SECONDS))bridge.destroyForcibly();
            for(var child:children)if(child.isAlive())child.destroyForcibly();
        }
        System.out.println("New-chain E2E logs: "+directory);
    }

    JsonNode request(String method,String path,Object body) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(70));
        if(body==null)builder.GET();else builder.header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        var response=HTTP.send(builder.build(),HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode()<300,response.statusCode()+" "+path+" "+response.body());
        return JSON.readTree(response.body());
    }
    JsonNode awaitRun(String id,Predicate<JsonNode> check) throws Exception {
        long end=System.nanoTime()+Duration.ofSeconds(90).toNanos();JsonNode value;
        do {
            value=request("GET","/api/runs/"+id,null);if(check.test(value))return value;
            Thread.sleep(100);
        }while(System.nanoTime()<end);
        fail("运行等待超时："+value);return null;
    }
    ObjectNode configure(boolean full,boolean auto,String timeout) throws Exception {
        JsonNode models=request("GET","/api/workbench/models",null);
        Map<String,JsonNode> byId=new HashMap<>();models.forEach(m->byId.put(m.path("id").asText(),m));
        ObjectNode config=JSON.createObjectNode().put("entry",full?"gxlf_shot_run_smoke":"test_seed").put("autoComplete",true);
        var process=byId.get("gxlf_shot_run_smoke");
        config.putArray("processes").addObject().put("model","gxlf_shot_run_smoke").put("revision",process.path("revisions").get(process.path("revisions").size()-1).path("id").asText());
        var devices=config.putArray("devices");
        String[][] types={{"seed-source","seed"},{"shg-injection","shg"},{"preamp","preamp"},{"multipass","multipass"},{"pump","pump"},
            {"measurement","measurement"},{"frequency-conversion","frequency-conversion"},{"target-alignment","target-alignment"},{"vacuum","vacuum"},
            {"diagnostics","diagnostics"},{"synchronization","synchronization"},{"switch-driver","switch-driver"},{"cooling","cooling"},{"control-environment","control-environment"}};
        for(var type:types) {
            if(!full&&!type[1].equals("seed"))continue;
            String model=type[0]+"-state-machine";var revisions=byId.get(model).path("revisions");
            devices.addObject().put("model",model).put("revision",revisions.get(revisions.size()-1).path("id").asText())
                .put("component",type[1]).put("count",type[1].equals("preamp")?2:1).put("autoComplete",auto).put("delayMs",30);
        }
        config.putObject("conditions");config.putObject("bindings");
        config.putObject("variables").put("shotId","E2E-"+UUID.randomUUID()).put("recipeRef","simulation").put("recipeVersion","1")
            .put("commandTimeout",timeout).put("workflowTimeout","PT120S").putArray("beamlines").add(1);
        if(!full)setProcess(config,"test_seed",Files.readString(Path.of("examples/minimal/process.bpmn20.xml")));
        return (ObjectNode)request("POST","/api/workbench/environment",config);
    }
    void setProcess(ObjectNode config,String id,String xml) throws Exception {
        request("POST","/api/workbench/models",Map.of("id",id,"name",id,"kind","bpmn","xml",xml));
        JsonNode revision=request("POST","/api/workbench/models/"+id+"/versions",Map.of());
        config.put("entry",id);config.putArray("processes").addObject().put("model",id).put("revision",revision.path("id").asText());
    }
    String start(JsonNode config) throws Exception {return request("POST","/api/workbench/runs",config).path("id").asText();}

    @Test @Order(1) void allComponentsInitializeAndReceiveParameters() throws Exception {
        var config=configure(true,true,"PT10S");
        var result=awaitRun(start(config),r->!r.path("ended").isNull());
        assertEquals("SUCCEEDED",result.path("outcome").asText(),result.toPrettyString());
        assertEquals("completed",result.path("shotRun").path("stage").asText());
        assertEquals(43,result.path("commands").size(),result.toPrettyString());
        assertEquals(46,result.path("shotRun").path("evidence").size());
        Set<String> completionFacts=new HashSet<>();
        for(var evidence:result.path("shotRun").path("evidence")) {
            assertEquals("succeeded",evidence.path("status").asText());
            if("facility-fact".equals(evidence.path("kind").asText())) {
                var details=evidence.path("details");completionFacts.add(details.path("condition").asText());
                assertEquals("activity-completion",details.path("purpose").asText());
                assertEquals("valid",details.path("facilityState").path("facts").path(details.path("condition").asText()).path("status").asText());
                continue;
            }
            assertFalse(evidence.path("source_id").asText().isBlank());
            assertTrue(evidence.path("state_revision").asLong()>0);
            var details=evidence.path("details");var command=JSON.readTree(details.path("commandResult").asText());
            assertEquals(details.path("expectedState").asText(),command.path("snapshot").path("state_id").asText());
        }
        assertEquals(Set.of("allPowerChecked","allFunctionReady","allParametersReceived"),completionFacts);
        assertEquals(0,result.path("unresolvedDeviceActions").size());
        assertEquals("unknown",result.path("facilityState").path("facts").path("interlockEstablished").path("status").asText());
    }

    @Test @Order(2) void failedDeviceResultStopsRunAndPreservesEvidence() throws Exception {
        var config=configure(false,false,"PT10S");String id=start(config);
        var active=awaitRun(id,r->r.path("commands").size()>0&&r.path("commands").get(0).path("sent").asBoolean());
        String command=active.path("commands").get(0).path("commandId").asText();
        request("POST","/api/workbench/devices/seed_01/simulate",Map.of("command_id",command,"outcome","failure"));
        var result=awaitRun(id,r->!r.path("ended").isNull());
        assertEquals("FAILED",result.path("outcome").asText(),result.toPrettyString());
        assertEquals("failed",result.path("shotRun").path("stage").asText());
        assertEquals("failed",result.path("shotRun").path("evidence").get(0).path("status").asText());
        assertFalse(result.path("shotRun").path("failure").path("reason").asText().isBlank());
    }

    @Test @Order(3) void cancellationKeepsUnknownPhysicalAction() throws Exception {
        String id=start(configure(false,false,"PT10S"));
        awaitRun(id,r->r.path("commands").size()>0&&r.path("commands").get(0).path("sent").asBoolean());
        var result=request("POST","/api/runs/"+id+"/stop",Map.of());
        assertEquals("CANCELLED",result.path("outcome").asText());
        assertEquals("cancelled",result.path("shotRun").path("stage").asText());
        assertEquals(1,result.path("unresolvedDeviceActions").size());
        assertEquals("unknown",result.path("shotRun").path("evidence").get(0).path("status").asText());
        assertFalse(request("GET","/api/devices",null).get(0).path("snapshot").path("active_command_id").isNull());
    }

    @Test @Order(4) void timeoutDoesNotClaimDeviceStopped() throws Exception {
        var result=awaitRun(start(configure(false,false,"PT0.8S")),r->!r.path("ended").isNull());
        assertEquals("FAILED",result.path("outcome").asText());
        assertTrue(result.path("failureReason").asText().contains("超时"));
        assertEquals(1,result.path("unresolvedDeviceActions").size());
    }

    @Test @Order(5) void guardOnOrdinaryTaskBlocksProgress() throws Exception {
        var config=configure(false,true,"PT10S");
        String xml=Files.readString(Path.of("examples/minimal/process.bpmn20.xml"));
        xml=xml.replace("<bpmn:serviceTask id=\"complete\" name=\"完成切片\" flowable:expression=\"${shotRuns.complete(execution)}\" />",
            "<bpmn:serviceTask id=\"complete\" name=\"安全条件核验\" flowable:expression=\"${shotRuns.complete(execution)}\"><bpmn:extensionElements><fac:requires guardRef=\"triggerAllowed\" /></bpmn:extensionElements></bpmn:serviceTask>");
        setProcess(config,"test_guard",xml);
        var result=awaitRun(start(config),r->!r.path("ended").isNull());
        assertEquals("FAILED",result.path("outcome").asText(),result.toPrettyString());
        assertTrue(result.path("failureReason").asText().contains("triggerAllowed"));
    }

    @Test @Order(6) void legacyAndAmbiguousActionModelsAreRejected() throws Exception {
        String xml=Files.readString(Path.of("examples/minimal/process.bpmn20.xml"));
        for(String bad:List.of(xml.replace("${facilityActions}","${componentCommand}"),xml.replace("delegateExpression=\"${facilityActions}\"","expression=\"${shotRuns.complete(execution)}\"")))
            assertFalse(request("POST","/api/workbench/validate",Map.of("kind","bpmn","xml",bad)).path("valid").asBoolean());
    }

    @Test @Order(7) void targetMismatchFailsDespiteSuccessfulCommand() throws Exception {
        var config=configure(false,true,"PT10S");
        String xml=Files.readString(Path.of("examples/minimal/process.bpmn20.xml")).replace("expectedStateRef=\"powerOnChecked\"","expectedStateRef=\"configured\"");
        setProcess(config,"test_wrong_target",xml);
        var result=awaitRun(start(config),r->!r.path("ended").isNull());
        assertEquals("FAILED",result.path("outcome").asText());
        assertTrue(result.path("failureReason").asText().contains("目标状态"));
        assertEquals("failed",result.path("shotRun").path("evidence").get(0).path("status").asText());
        assertEquals(0,result.path("unresolvedDeviceActions").size());
    }

    @Test @Order(8) void loopReentryUsesNewCommandAndKeepsBothResults() throws Exception {
        var config=configure(false,true,"PT10S");
        String xml=Files.readString(Path.of("examples/minimal/process.bpmn20.xml"))
            .replace("<bpmn:extensionElements>","<bpmn:extensionElements><fac:completion factRef=\"simulationDevicesObserved\" timeout=\"PT10S\" />")
            .replace("<bpmn:sequenceFlow id=\"f3\" sourceRef=\"check\" targetRef=\"complete\" />", """
                <bpmn:serviceTask id="increment" name="计数" flowable:expression="${execution.setVariable('round', round + 1)}" />
                <bpmn:exclusiveGateway id="again" default="exit_loop" />
                <bpmn:sequenceFlow id="next" sourceRef="check" targetRef="increment" />
                <bpmn:sequenceFlow id="evaluate" sourceRef="increment" targetRef="again" />
                <bpmn:sequenceFlow id="repeat" sourceRef="again" targetRef="check"><bpmn:conditionExpression>${round &lt; 2}</bpmn:conditionExpression></bpmn:sequenceFlow>
                <bpmn:sequenceFlow id="exit_loop" sourceRef="again" targetRef="complete" />
                """);
        ((ObjectNode)config.path("variables")).put("round",0);
        setProcess(config,"test_loop",xml);
        var result=awaitRun(start(config),r->!r.path("ended").isNull());
        assertEquals("SUCCEEDED",result.path("outcome").asText(),result.toPrettyString());
        assertEquals(2,result.path("commands").size());
        assertNotEquals(result.path("commands").get(0).path("commandId"),result.path("commands").get(1).path("commandId"));
        assertEquals(4,result.path("shotRun").path("evidence").size());
        Set<String> completionIds=new HashSet<>();
        for(var evidence:result.path("shotRun").path("evidence"))if("facility-fact".equals(evidence.path("kind").asText()))completionIds.add(evidence.path("id").asText());
        assertEquals(2,completionIds.size(),"循环重入必须建立独立完成条件等待，已成立的事实也能放行");
    }

    @Test @Order(9) void disconnectedDeviceStopsWithoutPretendingActionFailed() throws Exception {
        String id=start(configure(false,false,"PT10S"));
        awaitRun(id,r->r.path("commands").size()>0&&r.path("commands").get(0).path("sent").asBoolean());
        var children=bridge.descendants().filter(ProcessHandle::isAlive).toList();
        assertFalse(children.isEmpty());for(var child:children)child.destroy();
        var result=awaitRun(id,r->!r.path("ended").isNull());
        assertEquals("FAILED",result.path("outcome").asText());
        assertEquals("unknown",result.path("shotRun").path("evidence").get(0).path("status").asText());
        assertEquals(1,result.path("unresolvedDeviceActions").size());
    }

    String withCompletion(String declaration,boolean actions) throws Exception {
        String xml=Files.readString(Path.of("examples/minimal/process.bpmn20.xml"));
        if(!actions)xml=xml.replaceAll("<fac:actions>.*?</fac:actions>","");
        return xml.replace("<bpmn:extensionElements>","<bpmn:extensionElements>"+declaration);
    }

    @Test @Order(10) void successfulCommandWaitsForFacilityAndStateChangeResumesOnlyOnce() throws Exception {
        var config=configure(true,true,"PT10S");
        setProcess(config,"test_completion",withCompletion("<fac:completion factRef=\"allPowerChecked\" timeout=\"PT30S\" />",true));
        String id=start(config);
        var waiting=awaitRun(id,r->r.path("waits").size()==1&&"violated".equals(r.path("waits").get(0).path("status").asText()));
        assertEquals("RUNNING",waiting.path("outcome").asText());
        assertEquals(1,waiting.path("commands").size());
        assertEquals("succeeded",waiting.path("commands").get(0).path("verificationStatus").asText());
        assertTrue(waiting.path("activeActivities").toString().contains("check"));
        assertFalse(waiting.path("waits").get(0).path("reason").asText().isBlank());
        for(var device:request("GET","/api/devices",null)) {
            String deviceId=device.path("id").asText();
            if("seed_01".equals(deviceId)||"context".equals(device.path("component").asText()))continue;
            request("POST","/api/workbench/devices/"+deviceId+"/commands",Map.of(
                "command_id",UUID.randomUUID().toString(),"action","power_on_check","parameters",Map.of()));
        }
        var result=awaitRun(id,r->!r.path("ended").isNull());
        assertEquals("SUCCEEDED",result.path("outcome").asText(),result.toPrettyString());
        assertEquals(1,result.path("commands").size(),"等待设施事实不重复下发动作");
        assertEquals(2,result.path("shotRun").path("evidence").size());
        assertEquals(0,result.path("waits").size());
        // A later snapshot/condition event must not resume an already-finished activation.
        request("POST","/api/workbench/devices/seed_01/commands",Map.of(
            "command_id",UUID.randomUUID().toString(),"action","power_on_check","parameters",Map.of()));
        assertEquals(result.path("shotRun"),request("GET","/api/runs/"+id,null).path("shotRun"));
    }

    @Test @Order(11) void alreadyTrueFacilityConditionCannotBypassThisRunsCommand() throws Exception {
        var config=configure(false,false,"PT10S");
        setProcess(config,"test_correlated",withCompletion("<fac:completion factRef=\"simulationDevicesObserved\" timeout=\"PT10S\" />",true));
        String id=start(config);
        var active=awaitRun(id,r->r.path("commands").size()==1&&r.path("commands").get(0).path("sent").asBoolean());
        assertEquals("valid",active.path("facilityState").path("facts").path("simulationDevicesObserved").path("status").asText());
        assertEquals(0,active.path("waits").size(),"本发命令尚未成功，不能进入完成条件放行");
        assertEquals("RUNNING",active.path("outcome").asText());
        request("POST","/api/workbench/devices/seed_01/simulate",Map.of(
            "command_id",active.path("commands").get(0).path("commandId").asText(),"outcome","failure"));
        var result=awaitRun(id,r->!r.path("ended").isNull());
        assertEquals("FAILED",result.path("outcome").asText());
        assertEquals(1,result.path("shotRun").path("evidence").size());
        assertEquals("command-result",result.path("shotRun").path("evidence").get(0).path("kind").asText());
    }

    @Test @Order(12) void unknownCompletionTimesOutWithoutResendingSucceededCommand() throws Exception {
        var config=configure(false,true,"PT10S");
        setProcess(config,"test_completion_timeout",withCompletion("<fac:completion guardRef=\"triggerAllowed\" timeout=\"PT0.8S\" />",true));
        var result=awaitRun(start(config),r->!r.path("ended").isNull());
        assertEquals("FAILED",result.path("outcome").asText());
        assertTrue(result.path("failureReason").asText().contains("完成条件等待超时"));
        assertEquals(1,result.path("commands").size());
        assertEquals("succeeded",result.path("commands").get(0).path("verificationStatus").asText());
        assertEquals(0,result.path("unresolvedDeviceActions").size());
        assertEquals(1,result.path("waits").size());
        assertEquals("unknown",result.path("waits").get(0).path("status").asText());
        assertEquals(2,result.path("shotRun").path("evidence").size());
    }

    @Test @Order(13) void standaloneConditionWaitAcceptsAlreadyTrueFactWithoutCommands() throws Exception {
        var config=configure(false,true,"PT10S");
        setProcess(config,"test_wait_only",withCompletion("<fac:completion factRef=\"simulationDevicesObserved\" timeout=\"PT10S\" />",false));
        var result=awaitRun(start(config),r->!r.path("ended").isNull());
        assertEquals("SUCCEEDED",result.path("outcome").asText());
        assertEquals(0,result.path("commands").size());
        assertEquals(1,result.path("shotRun").path("evidence").size());
        assertEquals("facility-fact",result.path("shotRun").path("evidence").get(0).path("kind").asText());
    }

    @Test @Order(14) void cancellingCompletionWaitPreservesDiagnosisAndCompletedCommand() throws Exception {
        var config=configure(false,true,"PT10S");
        setProcess(config,"test_cancel_wait",withCompletion("<fac:completion guardRef=\"triggerAllowed\" timeout=\"PT30S\" />",true));
        String id=start(config);
        awaitRun(id,r->r.path("waits").size()==1);
        var result=request("POST","/api/runs/"+id+"/stop",Map.of());
        assertEquals("CANCELLED",result.path("outcome").asText());
        assertEquals(1,result.path("waits").size());
        assertEquals("cancelled",result.path("waits").get(0).path("outcome").asText());
        assertEquals(0,result.path("unresolvedDeviceActions").size());
        assertEquals("succeeded",result.path("commands").get(0).path("verificationStatus").asText());
    }

    void deviceAction(String device,String action) throws Exception {
        String command=UUID.randomUUID().toString();
        var accepted=request("POST","/api/workbench/devices/"+device+"/commands",Map.of("command_id",command,"action",action,"parameters",Map.of()));
        assertEquals("accepted",accepted.path("status").asText(),accepted.toPrettyString());
        var completed=request("POST","/api/workbench/devices/"+device+"/simulate",Map.of("command_id",command,"outcome","success"));
        assertEquals("succeeded",completed.path("status").asText(),completed.toPrettyString());
    }
    void initialize(String device) throws Exception {
        for(String action:List.of("power_on_check","function_check","parameter_dispatch"))deviceAction(device,action);
    }
    ObjectNode opticalConfiguration(boolean emit,boolean shg,boolean wait) throws Exception {
        var config=configure(true,false,"PT20S");
        initialize("synchronization_01");deviceAction("synchronization_01","repeat_mode");
        initialize("preamp_01");deviceAction("preamp_01","preamp_wakeup");
        initialize("multipass_01");
        String source=shg?"shg_01":"seed_01";initialize(source);
        if(emit)deviceAction(source,shg?"frequency_doubled_emit":"seed_source_emit");
        if(shg)((ObjectNode)config.path("variables")).putObject("sourceSelections").put("1","shg");
        String xml=Files.readString(Path.of("examples/minimal/process.bpmn20.xml"))
            .replace("typeRef=\"seed\" capabilityRef=\"power-on-check\" collectionVar=\"seedTargets\" expectedStateRef=\"powerOnChecked\"",
                "typeRef=\"multipass\" capabilityRef=\"collimation\" collectionVar=\"multipassTargets\" expectedStateRef=\"collimationReady\"");
        if(wait)xml=xml.replace("<bpmn:extensionElements>","<bpmn:extensionElements><fac:completion guardRef=\"triggerAllowed\" timeout=\"PT30S\" />");
        setProcess(config,"test_optical_dependency",xml);
        return config;
    }
    void report(String device,String event,String outer) throws Exception {
        request("POST","/api/workbench/devices/"+device+"/events",Map.of("event_id",UUID.randomUUID().toString(),"event",event,"observed_outer_state",outer));
    }
    JsonNode opticalActive(String run) throws Exception {
        return awaitRun(run,r->r.path("ended").isNull()&&r.path("commands").size()==1&&r.path("commands").get(0).path("sent").asBoolean());
    }
    void finishOptical(JsonNode active) throws Exception {
        request("POST","/api/workbench/devices/multipass_01/simulate",Map.of("command_id",active.path("commands").get(0).path("commandId").asText(),"outcome","success"));
    }

    @Test @Order(15) void declaredOpticalDependencyAutomaticallyAppliesWithoutBpmnGuard() throws Exception {
        String id=start(opticalConfiguration(true,false,false));var active=opticalActive(id);
        assertEquals("valid",active.path("dependencies").get(0).path("status").asText());
        assertEquals(3,active.path("dependencies").get(0).path("requirements").size());
        finishOptical(active);var result=awaitRun(id,r->!r.path("ended").isNull());
        assertEquals("SUCCEEDED",result.path("outcome").asText(),result.toPrettyString());
        assertEquals("succeeded",result.path("dependencies").get(0).path("outcome").asText());
        assertEquals(2,result.path("shotRun").path("evidence").size());
    }
    @Test @Order(16) void missingLightBlocksBeforeExecute() throws Exception {
        var result=awaitRun(start(opticalConfiguration(false,false,false)),r->!r.path("ended").isNull());
        assertEquals("FAILED",result.path("outcome").asText(),result.toPrettyString());
        assertFalse(result.path("commands").get(0).path("dispatchAttempted").asBoolean());
        assertEquals("blocked",result.path("commands").get(0).path("verificationStatus").asText());
        assertEquals(0,result.path("unresolvedDeviceActions").size());
        assertTrue(result.path("failureReason").asText().contains("seedEmitting"));
    }
    @Test @Order(17) void upstreamLossDuringCommandStopsFlowAndPreservesPhysicalUncertainty() throws Exception {
        String id=start(opticalConfiguration(true,false,false));opticalActive(id);
        report("seed_01","emission_lost_confirmed","就绪");
        var result=awaitRun(id,r->!r.path("ended").isNull());
        assertEquals("FAILED",result.path("outcome").asText(),result.toPrettyString());
        assertTrue(result.path("failureReason").asText().contains("seed-01"));
        assertTrue(result.path("dependencies").get(0).path("latched").asBoolean());
        assertEquals(1,result.path("unresolvedDeviceActions").size());
        assertFalse(result.path("activities").findValuesAsText("id").contains("complete"));
    }
    @Test @Order(18) void alternateSourceIgnoresUnselectedSeedAndUnrelatedPreamp() throws Exception {
        String id=start(opticalConfiguration(true,true,false));var active=opticalActive(id);
        Set<String> inputs=new HashSet<>();active.path("dependencies").get(0).path("requirements").forEach(r->inputs.add(r.path("device").asText()));
        assertEquals(Set.of("shg_01","preamp_01","synchronization_01"),inputs);
        report("seed_01","fault_confirmed","异常");
        finishOptical(active);var result=awaitRun(id,r->!r.path("ended").isNull());
        assertEquals("SUCCEEDED",result.path("outcome").asText(),result.toPrettyString());
        assertEquals("shg",result.path("shotRun").path("resource_selection").path("source_selection").path("by_beamline").path("1").asText());
    }
    @Test @Order(19) void commandDependencyEndsBeforeSeparateActivityCompletionWait() throws Exception {
        String id=start(opticalConfiguration(true,false,true));finishOptical(opticalActive(id));
        awaitRun(id,r->r.path("waits").size()==1);
        report("seed_01","emission_lost_confirmed","就绪");
        var waiting=awaitRun(id,r->"violated".equals(r.path("facilityState").path("deviceFacts").path("seed_01").path("seedEmitting").path("status").asText()));
        assertEquals("RUNNING",waiting.path("outcome").asText(),waiting.toPrettyString());
        assertEquals("succeeded",waiting.path("dependencies").get(0).path("outcome").asText());
        request("POST","/api/runs/"+id+"/stop",Map.of());
    }

    @Test @Order(20) void actualSelectedScxmlMustImplementFacilityContractBeforeAnyDispatch() throws Exception {
        var config=configure(false,true,"PT10S");
        String xml=Files.readString(Path.of("examples/gxlf/components/seed-source-state-machine.scxml"))
            .replace("\"power_on_check\":","\"renamed_check\":");
        assertTrue(request("POST","/api/workbench/validate",Map.of("kind","scxml","xml",xml)).path("valid").asBoolean());
        request("POST","/api/workbench/models",Map.of("id","incompatible-seed","name","不满足设施能力的种子源","kind","scxml","xml",xml));
        var revision=request("POST","/api/workbench/models/incompatible-seed/versions",Map.of());
        ((ObjectNode)config.path("devices").get(0)).put("model","incompatible-seed").put("revision",revision.path("id").asText());
        config=(ObjectNode)request("POST","/api/workbench/environment",config);
        JsonNode history=request("GET","/api/workbench/devices/seed_01/history",null);
        var activeDefinition=facilityStates.definition();
        var call=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/workbench/runs"))
            .timeout(Duration.ofSeconds(20)).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(config.toString())).build();
        var response=HTTP.send(call,HttpResponse.BodyHandlers.ofString());
        assertEquals(409,response.statusCode(),response.body());
        assertTrue(response.body().contains("power_on_check"),response.body());
        assertSame(activeDefinition,facilityStates.definition(),"失败的模型校验不得切换当前设施模型");
        assertEquals(history,request("GET","/api/workbench/devices/seed_01/history",null),"校验失败不得下发设备命令");
    }

    String holdProcess(String condition) throws Exception {
        String xml=Files.readString(Path.of("examples/minimal/process.bpmn20.xml"))
            .replace("<bpmn:process", "<bpmn:error id=\"holdError\" errorCode=\"HOLD_LOST\"/><bpmn:process")
            .replace("typeRef=\"seed\" capabilityRef=\"power-on-check\" collectionVar=\"seedTargets\" expectedStateRef=\"powerOnChecked\"",
                "typeRef=\"multipass\" capabilityRef=\"collimation\" collectionVar=\"multipassTargets\" expectedStateRef=\"collimationReady\"")
            .replace("    <bpmn:serviceTask id=\"check\"", """
                <bpmn:subProcess id="holding" name="准直阶段保持">
                  <bpmn:extensionElements><fac:hold errorRef="holdError"><fac:input typeRef="multipass" capabilityRef="collimation" requirementRef="incident-light" collectionVar="multipassTargets"/></fac:hold></bpmn:extensionElements>
                  <bpmn:startEvent id="insideStart"/>
                  <bpmn:serviceTask id="check"""+"\"")
            .replace("    <bpmn:serviceTask id=\"complete\"", """
                  <bpmn:serviceTask id="review" name="跨任务等待" flowable:delegateExpression="${facilityActions}"><bpmn:extensionElements><fac:completion %s timeout="PT60S"/></bpmn:extensionElements></bpmn:serviceTask>
                  <bpmn:endEvent id="insideEnd"/>
                  <bpmn:sequenceFlow id="i1" sourceRef="insideStart" targetRef="check"/>
                  <bpmn:sequenceFlow id="i2" sourceRef="check" targetRef="review"/>
                  <bpmn:sequenceFlow id="i3" sourceRef="review" targetRef="insideEnd"/>
                </bpmn:subProcess>
                <bpmn:boundaryEvent id="holdLost" attachedToRef="holding"><bpmn:errorEventDefinition errorRef="holdError"/></bpmn:boundaryEvent>
                <bpmn:serviceTask id="handleHoldLoss" flowable:expression="${shotRuns.fail(execution, holdFailureReason)}"/>
                <bpmn:endEvent id="failedEnd"/>
                <bpmn:sequenceFlow id="h1" sourceRef="holdLost" targetRef="handleHoldLoss"/>
                <bpmn:sequenceFlow id="h2" sourceRef="handleHoldLoss" targetRef="failedEnd"/>
                <bpmn:serviceTask id="complete""".formatted(condition)+"\"")
            .replace("sourceRef=\"open\" targetRef=\"check\"","sourceRef=\"open\" targetRef=\"holding\"")
            .replace("sourceRef=\"check\" targetRef=\"complete\"","sourceRef=\"holding\" targetRef=\"complete\"");
        return xml;
    }
    ObjectNode heldConfiguration(boolean emit,boolean shg,String condition)throws Exception {
        var config=opticalConfiguration(emit,shg,false);setProcess(config,"test_stage_hold",holdProcess(condition));return config;
    }
    void assertHeldFailure(JsonNode run) {
        assertEquals("FAILED",run.path("outcome").asText(),run.toPrettyString());
        assertEquals("failed",run.path("holds").get(0).path("outcome").asText(),run.toPrettyString());
        assertTrue(run.path("activities").findValuesAsText("id").contains("handleHoldLoss"),run.toPrettyString());
        assertFalse(run.path("activities").findValuesAsText("id").contains("complete"));
    }

    @Test @Order(21) void fullStageExampleInitializesHoldsReleasesAndChangesMode()throws Exception {
        var config=configure(true,true,"PT10S");
        for(var device:config.path("devices"))((ObjectNode)device).put("count",1);
        config=(ObjectNode)request("POST","/api/workbench/environment",config);
        setProcess(config,"test_hold_example",Files.readString(Path.of("examples/gxlf/processes/gxlf_stage_hold.bpmn20.xml")));
        var result=awaitRun(start(config),r->!r.path("ended").isNull());
        assertEquals("SUCCEEDED",result.path("outcome").asText(),result.toPrettyString());
        assertEquals(1,result.path("holds").size());assertEquals("released",result.path("holds").get(0).path("outcome").asText());
        assertTrue(result.path("activities").findValuesAsText("id").contains("single"));
        assertEquals("violated",result.path("facilityState").path("deviceFacts").path("synchronization_01").path("synchronizationRepeat").path("status").asText());
    }
    @Test @Order(22) void invalidHoldEntryTakesBoundaryWithoutSendingAnyCommand()throws Exception {
        var result=awaitRun(start(heldConfiguration(false,false,"guardRef=\"triggerAllowed\"")),r->!r.path("ended").isNull());
        assertHeldFailure(result);assertEquals(0,result.path("commands").size());assertEquals(0,result.path("unresolvedDeviceActions").size());
    }
    @Test @Order(23) void stageLossInterruptsCommandButPreservesPhysicalUncertainty()throws Exception {
        String id=start(heldConfiguration(true,false,"guardRef=\"triggerAllowed\""));opticalActive(id);
        report("seed_01","emission_lost_confirmed","就绪");
        var result=awaitRun(id,r->!r.path("ended").isNull());assertHeldFailure(result);
        assertEquals(1,result.path("unresolvedDeviceActions").size());assertEquals("unknown",result.path("commands").get(0).path("verificationStatus").asText());
    }
    @Test @Order(24) void stageHoldSurvivesCommandCompletionAndSeparateTaskWait()throws Exception {
        String id=start(heldConfiguration(true,false,"guardRef=\"triggerAllowed\""));finishOptical(opticalActive(id));
        var waiting=awaitRun(id,r->r.path("waits").size()==1);
        assertEquals("active",waiting.path("holds").get(0).path("outcome").asText());
        assertEquals("succeeded",waiting.path("dependencies").get(0).path("outcome").asText());
        report("seed_01","emission_lost_confirmed","就绪");
        var result=awaitRun(id,r->!r.path("ended").isNull());assertHeldFailure(result);
        assertEquals(0,result.path("unresolvedDeviceActions").size());assertEquals("succeeded",result.path("commands").get(0).path("verificationStatus").asText());
        assertEquals("interrupted",result.path("waits").get(0).path("outcome").asText());
    }
    @Test @Order(25) void selectedShgIgnoresUnusedSeedButCascadesClockLoss()throws Exception {
        String id=start(heldConfiguration(true,true,"guardRef=\"triggerAllowed\""));finishOptical(opticalActive(id));
        var waiting=awaitRun(id,r->r.path("waits").size()==1);Set<String> selected=new HashSet<>();
        waiting.path("holds").get(0).path("requirements").forEach(r->selected.add(r.path("device").asText()));
        assertEquals(Set.of("shg_01","preamp_01","synchronization_01"),selected);
        report("seed_01","fault_confirmed","异常");
        assertEquals("RUNNING",request("GET","/api/runs/"+id,null).path("outcome").asText());
        deviceAction("synchronization_01","single_mode");
        var result=awaitRun(id,r->!r.path("ended").isNull());assertHeldFailure(result);
        assertTrue(result.path("failureReason").asText().contains("synchronizationRepeat"));
    }
    @Test @Order(26) void unknownHighLevelConditionBlocksScopeEntry()throws Exception {
        var config=heldConfiguration(true,false,"factRef=\"simulationDevicesObserved\"");
        setProcess(config,"test_unknown_hold",holdProcess("factRef=\"simulationDevicesObserved\"").replace("</fac:hold>","<fac:condition factRef=\"interlockEstablished\"/></fac:hold>"));
        var result=awaitRun(start(config),r->!r.path("ended").isNull());assertHeldFailure(result);
        assertEquals("unknown",result.path("holds").get(0).path("status").asText());assertEquals(0,result.path("commands").size());
    }
    @Test @Order(27) void cancellationReleasesScopeAndPreservesActiveCommand()throws Exception {
        String id=start(heldConfiguration(true,false,"guardRef=\"triggerAllowed\""));opticalActive(id);
        var result=request("POST","/api/runs/"+id+"/stop",Map.of());
        assertEquals("CANCELLED",result.path("outcome").asText());assertEquals("cancelled",result.path("holds").get(0).path("outcome").asText());
        assertEquals(1,result.path("unresolvedDeviceActions").size());
    }
    @Test @Order(28) void endingOneParallelBranchDoesNotReleaseStageHold()throws Exception {
        var config=heldConfiguration(true,false,"guardRef=\"triggerAllowed\"");
        String xml=holdProcess("guardRef=\"triggerAllowed\"")
            .replace("<bpmn:endEvent id=\"insideEnd\"/>","""
                <bpmn:parallelGateway id="fork"/>
                <bpmn:serviceTask id="parallelReview" flowable:delegateExpression="${facilityActions}"><bpmn:extensionElements><fac:completion factRef="simulationDevicesObserved" timeout="PT10S"/></bpmn:extensionElements></bpmn:serviceTask>
                <bpmn:endEvent id="parallelEnd"/>
                <bpmn:endEvent id="insideEnd"/>
                <bpmn:sequenceFlow id="p1" sourceRef="fork" targetRef="review"/>
                <bpmn:sequenceFlow id="p2" sourceRef="fork" targetRef="parallelReview"/>
                <bpmn:sequenceFlow id="p3" sourceRef="parallelReview" targetRef="parallelEnd"/>
                """)
            .replace("sourceRef=\"check\" targetRef=\"review\"","sourceRef=\"check\" targetRef=\"fork\"");
        setProcess(config,"test_parallel_hold",xml);String id=start(config);finishOptical(opticalActive(id));
        var waiting=awaitRun(id,r->r.path("activities").findValuesAsText("id").contains("parallelEnd"));
        assertEquals("active",waiting.path("holds").get(0).path("outcome").asText());
        report("seed_01","emission_lost_confirmed","就绪");assertHeldFailure(awaitRun(id,r->!r.path("ended").isNull()));
    }
    @Test @Order(29) void loopReentryCreatesIndependentHoldActivations()throws Exception {
        var config=heldConfiguration(true,false,"factRef=\"simulationDevicesObserved\"");
        ((ObjectNode)config.path("variables")).put("round",0);
        String xml=holdProcess("factRef=\"simulationDevicesObserved\"")
            .replaceAll("<fac:actions>.*?</fac:actions>","<fac:completion factRef=\"simulationDevicesObserved\" timeout=\"PT10S\"/>")
            .replace("<bpmn:sequenceFlow id=\"f3\" sourceRef=\"holding\" targetRef=\"complete\" />","""
                <bpmn:serviceTask id="increment" flowable:expression="${execution.setVariable('round',round+1)}"/>
                <bpmn:exclusiveGateway id="again" default="leaveLoop"/>
                <bpmn:sequenceFlow id="l1" sourceRef="holding" targetRef="increment"/>
                <bpmn:sequenceFlow id="l2" sourceRef="increment" targetRef="again"/>
                <bpmn:sequenceFlow id="l3" sourceRef="again" targetRef="holding"><bpmn:conditionExpression>${round &lt; 2}</bpmn:conditionExpression></bpmn:sequenceFlow>
                <bpmn:sequenceFlow id="leaveLoop" sourceRef="again" targetRef="complete"/>
                """);
        setProcess(config,"test_hold_loop",xml);var result=awaitRun(start(config),r->!r.path("ended").isNull());
        assertEquals("SUCCEEDED",result.path("outcome").asText(),result.toPrettyString());assertEquals(2,result.path("holds").size());
        assertNotEquals(result.path("holds").get(0).path("id"),result.path("holds").get(1).path("id"));
        for(var hold:result.path("holds"))assertEquals("released",hold.path("outcome").asText());
    }
    @Test @Order(30) void lossOfContinuousMonitoringCoverageTakesUnknownFailurePath()throws Exception {
        String id=start(heldConfiguration(true,false,"guardRef=\"triggerAllowed\""));finishOptical(opticalActive(id));
        var waiting=awaitRun(id,r->r.path("waits").size()==1);
        dependencyMonitors.release(waiting.path("holds").get(0).path("id").asText());
        var result=awaitRun(id,r->!r.path("ended").isNull());assertHeldFailure(result);
        assertEquals("unknown",result.path("holds").get(0).path("status").asText());assertTrue(result.path("failureReason").asText().contains("监控中断"));
    }
    @Test @Order(31) void timerBetweenTasksIsStillInsideHoldingScope()throws Exception {
        var config=heldConfiguration(true,false,"factRef=\"simulationDevicesObserved\"");
        String xml=holdProcess("factRef=\"simulationDevicesObserved\"")
            .replace("<bpmn:endEvent id=\"insideEnd\"/>","""
                <bpmn:intermediateCatchEvent id="settle"><bpmn:timerEventDefinition><bpmn:timeDuration>PT30S</bpmn:timeDuration></bpmn:timerEventDefinition></bpmn:intermediateCatchEvent>
                <bpmn:sequenceFlow id="settled" sourceRef="settle" targetRef="review"/>
                <bpmn:endEvent id="insideEnd"/>
                """)
            .replace("sourceRef=\"check\" targetRef=\"review\"","sourceRef=\"check\" targetRef=\"settle\"");
        setProcess(config,"test_timer_hold",xml);String id=start(config);finishOptical(opticalActive(id));
        awaitRun(id,r->r.path("timers").size()==1);
        report("seed_01","emission_lost_confirmed","就绪");
        var result=awaitRun(id,r->!r.path("ended").isNull());assertHeldFailure(result);
        assertFalse(result.path("activities").findValuesAsText("id").contains("review"));assertEquals(0,result.path("timers").size());
    }
    @Test @Order(32) void observedLossAtScopeExitRollsBackNormalContinuationAndTakesBoundary()throws Exception {
        var config=heldConfiguration(true,false,"factRef=\"simulationDevicesObserved\"");
        var injected=new java.util.concurrent.atomic.AtomicBoolean();
        var listener=new org.flowable.engine.delegate.event.AbstractFlowableEngineEventListener() {
            @Override protected void activityCompleted(org.flowable.engine.delegate.event.FlowableActivityEvent event) {
                if("insideEnd".equals(event.getActivityId())&&injected.compareAndSet(false,true))try {
                    report("seed_01","emission_lost_confirmed","就绪");facilityStates.refresh();
                }catch(Exception ex){throw new IllegalStateException(ex);}
            }
            @Override public boolean isFailOnException(){return true;}
        };
        engineRuntime.addEventListener(listener,org.flowable.common.engine.api.delegate.event.FlowableEngineEventType.ACTIVITY_COMPLETED);
        try {
            String id=start(config);finishOptical(opticalActive(id));
            var result=awaitRun(id,r->!r.path("ended").isNull());assertTrue(injected.get());assertHeldFailure(result);
            assertEquals("violated",result.path("holds").get(0).path("status").asText());
        }finally{engineRuntime.removeEventListener(listener);}
    }
    @Test @Order(33) void directEngineDeploymentCannotIgnoreHoldOnUnsupportedTask() {
        String xml="""
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:fac="https://cdi8.cn/facility/v1" targetNamespace="test">
              <process id="invalid_hold" isExecutable="true">
                <startEvent id="start"/><userTask id="user"><extensionElements><fac:hold errorRef="missing"><fac:condition factRef="simulationDevicesObserved"/></fac:hold></extensionElements></userTask>
                <endEvent id="end"/><sequenceFlow id="f1" sourceRef="start" targetRef="user"/><sequenceFlow id="f2" sourceRef="user" targetRef="end"/>
              </process>
            </definitions>
            """;
        assertThrows(RuntimeException.class,()->repository.createDeployment().addString("invalid.bpmn20.xml",xml).deploy());
        assertEquals(0,repository.createProcessDefinitionQuery().processDefinitionKey("invalid_hold").count());
    }
}
