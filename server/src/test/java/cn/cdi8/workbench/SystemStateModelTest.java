package cn.cdi8.workbench;

import cn.cdi8.workbench.model.ModelValidation;
import cn.cdi8.workbench.systemstate.SystemStateModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SystemStateModelTest {
    @Test void fullLifecycleDeclarationUsesOnlyFactsPresentInTheFourteenCurrentDeviceModels() throws Exception {
        var model=SystemStateModel.parse(Files.readString(Path.of("examples/gxlf/system/gxlf-experiment.system.xml")));
        Map<String,JsonNode> components=new HashMap<>();
        try(var files=Files.list(Path.of("examples/gxlf/components"))) {
            for(var file:files.filter(path->path.toString().endsWith(".scxml")).toList()) {
                var factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);
                var document=factory.newDocumentBuilder().parse(file.toFile());
                var data=document.getElementsByTagNameNS("*","data");
                for(int i=0;i<data.getLength();i++)if("componentMetadata".equals(((org.w3c.dom.Element)data.item(i)).getAttribute("id"))) {
                    String component=file.getFileName().toString().replace("-state-machine.scxml","");
                    if(component.equals("seed-source"))component="seed";
                    if(component.equals("shg-injection"))component="shg";
                    components.put(component,new ObjectMapper().readTree(data.item(i).getTextContent()));
                }
            }
        }
        assertThat(components).hasSize(14);
        var predicates=new ArrayList<SystemStateModel.Predicate>();
        model.states().forEach(state->predicates.addAll(state.allPredicates()));
        model.facts().forEach(fact->predicates.addAll(fact.predicates()));
        for(var predicate:predicates)for(String component:predicate.components()) {
            assertThat(components).containsKey(component);
            var metadata=components.get(component);
            for(String state:predicate.states())assertThat(metadata.path("inner_labels").has(state))
                .as("%s leaf %s",component,state).isTrue();
            for(String state:predicate.outerStates())assertThat(metadata.path("outer_labels").has(state))
                .as("%s outer %s",component,state).isTrue();
        }
        assertThat(model.state("initialized").predicates()).hasSize(14);
        assertThat(model.state("powered").entryPredicates()).hasSize(14);
        assertThat(model.state("charged").predicates()).anySatisfy(predicate->{
            assertThat(predicate.component()).isEqualTo("pump");assertThat(predicate.states()).containsExactly("chargeComplete");
        });
        for(String phase:List.of("charging","charged")) {
            assertThat(model.state(phase).predicates()).anySatisfy(predicate->{
                assertThat(predicate.component()).isEqualTo("synchronization");
                assertThat(predicate.states()).containsExactly("singleConfigured");
            });
            assertThat(model.state(phase).predicates()).anySatisfy(predicate->{
                assertThat(predicate.component()).isEqualTo("switch-driver");
                assertThat(predicate.states()).containsExactly("chargeComplete");
            });
        }
        assertThat(model.state("released").predicates()).anySatisfy(predicate->{
            assertThat(predicate.component()).isEqualTo("pump");assertThat(predicate.states()).containsExactly("standbyReady");
        });
        assertThat(model.anomalies()).extracting(SystemStateModel.Anomaly::category)
            .contains("deviceFault","safetyInterlock","stateConditionLost","unknown","commandOutcomeUnknown");
        assertThat(model.anomaly("safetyInterlock").states()).containsExactly("interlockTripped");
        // An unresolved evidence gap must never leave an executable route to emission.
        Set<String> reachable=new HashSet<>(Set.of(model.initial()));boolean changed;
        do {
            changed=false;
            for(var transition:model.transitions())if(transition.blockedReason().isBlank()&&reachable.contains(transition.from()))
                changed|=reachable.add(transition.to());
        }while(changed);
        assertThat(reachable).containsExactlyInAnyOrder("uninitialized","powered","initialized","configuring");
        assertThat(model.transition("safetyVerified").blockedReason()).contains("人员","钛泵");
        assertThat(model.transition("countdownStarted").blockedReason()).contains("T+3/T+5");
    }

    @Test void namedFactsExpandWithSeparateEntryAndInvariantScopes() {
        var model=SystemStateModel.parse("<system-state-model xmlns=\""+SystemStateModel.NS+"\" id=\"test\" initial=\"initial\">"+
            "<state id=\"initial\" label=\"Initial\"/><state id=\"ready\" label=\"Ready\">"+
            "<use fact=\"light\"/><use fact=\"light\" scope=\"entry\"/></state>"+
            "<fact id=\"light\" label=\"Stable light\"><require collection=\"sourceTargets\" component=\"seed shg\" states=\"stableEmission\"/></fact>"+
            "</system-state-model>");
        assertThat(model.state("ready").predicates()).hasSize(1);
        assertThat(model.state("ready").entryPredicates()).hasSize(1);
        assertThat(model.state("ready").predicates().get(0).components()).containsExactlyInAnyOrder("seed","shg");
    }

    @Test void invalidFactReferencesScopesAndEntryOnlyStatesAreRejected() {
        String prefix="<system-state-model xmlns=\""+SystemStateModel.NS+"\" id=\"test\" initial=\"initial\"><state id=\"initial\" label=\"Initial\"/>";
        for(String condition:List.of("<use fact=\"missing\"/>",
            "<require collection=\"targets\" component=\"seed\" states=\"ok\" scope=\"typo\"/>",
            "<require collection=\"targets\" component=\"seed\" states=\"ok\" scope=\"entry\"/>"))
            assertThatThrownBy(()->SystemStateModel.parse(prefix+"<state id=\"ready\" label=\"Ready\">"+condition+"</state></system-state-model>"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void fourteenDeviceInitializationBpmnUsesOneBarrierBetweenPowerAndFunctionChecks() throws Exception {
        var bpmn=ModelValidation.parse(Files.readString(Path.of("examples/gxlf/bpmn/gxlf_system_initialization.bpmn20.xml")));
        SystemStateModel.validateProcess(bpmn);
        var process=bpmn.getMainProcess();
        assertThat(SystemStateModel.attr(SystemStateModel.elements(process,"systemModel").get(0),"ref")).isEqualTo("gxlf-experiment");
        var scope=(org.flowable.bpmn.model.SubProcess)process.getFlowElement("normal");
        var power=(org.flowable.bpmn.model.ServiceTask)scope.getFlowElement("power_checks");
        var function=(org.flowable.bpmn.model.ServiceTask)scope.getFlowElement("function_checks");
        assertThat(power.getLoopCharacteristics().isSequential()).isFalse();
        assertThat(function.getLoopCharacteristics().getInputDataItem()).isEqualTo("${allComponents}");
        assertThat(power.getOutgoingFlows().get(0).getTargetRef()).isEqualTo("report_powered");
        assertThat(((org.flowable.bpmn.model.FlowNode)scope.getFlowElement("report_powered")).getOutgoingFlows().get(0).getTargetRef()).isEqualTo("function_checks");
    }
    @Test void gxlfDeclarationDefinesStatesPredicatesAndTransitions() throws Exception {
        var model=SystemStateModel.parse(Files.readString(Path.of("examples/gxlf/system/gxlf-initialization.system.xml")));
        assertThat(model.id()).isEqualTo("gxlf-initialization");
        assertThat(model.initial()).isEqualTo("uninitialized");
        assertThat(model.transition("powerChecksVerified").to()).isEqualTo("powered");
        assertThat(model.state("initialized").predicates()).hasSize(4);
        assertThat(model.state("uninitialized").predicates().get(0).states())
            .contains("statusUnknown", "powerOnChecked");
    }

    @Test void rejectsTransitionsToUnknownStatesAndUnobservableNonInitialStates() {
        String header="<system-state-model xmlns=\""+SystemStateModel.NS+"\" id=\"test\" initial=\"initial\">";
        assertThatThrownBy(()->SystemStateModel.parse(header+
            "<state id=\"initial\" label=\"Initial\"/><state id=\"ready\" label=\"Ready\"/>"+
            "<transition id=\"go\" from=\"initial\" to=\"missing\"/></system-state-model>"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("必须声明设备条件");
        assertThatThrownBy(()->SystemStateModel.parse(header+
            "<state id=\"initial\" label=\"Initial\"/><state id=\"ready\" label=\"Ready\"><require collection=\"targets\" component=\"seed\" states=\"ok\"/></state>"+
            "<transition id=\"go\" from=\"initial\" to=\"missing\"/></system-state-model>"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("引用未定义状态");
    }

    @Test void oldQualificationExtensionsAreRejectedWithMigrationGuidance() throws Exception {
        String xml=Files.readString(Path.of("examples/gxlf/bpmn/component_initialization.bpmn20.xml"))
            .replace("<sys:systemModel ref=\"gxlf-initialization\" />",
                "<sys:systemModel ref=\"gxlf-initialization\" /><ctl:qualification xmlns:ctl=\"https://cdi8.cn/control/qualification/v1\" id=\"legacy\" />");
        var bpmn=ModelValidation.parse(xml);
        assertThatThrownBy(()->SystemStateModel.validateProcess(bpmn))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("资格扩展已移除");
    }
}
