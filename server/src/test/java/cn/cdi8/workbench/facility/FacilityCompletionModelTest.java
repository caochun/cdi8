package cn.cdi8.workbench.facility;

import cn.cdi8.workbench.model.ModelValidation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class FacilityCompletionModelTest {
    private String process(String declaration) {
        return """
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
              xmlns:fac="https://cdi8.cn/facility/v1" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="test">
              <process id="test" isExecutable="true">
                <serviceTask id="wait" flowable:delegateExpression="${facilityActions}">
                  <extensionElements>%s</extensionElements>
                </serviceTask>
              </process>
            </definitions>
            """.formatted(declaration);
    }

    @ParameterizedTest @ValueSource(strings={
        "<fac:completion factRef='allPowerChecked' timeout='PT2M'/>",
        "<fac:completion guardRef='simulationParameterDispatchAllowed' timeout='PT0.1S'/>"
    }) void supportsFactsAndComposedGuards(String declaration) throws Exception {
        assertDoesNotThrow(()->FacilityBpmnModel.validateProcess(ModelValidation.parse(process(declaration))));
    }

    @ParameterizedTest @ValueSource(strings={
        "<fac:completion factRef='missing' timeout='PT2M'/>",
        "<fac:completion guardRef='missing' timeout='PT2M'/>",
        "<fac:completion factRef='allPowerChecked' guardRef='triggerAllowed' timeout='PT2M'/>",
        "<fac:completion timeout='PT2M'/>",
        "<fac:completion factRef='allPowerChecked'/>",
        "<fac:completion factRef='allPowerChecked' timeout='PT0S'/>",
        "<fac:completion factRef='allPowerChecked' timeout='-PT1S'/>",
        "<fac:completion factRef='allPowerChecked' timeout='two minutes'/>",
        "<fac:completion factRef='allPowerChecked' timeout='PT2M' unknown='allow'/>",
        "<fac:completion factRef='allPowerChecked' timeout='PT2M'><fac:ignore/></fac:completion>",
        "<fac:completion factRef='allPowerChecked' timeout='PT2M'/><fac:completion factRef='allFunctionReady' timeout='PT2M'/>"
    }) void rejectsAmbiguousOrUnsafeCompletionDeclarations(String declaration) {
        assertThrows(IllegalArgumentException.class,()->FacilityBpmnModel.validateProcess(ModelValidation.parse(process(declaration))));
    }

    @Test void cannotSilentlyIgnoreCompletionOnOtherActivities() {
        String xml=process("<fac:completion factRef='allPowerChecked' timeout='PT2M'/>");
        assertThrows(IllegalArgumentException.class,()->FacilityBpmnModel.validateProcess(ModelValidation.parse(xml.replace("serviceTask","receiveTask"))));
        assertThrows(IllegalArgumentException.class,()->FacilityBpmnModel.validateProcess(ModelValidation.parse(xml.replace("${facilityActions}","${otherDelegate}"))));
        assertThrows(IllegalArgumentException.class,()->FacilityBpmnModel.validateProcess(ModelValidation.parse(process(""))));
    }
}
