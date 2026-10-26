package cn.cdi8.workbench.facility;

import cn.cdi8.workbench.systemdefinition.SystemDefinitionModel;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Grammar rejection and cross-model invariants must fail before any device dispatch. */
class FacilityXmlModelTest {
    static String xml(String suffix)throws Exception{return Files.readString(Path.of("examples/gxlf/system/gxlf-facility"+suffix+".xml"));}
    static String definition()throws Exception{return xml(".system-definition");}
    static String binding()throws Exception{return xml("-binding");}
    static String projection()throws Exception{return xml("-state-projection");}
    static void rejectedDefinition(String before,String from,String to) {
        String changed=before.replace(from,to);assertNotEquals(before,changed,"测试必须实际改变模型");
        assertThrows(IllegalArgumentException.class,()->SystemDefinitionModel.parse(changed));
    }
    @Test void rejectsLegacyDirectPortsAndDuplicateSections()throws Exception {
        String xml=definition();
        rejectedDefinition(xml,"<ports>","");
        rejectedDefinition(xml,"</signals>","</signals><signals><signal id=\"other\" label=\"other\" interfaceRef=\"optical-link\" /></signals>");
        rejectedDefinition(xml,"stateModelHref=","stateModelRef=");
    }
    @Test void rejectsUnknownExecutableElementsAttributesAndNamespaces()throws Exception {
        String xml=definition();
        rejectedDefinition(xml,"maintain=\"execution\"","maintain=\"execution\" ignored=\"true\"");
        rejectedDefinition(xml,"<requirements>","<requirements><unknownRule />");
        rejectedDefinition(xml,"<requirements>","<requirements xmlns=\"https://example.invalid/foreign\">");
        rejectedDefinition(xml,"<annotations>","<safetyRules /><annotations>");
    }
    @Test void rejectsDuplicateScopedIdsAndInvalidDirection()throws Exception {
        String xml=definition();
        rejectedDefinition(xml,"<port id=\"optical-in\" direction=\"in\" interfaceRef=\"optical-link\" />",
            "<port id=\"optical-in\" direction=\"in\" interfaceRef=\"optical-link\" /><port id=\"optical-in\" direction=\"in\" interfaceRef=\"optical-link\" />");
        rejectedDefinition(xml,"direction=\"inout\"","direction=\"bidirectional\"");
    }
    @Test void validatesEveryExternalAndGroupPort()throws Exception {
        String xml=definition();
        rejectedDefinition(xml,"externalEndpointRef=\"data-service\" portRef=\"ingest\"","externalEndpointRef=\"data-service\" portRef=\"missing\"");
        rejectedDefinition(xml,"instanceGroupRef=\"data-producers\" portRef=\"data-out\"","instanceGroupRef=\"all-components\" portRef=\"data-out\"");
        rejectedDefinition(xml,"instanceSetRef=\"seed\" portRef=\"optical-out\"","instanceSetRef=\"seed\" portRef=\"command-in\"");
        rejectedDefinition(xml,"instanceSetRef=\"seed\" portRef=\"optical-out\"","instanceSetRef=\"seed\" externalEndpointRef=\"data-service\" portRef=\"optical-out\"");
    }
    @Test void rejectsInvalidSignalMappingRangeAndStructuralCounts()throws Exception {
        String xml=definition();
        rejectedDefinition(xml,"signalRef=\"alignment-light\"","signalRef=\"unlisted-light\"");
        rejectedDefinition(xml,"groupSize=\"6\"","groupSize=\"7\"");
        rejectedDefinition(xml,"last=\"8\"","last=\"61\"");
        rejectedDefinition(xml,"instanceSetRef=\"pump\" quantity=\"1\"","instanceSetRef=\"pump\" quantity=\"2\"");
        rejectedDefinition(xml,"<componentType id=\"seed\"","<componentType quantity=\"1\" id=\"seed\"");
    }
    @Test void groupSizeAndInstanceCountsComeOnlyFromDefinition()throws Exception {
        String changed=definition().replace("groupSize=\"6\"","groupSize=\"3\"")
            .replace("id=\"preamp\" pattern=\"preamp-{index}\" quantity=\"10\"","id=\"preamp\" pattern=\"preamp-{index}\" quantity=\"20\"");
        var definition=SystemDefinitionModel.parse(changed);
        var topology=FacilityBindingModel.parse(binding(),definition).topology();
        assertEquals("preamp-03",topology.logical("preamp",7,"seed"));
        assertEquals(60,topology.beamlineCount());
    }
    @Test void bindingResolvesInstanceTypeInsteadOfAssumingItEqualsSetId()throws Exception {
        String definition=definition().replace("<instanceSet id=\"seed\"","<instanceSet id=\"seed-bank\"").replace("instanceSetRef=\"seed\"","instanceSetRef=\"seed-bank\"");
        String binding=binding().replace("instanceSetRef=\"seed\"","instanceSetRef=\"seed-bank\"").replace("runtimeKey=\"seed\"","runtimeKey=\"custom-source\"");
        var parsed=FacilityBindingModel.parse(binding,SystemDefinitionModel.parse(definition));
        assertEquals("seed",parsed.componentForRuntimeKey("custom-source"));
        assertEquals("seed-bank",parsed.instanceSetForRuntimeKey("custom-source"));
        assertEquals("seed-01",parsed.topology().logical("seed",1,"seed"));
        assertEquals("seed_01",parsed.topology().bind(Map.of("seed_01",Map.of("component","custom-source")),Map.of()).get("seed-01"));
    }
    @Test void missingAndForeignMappingBindingsAreRejected()throws Exception {
        var definition=SystemDefinitionModel.parse(definition());
        assertThrows(IllegalArgumentException.class,()->FacilityBindingModel.parse(binding().replace("<mappingBinding mappingRef=\"preamp-to-beamline\" />",""),definition));
        assertThrows(IllegalArgumentException.class,()->FacilityBindingModel.parse(binding().replace("mappingRef=\"preamp-to-beamline\"","mappingRef=\"missing\""),definition));
        assertThrows(IllegalArgumentException.class,()->FacilityBindingModel.parse(binding().replace("instanceSetRef=\"seed\"","instanceSetRef=\"missing\""),definition));
    }
    @Test void oldBindingTemplatesAndDuplicateRuntimeTargetsAreRejected()throws Exception {
        assertThrows(IllegalArgumentException.class,()->FacilityBindingModel.parse(binding().replace("instanceSetRef=\"seed\"","logicalPattern=\"seed-01\" instanceSetRef=\"seed\"")));
        assertThrows(IllegalArgumentException.class,()->FacilityBindingModel.parse(binding().replace("runtimePattern=\"shg_{index}\"","runtimePattern=\"seed_{index}\""),SystemDefinitionModel.parse(definition())));
    }
    @Test void projectionRejectsUninterpretedPoliciesAndEmptyConditions()throws Exception {
        for(String changed:List.of(projection().replace("<condition>","<condition ignored=\"yes\">"),
            projection().replace("<all>","<any /> <all>"),
            projection().replace("<annotations>","<invalidators><invalidate event=\"event\" result=\"valid\" /></invalidators><annotations>"),
            projection().replace("<condition>","<condition><unsupported />")))
            assertThrows(IllegalArgumentException.class,()->FacilityStateProjectionModel.parse(changed));
    }
    @Test void rejectsForwardReferenceCyclesAndUnresolvedFacts()throws Exception {
        String xml=projection();
        assertThrows(IllegalArgumentException.class,()->FacilityStateProjectionModel.parse(xml.replace("factRef=\"shieldDoorLocked\"","factRef=\"interlockEstablished\"")));
        assertThrows(IllegalArgumentException.class,()->FacilityStateProjectionModel.parse(xml.replace("factRef=\"shieldDoorLocked\"","factRef=\"noSuchFact\"")));
        assertThrows(IllegalArgumentException.class,()->FacilityStateProjectionModel.parse(xml.replace("observationRef=\"shieldDoor\"","observationRef=\"noSuchObservation\"")));
    }
    @Test void activationChecksProjectionSourcesAndDeviceSets()throws Exception {
        var definition=SystemDefinitionModel.parse(definition());
        for(String changed:List.of(projection().replace("instanceSetRef=\"seed\"","instanceSetRef=\"noSuchSet\""),
            projection().replace("externalEndpointRef=\"shield-door\"","externalEndpointRef=\"noSuchDoor\""),
            projection().replace("typeRef=\"seed\"","typeRef=\"noSuchType\"")))
            assertThrows(IllegalArgumentException.class,()->FacilityStateProjectionModel.parse(changed).validateDependencies(definition));
    }
    @Test void unsupportedUpstreamEdgesCannotBeSilentlySkipped()throws Exception {
        String xml=definition().replace("<connections>","<connections><connection id=\"unsupported\" relation=\"recipe-control\"><source externalEndpointRef=\"interlock-controller\" portRef=\"event-out\" /><target instanceSetRef=\"preamp\" portRef=\"command-in\" /></connection>");
        assertThrows(IllegalArgumentException.class,()->SystemDefinitionModel.parse(xml));
    }
    @Test void modelInputCannotLoadDtdOrExternalEntities()throws Exception {
        String payload=definition().replace("<?xml version=\"1.0\" encoding=\"UTF-8\"?>","<!DOCTYPE system-definition SYSTEM 'file:///unreadable-test-file'>");
        assertThrows(IllegalArgumentException.class,()->SystemDefinitionModel.parse(payload));
    }
}
