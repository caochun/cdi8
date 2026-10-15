package cn.cdi8.control;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.flowable.engine.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.ApplicationEventPublisher;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:workflow-test;DB_CLOSE_DELAY=-1",
    "control.events-enabled=false", "control.pump-delay=3600000", "flowable.async-executor-activate=false"})
class WorkflowIntegrationTest {
    @Autowired WorkflowService workflows;
    @Autowired RuntimeService runtime;
    @Autowired ManagementService management;
    @Autowired ObjectMapper json;
    @Autowired FakeGateway gateway;
    @Autowired QualificationGateway qualification;
    Map<String,ObjectNode> snapshots;
    Map<String,ObjectNode> results;
    Map<String,String> devices;

    @BeforeEach void setup() {
        for (var process : runtime.createProcessInstanceQuery().list()) runtime.deleteProcessInstance(process.getId(), "test cleanup");
        snapshots=gateway.snapshots;results=gateway.results;devices=gateway.devices;
        snapshots.clear();results.clear();devices.clear();gateway.freshSnapshots.clear();gateway.online=true;gateway.calls=0;gateway.loseResponse=false;
        for (String kind : List.of("seed","shg","preamp","multipass","context")) {
            devices.put(kind+"_01", kind);
            ObjectNode s=json.createObjectNode().put("outer_state","ready").put("state_id", "functionReady");
            s.put("model",Map.of("seed","seed-source-hierarchical-v3","shg","shg-injection-hierarchical-v2",
                "preamp","preamp-hierarchical-v2","multipass","multipass-hierarchical-v2","context","preparation-context-demo-v1").get(kind));
            s.putNull("active_command_id");s.putObject("conditions").put("safety_permit",false).put("preamp_repeat_ready",false).put("collimation_environment_ready",false);
            s.put("state_revision", 0);
            snapshots.put(kind+"_01",s);
        }
    }
    @TestConfiguration
    static class Configuration {
        @Bean @Primary FakeGateway fakeGateway(ObjectMapper json, ApplicationEventPublisher events) { return new FakeGateway(json,events); }
    }
    static class FakeGateway extends TangoGateway {
        final ObjectMapper json;
        final Map<String,ObjectNode> snapshots=new ConcurrentHashMap<>();
        final Map<String,ObjectNode> results=new LinkedHashMap<>();
        final Map<String,String> devices=new LinkedHashMap<>();
        final Map<String,ObjectNode> freshSnapshots=new HashMap<>();
        boolean online=true; boolean loseResponse; int calls;
        FakeGateway(ObjectMapper json,ApplicationEventPublisher events) {super(json,events,"http://unused",false);this.json=json;}
        @Override public boolean connected(){return online;}
        @Override public JsonNode catalog(){return json.valueToTree(devices.entrySet().stream().map(d -> Map.of("id",d.getKey(),"component",d.getValue(),"connected",true)).toList());}
        @Override public JsonNode snapshot(String id){return freshSnapshots.getOrDefault(id,snapshots.get(id)).deepCopy();}
        @Override public JsonNode cached(String id){return snapshots.get(id);}
        @Override public JsonNode execute(String device,String id,String action,Object parameters){
            calls++;
            if(results.containsKey(id)) return results.get(id).deepCopy();
            ObjectNode result=json.createObjectNode().put("command_id",id).put("action",action).put("status","accepted");
            results.put(id,result);snapshots.get(device).put("active_command_id",id);
            snapshots.get(device).put("state_revision", snapshots.get(device).path("state_revision").asLong()+1);
            if(loseResponse){loseResponse=false;throw new TangoGateway.GatewayException(503,"response lost");}
            return result.deepCopy();
        }
        @Override public JsonNode result(String device,String id){return results.get(id).deepCopy();}
    }

    String start() { return (String)workflows.start("optical_frontend_preparation",List.of("seed")).get("id"); }
    void notice(String id) {
        ObjectNode event=json.createObjectNode().put("device_id",id).put("connected",true);
        event.set("snapshot", snapshots.get(id).deepCopy());
        workflows.event(new TangoGateway.Notice(event));
    }
    void conditions(boolean safety, boolean environment) { ((ObjectNode)snapshots.get("context_01").path("conditions")).put("safety_permit",safety).put("collimation_environment_ready",environment); notice("context_01"); }
    void completeAll() { completeDevices(devices.keySet()); }
    void completeDevices(Set<String> selected) {
        for(var item:snapshots.entrySet()) {
            if(!selected.contains(item.getKey())) continue;
            ObjectNode state=item.getValue(); if(!state.hasNonNull("active_command_id"))continue;
            String id=state.path("active_command_id").asText();ObjectNode result=results.get(id);String action=result.path("action").asText();
            result.put("status","succeeded");state.putNull("active_command_id");
            String next=switch(action) {
                case "power_on_check" -> "powerOnOk";
                case "function_check" -> "functionReady";
                case "parameter_dispatch" -> "parameterOk";
                case "seed_source_emit" -> "collectIdle";
                case "frequency_doubled_emit" -> "stableEmission";
                case "preamp_wakeup" -> "wakeupReady";
                case "coarse_loop" -> "coarseLoopOk";
                case "fine_loop" -> "fineLoopOk";
                case "optical_collimation" -> "collimationReady";
                case "preamp_repeat" -> "";
                default -> throw new AssertionError(action);
            };
            if(action.equals("preamp_repeat"))((ObjectNode)state.path("conditions")).put("preamp_repeat_ready",true);
            else {state.put("state_id",next);state.put("outer_state",Set.of("collectIdle","stableEmission").contains(next)?"emitting":Set.of("wakeupReady","coarseLoopOk","fineLoopOk").contains(next)?"wakeup":action.equals("power_on_check")?"notReady":action.equals("optical_collimation")?"collimated":"ready");}
            state.put("state_revision",state.path("state_revision").asLong()+1);
            state.set("last_result",result.deepCopy());
            result.set("snapshot",state.deepCopy());
            notice(item.getKey());
        }
    }
    void untilActivity(String pid,String activity) {
        for(int i=0;i<30;i++) {
            if(runtime.getActiveActivityIds(pid).contains(activity))return;
            workflows.pump();completeAll();
        }
        throw new AssertionError(workflows.view(pid));
    }
    @Test void completeParallelPreparationAndKeepUnselectedSourceReady() {
        String pid=start();untilActivity(pid,"wait_safety");
        workflows.pump();assertThat(runtime.getActiveActivityIds(pid)).contains("wait_safety");
        assertThat(results.values()).allMatch(r->r.path("action").asText().equals("parameter_dispatch"));
        conditions(true,true);
        for(int i=0;i<40 && runtime.createProcessInstanceQuery().processInstanceId(pid).count()>0;i++){workflows.pump();completeAll();}
        assertThat(workflows.view(pid).get("outcome")).isEqualTo("SUCCEEDED");
        assertThat(results).hasSize(10);assertThat(snapshots.get("shg_01").path("state_id").asText()).isEqualTo("parameterOk");
        assertThat(snapshots.get("preamp_01").path("state_id").asText()).isEqualTo("fineLoopOk");
    }
    @Test void deviceFailureCancelsAllParallelBranches() {
        String pid=start();untilActivity(pid,"wait_safety");conditions(true,true);workflows.pump();workflows.pump();
        ObjectNode active=results.values().stream().filter(r->r.path("action").asText().equals("seed_source_emit")).findFirst().orElseThrow();
        active.put("status","failed");notice("seed_01");workflows.pump();
        assertThat(workflows.view(pid).get("outcome")).isEqualTo("FAILED");
        assertThat(runtime.createExecutionQuery().processInstanceId(pid).count()).isZero();
        assertThat(results.values()).noneMatch(r->r.path("action").asText().equals("preamp_wakeup"));
    }
    @Test void wrongCommandIdFailsInsteadOfReleasingNode() {
        String pid=start();workflows.pump();completeAll();
        results.values().iterator().next().put("command_id","different-id");workflows.pump();
        assertThat(workflows.view(pid).get("outcome")).isEqualTo("FAILED");
    }
    @Test void recoveredCoordinatorFindsPersistedResultsWithoutEventAndDuplicatesAreHarmless() {
        String pid=start();workflows.pump();completeAll();
        var recovered=new WorkflowService(runtime, null, null,gateway,json,qualification,"PT15M","PT45M");
        recovered.pump();assertThat(runtime.getActiveActivityIds(pid)).contains("wait_safety");
        for(int i=0;i<3;i++){notice("seed_01");workflows.pump();}
        assertThat(gateway.calls).isEqualTo(4);
        assertThat(results).hasSize(4);
    }
    @Test void lostPermitStopsDispatchAndDisconnectedStreamCannotReleaseGate() {
        String pid=start();untilActivity(pid,"wait_safety");conditions(true,true);
        gateway.online=false;workflows.pump();assertThat(results).hasSize(4);
        gateway.online=true;workflows.pump();workflows.pump();completeAll();workflows.pump();
        conditions(false,true);workflows.pump();
        assertThat(workflows.view(pid).get("outcome")).isEqualTo("FAILED");
        assertThat(workflows.view(pid).get("failureReason").toString()).contains("安全许可");
    }
    @Test void lostAcceptanceResponseRetriesSameCommandAfterFeedback() {
        String pid=start();gateway.loseResponse=true;workflows.pump();
        assertThat(results).hasSize(1);String original=results.keySet().iterator().next();completeAll();
        var recovered=new WorkflowService(runtime,null,null,gateway,json,qualification,"PT15M","PT45M");
        recovered.pump();completeAll();
        // Query from a freshly recovered coordinator, without any event hints.
        new WorkflowService(runtime,null,null,gateway,json,qualification,"PT15M","PT45M").pump();
        assertThat(runtime.getActiveActivityIds(pid)).contains("wait_safety");
        assertThat(results).hasSize(4).containsKey(original);assertThat(gateway.calls).isEqualTo(5);
    }
    @Test void sourceQualificationLossStopsBothDownstreamBranches() {
        String pid=start();untilActivity(pid,"wait_safety");conditions(true,true);
        untilActivity(pid,"coarse_wait");
        snapshots.get("seed_01").put("outer_state","ready");notice("seed_01");workflows.pump();
        assertThat(workflows.view(pid).get("outcome")).isEqualTo("FAILED");
        assertThat(workflows.view(pid).get("failureReason").toString()).contains("供光资格");
    }
    @Test void commandTimeoutTerminatesWaitAndManualStopIsPersisted() {
        String pid=start();workflows.pump();
        var timer=management.createTimerJobQuery().processInstanceId(pid).list().stream()
            .filter(j->j.getElementId().equals("configure_timeout")).findFirst().orElseThrow();
        var job=management.moveTimerToExecutableJob(timer.getId());management.executeJob(job.getId());
        assertThat(workflows.view(pid).get("outcome")).isEqualTo("FAILED");
        snapshots.values().forEach(s->s.putNull("active_command_id"));
        String next=start();workflows.stop(next,"operator stop");
        assertThat(workflows.view(next).get("failureReason")).isEqualTo("operator stop");
        assertThat(workflows.view(next).get("outcome")).isEqualTo("FAILED");
    }
    @Test void initializationWaitsForEveryPowerCheckAndEveryFunctionCheck() {
        snapshots.values().forEach(s->s.put("outer_state","notReady").put("state_id","unchecked"));
        String pid=(String)workflows.start("component_initialization",List.of("seed")).get("id");
        workflows.pump();
        assertThat(results).hasSize(4);
        completeDevices(Set.of("seed_01","shg_01","preamp_01"));workflows.pump();
        assertThat(results.values()).allMatch(r->r.path("action").asText().equals("power_on_check"));
        assertThat(runtime.getActiveActivityIds(pid)).contains("power_gate");
        completeDevices(Set.of("multipass_01"));workflows.pump();workflows.pump();
        assertThat(results).hasSize(8);
        completeDevices(Set.of("seed_01","shg_01","preamp_01"));workflows.pump();
        assertThat(workflows.view(pid).get("outcome")).isEqualTo("RUNNING");
        assertThat(runtime.getActiveActivityIds(pid)).contains("function_gate");
        completeDevices(Set.of("multipass_01"));workflows.pump();
        assertThat(workflows.view(pid).get("outcome")).isEqualTo("SUCCEEDED");
    }
    @Test void initializationUsesSameRuntimeAndWaitsForAllComponents() {
        snapshots.values().forEach(s->s.put("outer_state","notReady").put("state_id","unchecked"));
        String pid=(String)workflows.start("component_initialization",List.of("seed")).get("id");
        for(int i=0;i<20 && runtime.createProcessInstanceQuery().processInstanceId(pid).count()>0;i++){workflows.pump();completeAll();}
        assertThat(workflows.view(pid).get("outcome")).isEqualTo("SUCCEEDED");assertThat(results).hasSize(8);
    }

    String startInitialization() {
        snapshots.values().forEach(s->s.put("outer_state","notReady").put("state_id","unchecked"));
        return (String)workflows.start("component_initialization",List.of("seed")).get("id");
    }
    void loseQualification(String device) {
        ObjectNode state=snapshots.get(device);
        state.put("outer_state","notReady").put("state_id","unchecked")
            .put("state_revision",state.path("state_revision").asLong()+1);
        notice(device);
    }
    void enterFunctionGate(String pid) {
        workflows.pump();completeAll();workflows.pump();workflows.pump();
        assertThat(runtime.getActiveActivityIds(pid)).contains("function_gate");
        assertThat(results).hasSize(8);
    }
    @Test void powerGatewayLatchesLossEvenWhenComponentRecoversBeforeNextPump() {
        String pid=startInitialization();workflows.pump();
        completeDevices(Set.of("seed_01","shg_01","preamp_01"));workflows.pump();
        loseQualification("seed_01");
        ObjectNode state=snapshots.get("seed_01");
        state.put("state_id","powerOnOk").put("state_revision",state.path("state_revision").asLong()+1);notice("seed_01");
        workflows.pump();
        assertThat(workflows.view(pid).get("outcome")).isEqualTo("FAILED");
        assertThat(workflows.view(pid).get("failureReason").toString()).contains("seed_01", "资格失效");
        assertThat(results.values()).noneMatch(r->r.path("action").asText().equals("function_check"));
        assertThat(historyActivities(pid)).contains("qualification_lost");
    }
    @Test void functionGatewayStopsWhenAnEarlySuccessLosesQualification() {
        String pid=startInitialization();enterFunctionGate(pid);
        completeDevices(Set.of("seed_01","shg_01","preamp_01"));workflows.pump();
        loseQualification("seed_01");workflows.pump();
        assertThat(workflows.view(pid).get("outcome")).isEqualTo("FAILED");
        assertThat(historyActivities(pid)).doesNotContain("finish");
    }
    @Test void gatewayRechecksEveryComponentThroughFreshReadsAtTheJoin() {
        String pid=startInitialization();enterFunctionGate(pid);
        completeDevices(Set.of("seed_01","shg_01","preamp_01"));workflows.pump();
        // An early completion still looks valid in the subscription cache.
        gateway.freshSnapshots.put("seed_01",snapshots.get("seed_01").deepCopy().put("outer_state","notReady").put("state_id","unchecked"));
        completeDevices(Set.of("multipass_01"));workflows.pump();
        assertThat(workflows.view(pid).get("outcome")).isEqualTo("FAILED");
        assertThat(workflows.view(pid).get("failureReason").toString()).contains("seed_01");
        assertThat(historyActivities(pid)).doesNotContain("finish");
    }
    @Test void gatewayEvidenceSurvivesCoordinatorRestartAndDoesNotResendCompletedChecks() {
        String pid=startInitialization();workflows.pump();
        completeDevices(Set.of("seed_01","shg_01","preamp_01"));workflows.pump();
        var recoveredGateway=new QualificationGateway(runtime,gateway,json);
        var recovered=new WorkflowService(runtime,null,null,gateway,json,recoveredGateway,"PT15M","PT45M");
        loseQualification("seed_01");recovered.pump();
        assertThat(workflows.view(pid).get("outcome")).isEqualTo("FAILED");
        assertThat(gateway.calls).isEqualTo(4);
    }
    @Test void externalRecheckCannotReplaceThisRunsPowerEvidenceDuringFunctionStage() {
        String pid=startInitialization();workflows.pump();completeAll();workflows.pump();
        assertThat(runtime.getActiveActivityIds(pid)).contains("function_gate");
        snapshots.get("seed_01").putObject("last_result").put("command_id","external-power-check").put("status","succeeded");
        workflows.pump();
        assertThat(workflows.view(pid).get("outcome")).isEqualTo("FAILED");
        assertThat(workflows.view(pid).get("failureReason").toString()).contains("本轮检查命令");
    }
    @Test void staleEventsCannotRevokeNewerEvidenceAndFunctionProgressKeepsPowerQualification() {
        String pid=startInitialization();workflows.pump();
        ObjectNode stale=snapshots.get("seed_01").deepCopy();
        completeAll();workflows.pump();workflows.pump();
        workflows.event(new TangoGateway.Notice(json.createObjectNode().put("device_id","seed_01").put("connected",true).set("snapshot",stale)));
        workflows.pump();
        assertThat(workflows.view(pid).get("outcome")).isEqualTo("RUNNING");
        completeAll();workflows.pump();
        assertThat(workflows.view(pid).get("outcome")).isEqualTo("SUCCEEDED");
    }
    @Test void rejectedCheckTakesTheGatewayFailureSequenceFlow() {
        String pid=startInitialization();workflows.pump();
        String command=snapshots.get("seed_01").path("active_command_id").asText();
        results.get(command).put("status","failed");notice("seed_01");workflows.pump();
        assertThat(workflows.view(pid).get("outcome")).isEqualTo("FAILED");
        assertThat(historyActivities(pid)).contains("raise_failure").doesNotContain("prepare_function");
    }
    @Test void gatewayTimeoutHasItsOwnBoundaryAndNamesTheStage() {
        String pid=startInitialization();workflows.pump();
        var timer=management.createTimerJobQuery().processInstanceId(pid).list().stream()
            .filter(j->j.getElementId().equals("power_gate_timeout")).findFirst().orElseThrow();
        management.executeJob(management.moveTimerToExecutableJob(timer.getId()).getId());
        assertThat(workflows.view(pid).get("outcome")).isEqualTo("FAILED");
        assertThat(workflows.view(pid).get("failureReason").toString()).contains("上电", "超时");
        assertThat(historyActivities(pid)).doesNotContain("prepare_function");
    }
    @Test void lostGatewayAcceptanceResponseRetriesTheSameRegisteredCommand() {
        String pid=startInitialization();gateway.loseResponse=true;workflows.pump();
        assertThat(results).hasSize(1);String original=results.keySet().iterator().next();completeAll();
        var recoveredGateway=new QualificationGateway(runtime,gateway,json);
        var recovered=new WorkflowService(runtime,null,null,gateway,json,recoveredGateway,"PT15M","PT45M");
        recovered.pump();completeAll();
        for (var device : snapshots.entrySet()) recovered.event(new TangoGateway.Notice(
            json.createObjectNode().put("device_id",device.getKey()).put("connected",true).set("snapshot",device.getValue().deepCopy())));
        recovered.pump();
        assertThat(runtime.getActiveActivityIds(pid)).contains("function_gate");
        assertThat(results).hasSize(4).containsKey(original);
        assertThat(gateway.calls).isEqualTo(5);
    }
    @SuppressWarnings("unchecked") List<String> historyActivities(String pid) {
        return ((List<Map<String,Object>>)workflows.view(pid).get("activities")).stream().map(a->a.get("id").toString()).toList();
    }
}
