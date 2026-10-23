package cn.cdi8.workbench;

import cn.cdi8.workbench.integration.tango.TangoGateway;
import cn.cdi8.workbench.run.WorkflowService;
import cn.cdi8.workbench.run.RunPolicies;
import cn.cdi8.workbench.run.ExecutionPolicy;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.nio.file.Files;
import java.nio.file.Path;
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
    "control.events-enabled=false", "control.pump-delay=3600000", "flowable.async-executor-activate=false", "control.workbench-directory=${java.io.tmpdir}/cdi8-workflow-test-${random.uuid}"})
class WorkflowIntegrationTest {
    @Autowired WorkflowService workflows;
    @Autowired cn.cdi8.workbench.systemstate.SystemStateRuntime systemStates;
    @Autowired RunPolicies runPolicies;
    @Autowired List<ExecutionPolicy> executionPolicies;
    @Autowired RuntimeService runtime;
    @Autowired ManagementService management;
    @Autowired RepositoryService repository;
    @Autowired HistoryService history;
    @Autowired ObjectMapper json;
    @Autowired FakeGateway gateway;
    Map<String,ObjectNode> snapshots;
    Map<String,ObjectNode> results;
    Map<String,String> devices;

    @BeforeEach void setup() {
        for (var process : runtime.createProcessInstanceQuery().list()) runtime.deleteProcessInstance(process.getId(), "test cleanup");
        snapshots=gateway.snapshots;results=gateway.results;devices=gateway.devices;
        snapshots.clear();results.clear();devices.clear();gateway.freshSnapshots.clear();gateway.readinessOverrides.clear();gateway.online=true;gateway.calls=0;gateway.loseResponse=false;gateway.loseRequest=false;
        for (String kind : List.of("seed","shg","preamp","multipass","context")) {
            devices.put(kind+"_01", kind);
            ObjectNode s=json.createObjectNode().put("outer_state","ready").put("state_id", kind.equals("seed") ? "qualified" : "functionReady");
            s.put("model",Map.of("seed","seed-source-reference-v2","shg","shg-injection-hierarchical-v2",
                "preamp","preamp-hierarchical-v3","multipass","multipass-hierarchical-v3","context","preparation-context-demo-v1").get(kind));
            s.putNull("active_command_id");s.putObject("conditions").put("safety_permit",false).put("preamp_repeat_ready",false).put("collimation_environment_ready",false);
            s.put("state_revision", 0).put("source_id",kind+"-test-instance").put("supports_entry_condition",true);
            gateway.addContracts(s,kind);
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
        final Map<String,Set<String>> fixtureStates=new HashMap<>();
        final Map<String,Boolean> readinessOverrides=new HashMap<>();
        boolean online=true; boolean loseResponse,loseRequest; int calls;
        FakeGateway(ObjectMapper json,ApplicationEventPublisher events) {super(json,events,"http://unused",false);this.json=json;}
        @Override public boolean connected(){return online;}
        @Override public JsonNode catalog(){return json.valueToTree(devices.entrySet().stream().map(d -> Map.of("id",d.getKey(),"component",d.getValue(),"connected",true)).toList());}
        void addContracts(ObjectNode snapshot,String kind) {
            if("context".equals(kind)) return;
            try {
                String filename=Map.of("seed","seed-source","shg","shg-injection","preamp","preamp","multipass","multipass").get(kind);
                String xml=Files.readString(Path.of("examples/gxlf/scxml/"+filename+"-state-machine.scxml"));
                var metadata=json.readTree(xml.substring(xml.indexOf("<![CDATA[")+9,xml.indexOf("]]>")));
                var factory=javax.xml.parsers.DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
                var document=factory.newDocumentBuilder().parse(new org.xml.sax.InputSource(new java.io.StringReader(xml)));
                var states=document.getElementsByTagNameNS("*","state");
                snapshot.put("supports_action_contract",true);
                var contracts=snapshot.putObject("action_contracts");
                metadata.path("actions").fields().forEachRemaining(action -> {
                    var contract=contracts.putObject(action.getKey()).put("ready",true);
                    String event=action.getValue().path("request_event").asText();
                    contract.putObject("entry").put("idle",true).put("event",event);
                    // This fake only supplies the built-in fixtures. Guard and
                    // transition semantics are exercised by real device tests.
                    Set<String> allowed=new HashSet<>();
                    for(int i=0;i<states.getLength();i++) {
                        var state=(org.w3c.dom.Element)states.item(i);
                        for(var parent=state;parent!=null && "state".equals(parent.getLocalName());parent=parent.getParentNode() instanceof org.w3c.dom.Element p?p:null)
                            for(var child=parent.getFirstChild();child!=null;child=child.getNextSibling())
                                if(child instanceof org.w3c.dom.Element transition && "transition".equals(transition.getLocalName()))
                                    for(String descriptor:transition.getAttribute("event").split("\\s+"))
                                        if(!descriptor.isBlank() && (event.equals(descriptor) || event.startsWith(descriptor+"."))) allowed.add(state.getAttribute("id"));
                    }
                    fixtureStates.put(snapshot.path("model").asText()+"|"+action.getKey(),allowed);
                });
            } catch(Exception ex) { throw new IllegalStateException(ex); }
        }
        @Override public JsonNode snapshot(String id){
            ObjectNode snapshot=freshSnapshots.getOrDefault(id,snapshots.get(id)).deepCopy();
            snapshot.path("action_contracts").fields().forEachRemaining(action -> {
                var contract=(ObjectNode)action.getValue();
                if(!contract.path("ready").isBoolean()) return;
                boolean allowed=readinessOverrides.getOrDefault(id+"|"+action.getKey(),
                    fixtureStates.getOrDefault(snapshot.path("model").asText()+"|"+action.getKey(),Set.of()).contains(snapshot.path("state_id").asText()));
                boolean busy=snapshot.hasNonNull("active_command_id");
                contract.put("ready",allowed && !busy).put("reason",busy?"设备已有执行中的动作":"当前状态不接受动作："+snapshot.path("state_id").asText());
            });
            return snapshot;
        }
        @Override public JsonNode cached(String id){return snapshots.get(id);}
        @Override public JsonNode execute(String device,String id,String action,Object parameters){
            calls++;
            if(loseRequest){loseRequest=false;throw new TangoGateway.GatewayException(503,"request lost before device acceptance");}
            if(results.containsKey(id)) return results.get(id).deepCopy();
            ObjectNode result=json.createObjectNode().put("command_id",id).put("action",action).put("status","accepted");
            results.put(id,result);snapshots.get(device).put("active_command_id",id);
            snapshots.get(device).put("state_revision", snapshots.get(device).path("state_revision").asLong()+1);
            result.set("parameters",json.valueToTree(parameters));
            if(loseResponse){loseResponse=false;throw new TangoGateway.GatewayException(503,"response lost");}
            return result.deepCopy();
        }
        @Override public JsonNode result(String device,String id){
            if(!results.containsKey(id)) throw new TangoGateway.GatewayException(409,"unknown command or result unavailable");
            return results.get(id).deepCopy();
        }
    }

    String start() { return start(List.of("seed")); }
    String start(List<String> sources) {
        snapshots.forEach((id, state) -> {
            if (id.startsWith("seed_")) {
                if (!"standbyReady".equals(state.path("state_id").asText())) state.put("state_id", "configured");
            } else if (!id.startsWith("context_")) state.put("state_id", "parameterOk");
        });
        return (String)workflows.start("optical_frontend_preparation",sources).get("id");
    }
    String startParameterDispatch() {
        return (String)workflows.start("parameter_dispatch",List.of()).get("id");
    }
    void notice(String id) {
        ObjectNode event=json.createObjectNode().put("device_id",id).put("connected",true);
        event.set("snapshot", snapshots.get(id).deepCopy());
        workflows.event(new TangoGateway.Notice(event));
    }
    void conditions(boolean safety, boolean environment) { ((ObjectNode)snapshots.get("context_01").path("conditions")).put("safety_permit",safety).put("collimation_environment_ready",environment); notice("context_01"); }
    void formalConditions() {
        ObjectNode conditions = (ObjectNode)snapshots.get("context_01").path("conditions");
        for (String key : List.of("safety_permit", "diagnosis_acquisition_ready", "crystal_pose_ready",
                "charge_preparation_ready", "preamp_single_recipe_ready", "measurement_single_recipe_ready",
                "target_measurement_ready", "switch_charge_ready", "charge_complete", "warning_music_stopped",
                "trigger_countdown_started", "pump_trigger_ready", "switch_trigger_ready", "synchronized_triggered",
                "diagnostic_postprocess_ready", "pump_data_collected", "pump_standby_ready",
                "preionization_prepared", "preionization_charge_complete", "preionization_music_stopped",
                "preionization_music_started",
                "preionization_countdown_started", "preionization_pump_trigger_ready", "preionization_sync_triggered",
                "preionization_data_collected", "preionization_standby_ready", "warning_light_off",
                "laser_parameter_collected", "target_analysis_ready", "target_retracted",
                "switch_waveform_collected", "switch_standby_ready", "slab_purge_complete", "shielding_door_open"))
            conditions.put(key, true);
        notice("context_01");
    }
    void addComponent(String id, String kind) {
        devices.put(id, kind);
        ObjectNode s=json.createObjectNode().put("outer_state","ready")
            .put("state_id", kind.equals("seed") ? "qualified" : "functionReady")
            .put("model",Map.of("seed","seed-source-reference-v2","shg","shg-injection-hierarchical-v2",
                "preamp","preamp-hierarchical-v3","multipass","multipass-hierarchical-v3").get(kind));
        s.putNull("active_command_id");
        s.putObject("conditions").put("safety_permit",false)
            .put("preamp_repeat_ready",false).put("collimation_environment_ready",false);
        s.put("state_revision",0).put("source_id",id+"-test-instance").put("supports_entry_condition",true);
        gateway.addContracts(s,kind);
        snapshots.put(id,s);
    }
    void completeAll() { completeDevices(devices.keySet()); }
    void completeDevices(Set<String> selected) {
        for(var item:snapshots.entrySet()) {
            if(!selected.contains(item.getKey())) continue;
            ObjectNode state=item.getValue(); if(!state.hasNonNull("active_command_id"))continue;
            String id=state.path("active_command_id").asText();ObjectNode result=results.get(id);String action=result.path("action").asText();
            result.put("status","succeeded");state.putNull("active_command_id");
            boolean seed=item.getKey().equals("seed_01");
            String next=switch(action) {
                case "power_on_check" -> seed ? "powerOnChecked" : "powerOnOk";
                case "function_check" -> seed ? "qualified" : "functionReady";
                case "parameter_dispatch" -> seed ? "configured" : "parameterOk";
                case "seed_source_emit" -> "stableEmission";
                case "frequency_doubled_emit" -> "stableEmission";
                case "preamp_wakeup" -> "wakeupReady";
                case "coarse_loop" -> "coarseLoopOk";
                case "fine_loop" -> "fineLoopOk";
                case "energy_meter_clear" -> "energyMeterCleared";
                case "laser_parameter_collect" -> "collectionOk";
                case "energy_collect" -> "energyDataCollected";
                case "standby_reset" -> "standbyReady";
                case "optical_collimation" -> "collimationReady";
                case "preamp_repeat" -> "";
                default -> throw new AssertionError(action);
            };
            if(action.equals("preamp_repeat"))((ObjectNode)state.path("conditions")).put("preamp_repeat_ready",true);
            else {state.put("state_id",next);state.put("outer_state",next.equals("stableEmission")?"emitting":Set.of("wakeupReady","coarseLoopOk","fineLoopOk").contains(next)?"wakeup":action.equals("power_on_check")?"notReady":action.equals("optical_collimation")?"collimated":"ready");}
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
        assertThat(results).isEmpty();
        conditions(true,true);
        for(int i=0;i<40 && runtime.createProcessInstanceQuery().processInstanceId(pid).count()>0;i++){workflows.pump();completeAll();}
        assertThat(workflows.view(pid).get("outcome")).isEqualTo("SUCCEEDED");
        assertThat(results).hasSize(6);assertThat(snapshots.get("shg_01").path("state_id").asText()).isEqualTo("parameterOk");
        assertThat(snapshots.get("preamp_01").path("state_id").asText()).isEqualTo("fineLoopOk");
    }
    @Test void configuredSeedStandbyCanStartPreparationWithoutRedispatch() {
        snapshots.get("seed_01").put("state_id","standbyReady");
        String pid=start();untilActivity(pid,"wait_safety");
        assertThat(snapshots.get("seed_01").path("state_id").asText()).isEqualTo("standbyReady");
        assertThat(results).isEmpty();
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
        String pid=start();conditions(true,true);workflows.pump();workflows.pump();completeAll();
        results.values().iterator().next().put("command_id","different-id");workflows.pump();
        assertThat(workflows.view(pid).get("outcome")).isEqualTo("FAILED");
    }
    @Test void recoveredCoordinatorFindsPersistedResultsWithoutEventAndDuplicatesAreHarmless() {
        String pid=start();conditions(true,true);workflows.pump();workflows.pump();completeAll();
        var recovered=new WorkflowService(runtime, repository, history,management,gateway,json,runPolicies,executionPolicies,systemStates,"PT15M","PT45M");
        recovered.pump();assertThat(runtime.createProcessInstanceQuery().processInstanceId(pid).count()).isEqualTo(1);
        for(int i=0;i<3;i++){notice("seed_01");workflows.pump();}
        assertThat(gateway.calls).isGreaterThanOrEqualTo(2);
        assertThat(results).hasSizeGreaterThanOrEqualTo(2);
    }
    @Test void lostPermitStopsDispatchAndDisconnectedStreamCannotReleaseGate() {
        String pid=start();untilActivity(pid,"wait_safety");conditions(true,true);workflows.pump();workflows.pump();
        gateway.online=false;workflows.pump();assertThat(results).hasSize(2);
        gateway.online=true;workflows.pump();workflows.pump();completeAll();workflows.pump();
        conditions(false,true);workflows.pump();
        assertThat(workflows.view(pid).get("outcome")).isEqualTo("FAILED");
        assertThat(workflows.view(pid).get("failureReason").toString()).contains("安全许可");
    }
    @Test void lostAcceptanceResponseRetriesSameCommandAfterFeedback() {
        String pid=start();conditions(true,true);gateway.loseResponse=true;workflows.pump();workflows.pump();
        assertThat(results).hasSize(1);String original=results.keySet().iterator().next();completeAll();
        var recovered=new WorkflowService(runtime,repository,history,management,gateway,json,runPolicies,executionPolicies,systemStates,"PT15M","PT45M");
        recovered.pump();completeAll();
        // Query from a freshly recovered coordinator, without any event hints.
        new WorkflowService(runtime,repository,history,management,gateway,json,runPolicies,executionPolicies,systemStates,"PT15M","PT45M").pump();
        assertThat(runtime.createProcessInstanceQuery().processInstanceId(pid).count()).isEqualTo(1);
        assertThat(results).containsKey(original);assertThat(gateway.calls).isGreaterThanOrEqualTo(2);
    }
    @Test void sourceQualificationLossStopsBothDownstreamBranches() {
        String pid=start();untilActivity(pid,"wait_safety");conditions(true,true);
        untilActivity(pid,"coarse_wait");
        snapshots.get("seed_01").put("outer_state","ready");notice("seed_01");workflows.pump();
        assertThat(workflows.view(pid).get("outcome")).isEqualTo("FAILED");
        assertThat(workflows.view(pid).get("failureReason").toString()).contains("供光资格");
    }
    @Test void commandTimeoutTerminatesWaitAndManualStopIsPersisted() {
        String pid=startParameterDispatch();workflows.pump();
        var timer=management.createTimerJobQuery().processInstanceId(pid).list().stream()
            .filter(j->j.getElementId().equals("dispatch_timeout")).findFirst().orElseThrow();
        var job=management.moveTimerToExecutableJob(timer.getId());management.executeJob(job.getId());
        assertThat(workflows.view(pid).get("outcome")).isEqualTo("FAILED");
        snapshots.values().forEach(s->s.putNull("active_command_id"));
        String next=start();workflows.stop(next,"operator stop");
        assertThat(workflows.view(next).get("failureReason")).isEqualTo("operator stop");
        assertThat(workflows.view(next).get("outcome")).isEqualTo("FAILED");
    }

    @Test void parameterDispatchRunsAfterInitializationAndEnablesPreparation() {
        String pid = startParameterDispatch();
        workflows.pump();
        assertThat(results).hasSize(4);
        assertThat(results.values()).allMatch(r -> r.path("action").asText().equals("parameter_dispatch"));
        completeAll();
        workflows.pump();
        assertThat(workflows.view(pid).get("outcome")).isEqualTo("SUCCEEDED");
        assertThat(snapshots.get("seed_01").path("state_id").asText()).isEqualTo("configured");
    }
    @Test void initializationShowsFourComponentBranchesAndWaitsForEveryInstance() {
        addComponent("preamp_02", "preamp");
        addComponent("multipass_02", "multipass");
        snapshots.values().forEach(s->s.put("outer_state","notReady").put("state_id", s == snapshots.get("seed_01") ? "statusUnknown" : "unchecked"));
        String pid=(String)workflows.start("component_initialization",List.of()).get("id");
        workflows.pump();
        assertThat(results).hasSize(6);
        assertThat(runtime.getActiveActivityIds(pid)).contains("seed_power_wait","shg_power_wait","preamp_power_wait","multipass_power_wait");
        completeDevices(Set.of("seed_01","shg_01","preamp_01","multipass_01"));workflows.pump();
        assertThat(results).hasSize(6);
        completeDevices(Set.of("preamp_02","multipass_02"));
        for(int i=0;i<4 && results.size()<12;i++){workflows.pump();}
        assertThat(results).hasSize(12);
        assertThat(runtime.getActiveActivityIds(pid)).contains("seed_function_wait","shg_function_wait","preamp_function_wait","multipass_function_wait");
        completeDevices(Set.of("seed_01","shg_01","preamp_01","multipass_01"));workflows.pump();
        assertThat(workflows.view(pid).get("outcome")).isEqualTo("RUNNING");
        completeDevices(Set.of("preamp_02","multipass_02"));
        for(int i=0;i<4 && runtime.createProcessInstanceQuery().processInstanceId(pid).count()>0;i++){workflows.pump();}
        assertThat(workflows.view(pid).get("outcome")).isEqualTo("SUCCEEDED");
        assertThat(((List<?>)workflows.view(pid).get("commands"))).hasSize(12);
    }
    @Test void initializationDoesNotRequireASelectedLightSourceOrContextDevice() {
        snapshots.values().forEach(s->s.put("outer_state","notReady").put("state_id", s == snapshots.get("seed_01") ? "statusUnknown" : "unchecked"));
        String pid=(String)workflows.start("component_initialization",List.of()).get("id");
        workflows.pump();
        assertThat(results.keySet()).noneMatch(id -> id.contains("context"));
        assertThat(results.values()).allMatch(r->r.path("action").asText().equals("power_on_check"));
    }
    @Test void initializationUsesTheStandardCommandWaitPathForFailureAndTimeout() {
        snapshots.values().forEach(s->s.put("outer_state","notReady").put("state_id", s == snapshots.get("seed_01") ? "statusUnknown" : "unchecked"));
        String pid=(String)workflows.start("component_initialization",List.of()).get("id");
        workflows.pump();
        String command=snapshots.get("seed_01").path("active_command_id").asText();
        results.get(command).put("status","failed");notice("seed_01");workflows.pump();
        assertThat(workflows.view(pid).get("outcome")).isEqualTo("FAILED");

        snapshots.values().forEach(s->s.put("outer_state","notReady").put("state_id", s == snapshots.get("seed_01") ? "statusUnknown" : "unchecked").putNull("active_command_id"));
        results.clear();
        String timeoutPid=(String)workflows.start("component_initialization",List.of()).get("id");
        workflows.pump();
        var timer=management.createTimerJobQuery().processInstanceId(timeoutPid).list().stream()
            .filter(j->j.getElementId().equals("seed_power_timeout")).findFirst().orElseThrow();
        management.executeJob(management.moveTimerToExecutableJob(timer.getId()).getId());
        assertThat(workflows.view(timeoutPid).get("outcome")).isEqualTo("FAILED");
    }

    String launchInitialization() {
        String pid=startInitialization();workflows.pump();return pid;
    }
    String startInitialization() {
        snapshots.forEach((id,state)->state.put("outer_state","notReady")
            .put("state_id",id.startsWith("seed")?"statusUnknown":"unchecked"));
        return (String)workflows.start("component_initialization",List.of()).get("id");
    }
    void advanceToPowered(String pid) {
        workflows.pump();completeDevices(Set.of("seed_01","shg_01","preamp_01","multipass_01"));
        for(int i=0;i<5;i++)workflows.pump();
        assertThat(systemState(pid).path("state").asText()).isEqualTo("powered");
    }
    JsonNode systemState(String pid) {
        try{return json.readTree((String)workflows.view(pid).get("experimentSystemState"));}
        catch(Exception ex){throw new IllegalStateException(ex);}
    }
    void stateEvent(String device,String outer,String leaf) {
        var state=snapshots.get(device);
        state.put("outer_state",outer).put("state_id",leaf).put("state_revision",state.path("state_revision").asLong()+1);
        notice(device);
    }
    @Test void initializationChecksCurrentDeviceStateAndBusyStatusBeforeSending() {
        String pid=startInitialization();
        gateway.freshSnapshots.put("seed_01",snapshots.get("seed_01").deepCopy().put("active_command_id","manual-action"));
        workflows.pump();
        assertThat(workflows.view(pid).get("outcome")).isEqualTo("FAILED");
        assertThat(workflows.view(pid).get("failureReason").toString()).contains("执行中的动作");
        assertThat(snapshots.get("seed_01").path("active_command_id").isNull()).isTrue();
        assertThat(results).isEmpty();
        assertThat(((List<Map<String,Object>>)workflows.view(pid).get("commands")))
            .filteredOn(c->"seed_01".equals(c.get("commandDevice")))
            .allMatch(c->c.get("commandResult").toString().contains("\"status\":\"rejected\""));
    }
    @Test void initializationRequiresDeviceSideGuardCapabilityRatherThanSilentlyIgnoringContract() {
        String pid=startInitialization();snapshots.get("seed_01").remove("supports_action_contract");workflows.pump();
        assertThat(workflows.view(pid).get("outcome")).isEqualTo("FAILED");
        assertThat(workflows.view(pid).get("failureReason").toString()).contains("未声明支持");
        assertThat(snapshots.get("seed_01").path("active_command_id").isNull()).isTrue();
    }
    @Test void initializationRejectsCurrentStateEvenIfCachedStateWouldAllowEntry() {
        String pid=startInitialization();
        gateway.freshSnapshots.put("seed_01",snapshots.get("seed_01").deepCopy().put("state_id","qualified").put("outer_state","ready"));
        workflows.pump();
        assertThat(workflows.view(pid).get("outcome")).isEqualTo("FAILED");
        assertThat(workflows.view(pid).get("failureReason").toString()).contains("qualified");
        assertThat(snapshots.get("seed_01").path("active_command_id").isNull()).isTrue();
    }
    @Test void initializationUsesDeviceReadinessWithoutCopyingIntrinsicStatesIntoBpmn() {
        String pid=startInitialization();
        var seed=snapshots.get("seed_01");
        seed.remove("supports_entry_condition");
        gateway.readinessOverrides.put("seed_01|power_on_check",true);
        workflows.pump();
        assertThat(workflows.view(pid).get("outcome")).isEqualTo("RUNNING");
        assertThat(results).hasSize(4);
        assertThat(results.values()).allMatch(r->!r.path("parameters").has("entry_condition"));
    }
    @Test void missingOrMalformedActionReadinessCannotSilentlyAllowDispatch() {
        for(String variant:List.of("missing","unknown","invalid")) {
            String pid=startInitialization();
            var contract=(ObjectNode)snapshots.get("seed_01").path("action_contracts").path("power_on_check");
            if("missing".equals(variant)) snapshots.get("seed_01").remove("action_contracts");
            else if("unknown".equals(variant)) contract.remove("ready");
            else contract.put("ready","true");
            workflows.pump();
            assertThat(workflows.view(pid).get("outcome")).isEqualTo("FAILED");
            assertThat(workflows.view(pid).get("failureReason").toString()).contains("就绪状态未知");
            assertThat(snapshots.get("seed_01").hasNonNull("active_command_id")).isFalse();
            setup();
        }
    }
    @Test void experimentMayTightenDeviceContractAndMustNotIgnoreUnsupportedExtraConditions() throws Exception {
        String xml=Files.readString(Path.of("examples/gxlf/bpmn/component_initialization.bpmn20.xml"))
            .replaceFirst("<act:actionContract />","<act:actionContract /><act:entryCondition states=\"powerOnChecked\" idle=\"true\" />");
        var deployment=repository.createDeployment().addString("extra-condition.bpmn20.xml",xml).deploy();
        try {
            for(boolean supported:List.of(true,false)) {
                String pid=startInitialization();
                if(!supported) snapshots.get("seed_01").remove("supports_entry_condition");
                workflows.pump();
                assertThat(workflows.view(pid).get("outcome")).isEqualTo("FAILED");
                assertThat(workflows.view(pid).get("failureReason").toString())
                    .contains(supported?"实验附加进入条件":"未声明支持受理时进入条件");
                assertThat(snapshots.get("seed_01").hasNonNull("active_command_id")).isFalse();
                setup();
            }
        } finally { repository.deleteDeployment(deployment.getId(),true); }
    }
    @Test void guardedCommandWithLostReplyIsQueriedAfterControllerRecoveryWithoutResubmission() {
        String pid=startInitialization();gateway.loseResponse=true;workflows.pump();
        assertThat(results).hasSize(1);
        String original=results.keySet().iterator().next();
        assertThat(results.get(original).path("parameters").has("entry_condition")).isFalse();
        assertThat((List<Map<String,Object>>)workflows.view(pid).get("commands"))
            .allMatch(c->Boolean.TRUE.equals(c.get("deviceActionContract")));
        completeAll();
        new WorkflowService(runtime,repository,history,management,gateway,json,runPolicies,executionPolicies,systemStates,"PT15M","PT45M").pump();
        assertThat(results.get(original).path("status").asText()).isEqualTo("succeeded");
        assertThat(gateway.calls).as("Guarded commands must not be submitted a second time").isEqualTo(results.size());
        assertThat(workflows.view(pid).get("outcome")).isEqualTo("RUNNING");
    }
    @Test void uncertainGuardedSubmissionDoesNotBecomeARejectionOrStartANewAction() {
        String pid=startInitialization();gateway.loseRequest=true;workflows.pump();
        assertThat(gateway.calls).isEqualTo(1);assertThat(results).isEmpty();
        new WorkflowService(runtime,repository,history,management,gateway,json,runPolicies,executionPolicies,systemStates,"PT15M","PT45M").pump();
        assertThat(gateway.calls).isEqualTo(1);assertThat(results).isEmpty();
        assertThat(workflows.view(pid).get("outcome")).isEqualTo("RUNNING");
        assertThat(workflows.view(pid).get("communicationError").toString()).contains("result unavailable");
        workflows.stop(pid,"uncertain submission; verify device manually");
        assertThat((List<?>)workflows.view(pid).get("unresolvedDeviceActions")).hasSize(1);
        assertThat((List<Map<String,Object>>)workflows.view(pid).get("commands"))
            .filteredOn(c->Boolean.TRUE.equals(c.get("dispatchAttempted"))).noneMatch(c->c.containsKey("commandResult"));
    }
    @Test void systemStateTransitionsFromUninitializedThroughPoweredToInitialized() throws Exception {
        String pid=launchInitialization();
        assertThat(systemState(pid).path("state").asText()).isEqualTo("uninitialized");
        advanceToPowered(pid);
        completeDevices(Set.of("seed_01","shg_01","preamp_01","multipass_01"));
        for(int i=0;i<8&&"RUNNING".equals(workflows.view(pid).get("outcome"));i++)workflows.pump();
        assertThat(systemState(pid).path("state").asText()).isEqualTo("initialized");
        assertThat(systemState(pid).path("integrity").asText()).isEqualTo("valid");
        assertThat(systemState(pid).path("history").findValuesAsText("transition"))
            .containsExactly("powerChecksVerified","functionChecksVerified");
    }
    @Test void systemStateViolationIsLatchedAndStopsActiveProcessBranches() {
        String pid=launchInitialization();advanceToPowered(pid);
        stateEvent("seed_01","notReady","faulted");workflows.pump();
        assertThat(workflows.view(pid).get("outcome")).isEqualTo("FAILED");
        assertThat(systemState(pid).path("integrity").asText()).isEqualTo("violated");
        assertThat(systemState(pid).path("reason").asText()).contains("seed_01");
        assertThat((List<Map<String,Object>>)workflows.view(pid).get("unresolvedDeviceActions")).hasSize(4);
    }
    @Test void transientSystemStateViolationIsObservedEvenIfDeviceRecoversBeforeCoordinatorPump() {
        String pid=launchInitialization();advanceToPowered(pid);
        stateEvent("seed_01","notReady","faulted");stateEvent("seed_01","ready","powerOnChecked");
        workflows.pump();
        assertThat(workflows.view(pid).get("outcome")).isEqualTo("FAILED");
        assertThat(systemState(pid).path("integrity").asText()).isEqualTo("violated");
    }
    @Test void disconnectedSystemStateEvidenceBlocksFurtherOperation() {
        String pid=launchInitialization();advanceToPowered(pid);gateway.online=false;workflows.pump();
        assertThat(workflows.view(pid).get("outcome")).isEqualTo("FAILED");
        assertThat(systemState(pid).path("integrity").asText()).isEqualTo("unknown");
    }
    @Test void systemStateCannotAdvanceWhenAnyParticipantFailsItsDeclaredCondition() {
        String pid=launchInitialization();workflows.pump();
        completeDevices(Set.of("seed_01","shg_01","preamp_01"));
        for(int i=0;i<6;i++)workflows.pump();
        assertThat(systemState(pid).path("state").asText()).isEqualTo("uninitialized");
        assertThat(workflows.view(pid).get("outcome")).isEqualTo("RUNNING");
    }

    @Test void lifecycleComposesInitializationAndPreparationForModeledComponents() {
        snapshots.values().forEach(s -> s.put("outer_state", "notReady")
            .put("state_id", s == snapshots.get("seed_01") ? "statusUnknown" : "unchecked"));
        conditions(true, true); formalConditions();
        String pid = (String)workflows.start("emission_task_lifecycle", List.of("seed")).get("id");
        JsonNode liveView = json.valueToTree(workflows.view(pid));
        assertThat(liveView.path("participants").findValuesAsText("id"))
            .containsExactlyInAnyOrder("seed_01", "shg_01", "preamp_01", "multipass_01", "context_01");
        assertThat(liveView.path("children").get(0).path("key").asText()).isEqualTo("component_initialization");
        assertThat(liveView.path("children").get(0).path("activeActivities").toString()).contains("seed_power_wait");
        assertThat(workflows.runXml(liveView.path("children").get(0).path("id").asText()))
            .contains("id=\"component_initialization\"");
        for (int i = 0; i < 120 && runtime.createProcessInstanceQuery().processInstanceId(pid).count() > 0; i++) {
            workflows.pump();
            completeAll();
        }
        assertThat(workflows.view(pid).get("outcome")).isEqualTo("SUCCEEDED");
        JsonNode completedView = json.valueToTree(workflows.view(pid));
        assertThat(completedView.path("children")).hasSize(5);
        assertThat(completedView.path("children").get(4).path("commands")).hasSize(3);
        assertThat(results.values()).extracting(r -> r.path("action").asText())
            .contains("power_on_check", "function_check", "parameter_dispatch", "seed_source_emit",
                "preamp_wakeup", "coarse_loop", "fine_loop", "optical_collimation");
    }

    @Test void lifecyclePropagatesInitializationFailureFromCalledProcess() {
        snapshots.values().forEach(s -> s.put("outer_state", "notReady")
            .put("state_id", s == snapshots.get("seed_01") ? "statusUnknown" : "unchecked"));
        String pid = (String)workflows.start("emission_task_lifecycle", List.of("seed")).get("id");
        workflows.pump();
        ObjectNode failed = results.values().stream()
            .filter(r -> r.path("action").asText().equals("power_on_check"))
            .findFirst().orElseThrow();
        failed.put("status", "failed");
        String device = snapshots.entrySet().stream()
            .filter(entry -> entry.getValue().path("active_command_id").asText().equals(failed.path("command_id").asText()))
            .map(Map.Entry::getKey).findFirst().orElseThrow();
        snapshots.get(device).putNull("active_command_id");
        failed.set("snapshot", snapshots.get(device).deepCopy());
        notice(device);
        workflows.pump();
        assertThat(workflows.view(pid).get("outcome")).isEqualTo("FAILED");
    }

    @Test void formalEmissionRunsSecondFineLoopAndMeterClearBeforeTimedTriggerEvidence() {
        snapshots.get("seed_01").put("outer_state", "emitting").put("state_id", "stableEmission");
        snapshots.get("shg_01").put("outer_state", "ready").put("state_id", "parameterOk");
        snapshots.get("preamp_01").put("outer_state", "wakeup").put("state_id", "fineLoopOk");
        snapshots.get("multipass_01").put("outer_state", "collimated").put("state_id", "collimationReady");
        formalConditions();
        String pid = (String)workflows.start("formal_emission", List.of("seed")).get("id");
        for (int i = 0; i < 80 && runtime.createProcessInstanceQuery().processInstanceId(pid).count() > 0; i++) {
            workflows.pump();
            completeAll();
        }
        assertThat(workflows.view(pid).get("outcome")).isEqualTo("SUCCEEDED");
        assertThat(results.values()).extracting(r -> r.path("action").asText())
            .contains("fine_loop", "energy_meter_clear");
    }

    @Test void postEmissionRunsComponentActionsAndWaitsForAllExternalBranches() {
        snapshots.get("seed_01").put("outer_state", "emitting").put("state_id", "stableEmission");
        snapshots.get("shg_01").put("outer_state", "ready").put("state_id", "parameterOk");
        snapshots.get("preamp_01").put("outer_state", "wakeup").put("state_id", "energyMeterCleared");
        snapshots.get("multipass_01").put("outer_state", "collimated").put("state_id", "collimationReady");
        formalConditions();
        String pid = (String)workflows.start("post_emission_processing", List.of("seed")).get("id");
        for (int i = 0; i < 80 && runtime.createProcessInstanceQuery().processInstanceId(pid).count() > 0; i++) {
            workflows.pump();
            completeAll();
        }
        assertThat(workflows.view(pid).get("outcome")).isEqualTo("SUCCEEDED");
        assertThat(results.values()).extracting(r -> r.path("action").asText())
            .contains("laser_parameter_collect", "energy_collect", "standby_reset");
        assertThat(snapshots.get("seed_01").path("state_id").asText()).isEqualTo("collectionOk");
        assertThat(snapshots.get("preamp_01").path("state_id").asText()).isEqualTo("standbyReady");
    }

    @Test void gxlfDefaultSourcesAreResolvedByTheScenarioPolicy() {
        String pid = start(null);
        assertThat(runtime.getVariable(pid, "scenarioId")).isEqualTo("gxlf");
        assertThat(runtime.getVariable(pid, "sourceTargets").toString()).contains("seed_01").doesNotContain("shg_01");
        workflows.stop(pid, "test stop");
    }

    @Test void gxlfModelIdentityChecksRunBeforeCreatingTheProcess() {
        snapshots.get("seed_01").put("model", "another-model");
        assertThatThrownBy(this::start).hasMessageContaining("组件模型不匹配");
        assertThat(runtime.createProcessInstanceQuery().count()).isZero();
    }

}
