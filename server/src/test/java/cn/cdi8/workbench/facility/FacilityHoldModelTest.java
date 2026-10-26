package cn.cdi8.workbench.facility;

import cn.cdi8.workbench.model.ModelValidation;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FacilityHoldModelTest {
    String example()throws Exception{return Files.readString(Path.of("examples/gxlf/processes/gxlf_stage_hold.bpmn20.xml"));}
    void validate(String xml)throws Exception{FacilityBpmnModel.validateProcess(ModelValidation.parse(xml));}

    @Test void executableExampleResolvesAllReferences()throws Exception {
        assertDoesNotThrow(()->validate(example()));
    }
    @Test void supportsExplicitFactsAndGuardsAlongsideCascadingInputs()throws Exception {
        validate(example().replace("</fac:hold>","<fac:condition factRef=\"simulationDevicesObserved\"/><fac:condition guardRef=\"triggerAllowed\"/></fac:hold>"));
    }
    @Test void rejectsMisspelledOrMisplacedSafetyDeclarations()throws Exception {
        String xml=example();
        for(String invalid:List.of(
            xml.replace("requirementRef=","requirement="),xml.replace("requirementRef=\"incident-light\"","requirementRef=\"missing\""),
            xml.replace("collectionVar=\"multipassTargets\" />\n        </fac:hold>","collectionVar=\"\" />\n        </fac:hold>"),
            xml.replace("<fac:hold errorRef=\"alignmentHoldLost\">","<fac:hold errorRef=\"missing\">"),
            xml.replace("<fac:input ","<fac:unknown "),xml.replace("</fac:hold>","<fac:condition factRef=\"missing\"/></fac:hold>"),
            xml.replace("</fac:hold>","<fac:condition factRef=\"simulationDevicesObserved\" guardRef=\"triggerAllowed\"/></fac:hold>"),
            xml.replace("</fac:hold>","<fac:condition/></fac:hold>"),
            xml.replace("cancelActivity=\"true\"","cancelActivity=\"false\""),
            xml.replace("<bpmn:errorEventDefinition errorRef=\"alignmentHoldLost\" />","<bpmn:errorEventDefinition />"),
            xml.replace("<bpmn:subProcess id=\"alignment\"","<bpmn:subProcess triggeredByEvent=\"true\" id=\"alignment\""),
            xml.replace("<bpmn:endEvent id=\"alignmentEnd\" />","<bpmn:endEvent id=\"alignmentEnd\"><bpmn:terminateEventDefinition/></bpmn:endEvent>")))
            assertThrows(Exception.class,()->validate(invalid),invalid);
    }
    @Test void rawXmlRejectsHoldOnTaskAndInputOutsideHold()throws Exception {
        assertThrows(IllegalArgumentException.class,()->FacilityBpmnGrammar.validateXml(example().replace("subProcess","serviceTask")));
        assertThrows(IllegalArgumentException.class,()->FacilityBpmnGrammar.validateXml(example().replace("<fac:hold errorRef=\"alignmentHoldLost\">","").replace("</fac:hold>","")));
    }
    @Test void unsupportedScopeLifecyclesAreRejected()throws Exception {
        String xml=example();
        for(String invalid:List.of(
            xml.replace("id=\"alignment\" name=", "id=\"alignment\" flowable:asyncLeave=\"true\" name="),
            xml.replace("<bpmn:startEvent id=\"alignmentStart\" />", "<bpmn:startEvent id=\"alignmentStart\"/><bpmn:multiInstanceLoopCharacteristics isSequential=\"true\"><bpmn:loopCardinality>2</bpmn:loopCardinality></bpmn:multiInstanceLoopCharacteristics>"),
            xml.replace("<bpmn:endEvent id=\"alignmentEnd\" />", "<bpmn:endEvent id=\"alignmentEnd\"/><bpmn:subProcess id=\"nested\"/>"),
            xml.replace("<bpmn:endEvent id=\"alignmentEnd\" />", "<bpmn:endEvent id=\"alignmentEnd\"/><bpmn:scriptTask id=\"script\" scriptFormat=\"javascript\"/>"),
            xml.replace("<bpmn:startEvent id=\"alignmentStart\" />", "<bpmn:startEvent id=\"alignmentStart\"><bpmn:messageEventDefinition messageRef=\"missing\"/></bpmn:startEvent>")))
            assertThrows(Exception.class,()->validate(invalid));
    }
}
