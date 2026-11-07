package cn.cdi8.simulator.environment;

import cn.cdi8.simulator.runtime.ComponentModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Model validation and capability descriptions used by the simulator CLI and device configuration. */
public final class ComponentModelSupport {
    private ComponentModelSupport() { }

    public static JsonNode validate(String xml) {
        ObjectNode result=ComponentModel.JSON.createObjectNode();
        ArrayNode diagnostics=result.putArray("diagnostics");
        try {
            ComponentModel model=new ComponentModel(xml);
            result.put("valid",true).set("capabilities",capabilities(model));
        } catch(Exception ex) {
            Throwable cause=ex;
            while(cause.getCause()!=null)cause=cause.getCause();
            result.put("valid",false);
            diagnostics.addObject().put("severity","error").put("message",cause.getMessage()==null?cause.toString():cause.getMessage());
        }
        return result;
    }

    static ObjectNode capabilities(ComponentModel model) {
        ObjectNode result=ComponentModel.JSON.createObjectNode();
        ObjectNode actions=result.putObject("actions");
        model.metadata.path("actions").fields().forEachRemaining(entry->{
            ObjectNode action=actions.putObject(entry.getKey());
            action.put("label",entry.getValue().path("label").asText(entry.getKey()));
            action.put("request_event",model.requestEvent(entry.getKey()));
        });
        ObjectNode confirmations=result.putObject("confirmations");
        model.metadata.path("confirmations").fields().forEachRemaining(entry->{
            ObjectNode confirmation=confirmations.putObject(entry.getKey());
            confirmation.put("label",entry.getValue().path("label").asText(entry.getKey()));
            confirmation.set("source_outer",entry.getValue().path("source_outer"));
            confirmation.set("observation",entry.getValue().path("observation"));
        });
        return result;
    }
}
