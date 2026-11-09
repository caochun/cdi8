package cn.cdi8.workbench.workflow.facility;

import cn.cdi8.facility.contract.FacilityComponentModels;
import cn.cdi8.facility.contract.FacilityStateProjectionModel;
import cn.cdi8.workbench.integration.facility.FacilityStateClient;

import cn.cdi8.workbench.model.ModelValidation;
import cn.cdi8.facility.contract.SystemDefinitionModel;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class FacilityContractTest {
    private String minimal()throws Exception{return Files.readString(Path.of("examples/minimal/process.bpmn20.xml"));}
    private String declaration(String extension)throws Exception {
        return minimal().replaceAll("<bpmn:extensionElements>.*?</bpmn:extensionElements>",
            java.util.regex.Matcher.quoteReplacement("<bpmn:extensionElements>"+extension+"</bpmn:extensionElements>"));
    }
    private void validate(String xml)throws Exception {FacilityBpmnModel.validateProcess(ModelValidation.parse(xml));}
    @ParameterizedTest @ValueSource(strings={
        "<fac:requiers guardRef='triggerAllowed'/>",
        "<fac:requires guardRef='triggerAllowed' maintian='execution'/>",
        "<fac:requires guardRef='triggerAllowed'/><fac:requires guardRef='triggerAllowed'/>",
        "<fac:actions/>",
        "<fac:actions><fac:action typeRef='seed' capabilityRef='power-on-check' collectionVar='seedTargets' expectedStateRef='missing'/></fac:actions>",
        "<fac:actions><fac:action typeRef='seed' capabilityRef='power-on-check' collectionVar='seedTargets' expectedStateRef='ready'/></fac:actions>",
        "<fac:actions><fac:action typeRef='seed' capabilityRef='power-on-check' collectionVar='seedTargets' expectedStateRef='powerOnChecked configured'/></fac:actions>",
        "<fac:actions><fac:action typeRef='seed' capabilityRef='power-on-check' collectionVar='seedTargets' expectedStateRef='powerOnChecked' maintian='execution'/></fac:actions>",
        "<fac:actions><fac:action typeRef='seed' capabilityRef='power-on-check' collectionVar='seedTargets' expectedStateRef='powerOnChecked'><fac:unknownSafetyRule/></fac:action></fac:actions>",
        "<fac:action typeRef='seed' capabilityRef='power-on-check' collectionVar='seedTargets' expectedStateRef='powerOnChecked'/>",
        "<fac:capability ownerRef='pump' capabilityRef='charge'/>",
        "<run:stage xmlns:run='https://cdi8.cn/shot-run/v1' value='created'/><run:stage xmlns:run='https://cdi8.cn/shot-run/v1' value='completed'/>",
        "<run:records xmlns:run='https://cdi8.cn/shot-run/v1' kind='command-result'/>"
    }) void rejectsUnknownAmbiguousOrNonExecutableExtensions(String extension)throws Exception {
        assertThrows(IllegalArgumentException.class,()->validate(declaration(extension)));
    }
    @Test void checksRawXmlBeforeConverterCanDropMisplacedNodes()throws Exception {
        String xml=minimal();
        for(String bad:List.of(
            xml.replace("<bpmn:startEvent id=\"start\" />","<bpmn:startEvent id=\"start\"><fac:requires guardRef='triggerAllowed'/></bpmn:startEvent>"),
            xml.replace("<bpmn:process id=", "<bpmn:process fac:requires='triggerAllowed' id="),
            xml.replace("https://cdi8.cn/facility/v1","https://cdi8.cn/facility/v2"),
            xml.replace("fac:","other:").replace("xmlns:fac=","xmlns:other=").replace("https://cdi8.cn/facility/v1","https://cdi8.cn/facilty/v1"),
            xml.replace("<bpmn:extensionElements>","<bpmn:extensionElements><unknown xmlns='urn:example'><fac:requires guardRef='triggerAllowed'/></unknown>")))
            assertThrows(IllegalArgumentException.class,()->ModelValidation.parse(bad));
    }
    @Test void operationAdmissionMustBeDeclaredByTheFacilityAndReplacesLegacyGuardEntry()throws Exception {
        String process=minimal().replace("<bpmn:extensionElements><fac:actions>",
            "<bpmn:extensionElements><fac:operation operationRef='trigger'/><fac:actions>");
        assertDoesNotThrow(()->validate(process));
        assertThrows(IllegalArgumentException.class,()->validate(process.replace("operationRef='trigger'","operationRef='missing-operation'")));
        assertThrows(IllegalArgumentException.class,()->validate(process.replace("<fac:operation operationRef='trigger'/>",
            "<fac:operation operationRef='trigger'/><fac:operation operationRef='trigger'/>") ));
        assertThrows(IllegalArgumentException.class,()->validate(process.replace("<fac:operation operationRef='trigger'/>",
            "<fac:operation operationRef='trigger'/><fac:requires guardRef='triggerAllowed'/>") ));
    }
    @Test void allExecutableAndDesignExamplesValidate()throws Exception {
        validate(minimal());
        for(String dir:List.of("processes","design"))try(var files=Files.list(Path.of("examples/gxlf",dir))) {
            for(Path file:files.filter(p->p.toString().endsWith(".bpmn20.xml")).toList()) {
                var xml=Files.readString(file);validate(xml);
                var errors=ModelValidation.engineErrors(ModelValidation.parse(xml));
                assertTrue(errors.isEmpty(),file+" / "+errors);
                if("design".equals(dir)) {
                    assertFalse(ModelValidation.parse(xml).getMainProcess().isExecutable());
                    assertFalse(xml.contains("shotRuns.record"),"业务设计不得以占位表达式写成功证据");
                }
            }
        }
    }
    @Test void targetDesignDeclaresTheSystemRunContract()throws Exception {
        String xml=Files.readString(Path.of("examples/gxlf/design/gxlf_shot_run_target.bpmn20.xml"));
        var model=ModelValidation.parse(xml);
        assertFalse(model.getMainProcess().isExecutable());
        assertTrue(xml.contains("id=\"supervised_run\""));
        assertTrue(xml.contains("id=\"optical_hold\""));
        assertTrue(xml.contains("id=\"charging_hold\""));
        assertTrue(xml.contains("id=\"system_supervision_boundary\""));
        assertTrue(xml.contains("kind=\"emission-result\""));
        assertTrue(xml.contains("contract-unresolved"));
        assertTrue(xml.contains("external-interface-required"));
        assertTrue(xml.contains("manual-confirmation-required"));
        assertTrue(ModelValidation.engineErrors(model).isEmpty());
        FacilityBpmnModel.validateProcess(model);
    }

    @Test void onlyExplicitlyNonExecutableDesignsMayOmitServiceImplementations()throws Exception {
        String design=Files.readString(Path.of("examples/gxlf/design/gxlf_shot_run_target.bpmn20.xml"));
        assertTrue(ModelValidation.engineErrors(ModelValidation.parse(design)).isEmpty());
        assertFalse(ModelValidation.engineErrors(ModelValidation.parse(design.replace("isExecutable=\"false\"","isExecutable=\"true\""))).isEmpty());
        String broken=design.replace("sourceRef=\"supervised_start\"","sourceRef=\"missing_node\"");
        assertFalse(ModelValidation.engineErrors(ModelValidation.parse(broken)).isEmpty(),"设计模型仍须检查结构引用");
    }
    @Test void checksModelPathsAndCapabilityActionReferences()throws Exception {
        String xml=FacilityTestModels.definition();
        for(String changed:List.of(xml.replace("components/seed-source-state-machine.scxml","components/missing.scxml"),
            xml.replace("components/seed-source-state-machine.scxml","../seed-source-state-machine.scxml"),
            xml.replace("actionRef=\"power_on_check\"","actionRef=\"missing_action\"")))
            assertThrows(IllegalArgumentException.class,()->FacilityComponentModels.bundled(SystemDefinitionModel.parse(changed)));
    }
    @Test void checksProjectedLeavesAndOuterStatesAgainstScxml()throws Exception {
        var definition=SystemDefinitionModel.parse(FacilityTestModels.definition());var models=FacilityComponentModels.bundled(definition);
        String xml=FacilityTestModels.projection();
        for(String changed:List.of(xml.replace("states=\"powerOnChecked\"","states=\"missing\""),
            xml.replace("outerStates=\"ready\"","outerStates=\"missing\""))) {
            assertNotEquals(xml,changed);
            assertThrows(IllegalArgumentException.class,()->FacilityStateProjectionModel.parse(changed).validateComponents(definition,models));
        }
    }
    @Test void validatesSelectedModelEvenWhenBundledModelIsValid()throws Exception {
        var definition=SystemDefinitionModel.parse(FacilityTestModels.definition());var models=FacilityComponentModels.bundled(definition);
        String seed=models.xml().get("seed");
        // The selected model is a valid standalone SCXML but no longer implements a facility capability.
        String renamed=seed.replace("\"power_on_check\":", "\"renamed_check\":");assertNotEquals(seed,renamed);
        new cn.cdi8.component.contract.ComponentContract(renamed);
        assertThrows(IllegalArgumentException.class,()->models.withSelected(definition,Map.of("seed",renamed)));
        String changedState=seed.replace("powerOnChecked","changedState");
        var selected=models.withSelected(definition,Map.of("seed",changedState));
        assertThrows(IllegalArgumentException.class,()->FacilityStateProjectionModel.parse(FacilityTestModels.projection()).validateComponents(definition,selected));
    }
    @Test void validationApiResolvesPublishedComponentsWithoutChangingActiveModels(@org.junit.jupiter.api.io.TempDir Path directory)throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        var store=new cn.cdi8.workbench.model.ModelStore(json,directory.toString());store.initialize();
        var gateway=new cn.cdi8.workbench.integration.facility.FacilityClient(json,event->{},"http://unused",false) {
            @Override public com.fasterxml.jackson.databind.JsonNode request(String method,String path,Object body){throw new AssertionError("静态校验不得访问设备");}
        };
        try {
            var states=org.mockito.Mockito.mock(FacilityStateClient.class);
            var validation=new ModelValidation(json,gateway,states,store);
            assertTrue(validation.validate("system",FacilityTestModels.definition()).path("valid").asBoolean());
            assertFalse(validation.validate("system",FacilityTestModels.definition().replace("components/seed-source-state-machine.scxml","components/missing.scxml")).path("valid").asBoolean());
            assertFalse(validation.validate("system",FacilityTestModels.definition().replace("actionRef=\"power_on_check\"","actionRef=\"missing\"")).path("valid").asBoolean());
            assertFalse(validation.validate("system",FacilityTestModels.projection().replace("outerStates=\"ready\"","outerStates=\"missing\"")).path("valid").asBoolean());
            assertFalse(validation.validate("bpmn",minimal().replace("expectedStateRef=\"powerOnChecked\"","expectedStateRef=\"missing\"")).path("valid").asBoolean());
            org.mockito.Mockito.verifyNoInteractions(states);
        }finally{gateway.close();}
    }
}
