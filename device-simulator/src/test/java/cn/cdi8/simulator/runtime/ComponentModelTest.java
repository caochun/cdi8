package cn.cdi8.simulator.runtime;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ComponentModelTest {
    private String resource(String name)throws Exception {
        try(var input=getClass().getResourceAsStream(name)){assertNotNull(input,name);return new String(input.readAllBytes(),StandardCharsets.UTF_8);}
    }
    private String changed(String xml,Consumer<ObjectNode> change)throws Exception {
        int start=xml.indexOf("<![CDATA[")+9,end=xml.indexOf("]]>",start);
        ObjectNode metadata=(ObjectNode)ComponentModel.JSON.readTree(xml.substring(start,end));change.accept(metadata);
        return xml.substring(0,start)+metadata+"\n"+xml.substring(end);
    }
    @ParameterizedTest @ValueSource(strings={"seed-source","shg-injection","preamp","multipass","pump","measurement",
        "frequency-conversion","target-alignment","vacuum","diagnostics","synchronization","switch-driver","cooling","control-environment"})
    void allExamplesUseSameValidatorAndRealEngine(String name)throws Exception {
        assertDoesNotThrow(()->new ComponentModel(resource("/models/"+name+"-state-machine.scxml")));
    }
    @ParameterizedTest @ValueSource(strings={"unknownRoot","unknownAction","unknownFeedbackState","unknownFeedbackEvent","numericFeedback",
        "unknownRequest","fractionalVersion","unknownLabelState","unknownConfiguredState","oldNotes","duplicateJson","duplicateMetadata","nestedMetadata"})
    void rejectsMalformedAdapterContract(String failure)throws Exception {
        String xml=resource("/component.scxml");
        String bad=changed(xml,m->{
            var action=(ObjectNode)m.path("actions").path("activate");
            switch(failure) {
                case "unknownRoot" -> m.put("actionz",true);
                case "unknownAction" -> action.put("maintian","execution");
                case "unknownFeedbackState" -> ((ObjectNode)action.path("success_events")).put("missing","activate.succeeded");
                case "unknownFeedbackEvent" -> ((ObjectNode)action.path("success_events")).put("idle","missing.event");
                case "numericFeedback" -> ((ObjectNode)action.path("failure_events")).put("idle",123);
                case "unknownRequest" -> action.put("request_event","missing.request");
                case "fractionalVersion" -> m.put("model_version",1.5);
                case "unknownLabelState" -> ((ObjectNode)m.path("inner_labels")).put("missing","不存在");
                case "unknownConfiguredState" -> m.putArray("configuration_valid_states").add("missing");
                case "oldNotes" -> m.putArray("modeling_notes").add("须置于 annotations");
            }
        });
        if(failure.equals("duplicateJson"))bad=bad.replace("\"model_version\":1","\"model_version\":1,\"model_version\":2");
        if(failure.equals("duplicateMetadata"))bad=bad.replace("</datamodel>","<data id='componentMetadata'>{}</data></datamodel>");
        if(failure.equals("nestedMetadata"))bad=bad.replace("<datamodel>","<state id='metadataOwner'><datamodel>").replace("</datamodel>","</datamodel></state>");
        assertNotEquals(xml,bad);final String invalid=bad;
        assertThrows(IllegalArgumentException.class,()->new ComponentModel(invalid),failure);
    }
    @ParameterizedTest @ValueSource(strings={"field","outer","leaf","event","faultCode"})
    void rejectsBrokenConfirmationReferences(String failure)throws Exception {
        String xml=changed(resource("/models/seed-source-state-machine.scxml"),m->{
            var c=(ObjectNode)m.path("confirmations").path("fault_confirmed");
            switch(failure) {
                case "field" -> c.put("ignoreFailure",true);
                case "outer" -> c.putArray("source_outer").add("missing");
                case "leaf" -> c.putArray("source_states").add("missing");
                case "event" -> c.put("machine_event","missing.event");
                case "faultCode" -> ((ObjectNode)m.path("fault_codes")).put("missing_confirmation","fault");
            }
        });
        assertThrows(IllegalArgumentException.class,()->new ComponentModel(xml));
    }
    @Test void supportsAncestorWildcardTransitionsAndNullFeedback()throws Exception {
        String xml=changed(resource("/component.scxml"),m->((ObjectNode)m.path("actions").path("activate").path("failure_events")).putNull("idle"));
        xml=xml.replace("<transition event=\"activate.request\" />","")
            .replace("<state id=\"operating\" initial=\"idle\">","<state id=\"operating\" initial=\"idle\"><transition event=\"activate.*\" />");
        new ComponentModel(xml);
    }
    @Test void requestMayChangeStateBeforeFeedback()throws Exception {
        String xml=resource("/models/seed-source-state-machine.scxml");
        var model=new ComponentModel(xml);
        var engine=new cn.cdi8.simulator.engine.ScxmlEngine(xml);
        engine.restore("collectionOk");
        engine.dispatch(model.requestEvent("laser_parameter_collect"));
        assertEquals("stableEmission",engine.leaf());
        engine.dispatch("collection.completed");
        assertEquals("collectionOk",engine.leaf());
    }
}
