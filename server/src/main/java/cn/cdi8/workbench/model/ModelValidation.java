package cn.cdi8.workbench.model;

import cn.cdi8.workbench.integration.tango.TangoGateway;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.StringReader;
import javax.xml.stream.*;
import org.flowable.bpmn.converter.BpmnXMLConverter;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.validation.ProcessValidatorFactory;
import org.springframework.stereotype.Service;

@Service
public class ModelValidation {
    private final ObjectMapper json;
    private final TangoGateway tango;
    public ModelValidation(ObjectMapper json,TangoGateway tango){this.json=json;this.tango=tango;}
    public static BpmnModel parse(String xml) throws Exception {
        var factory=XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD,false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities",false);
        if(xml==null || xml.length()>1_000_000 || xml.toUpperCase().contains("<!DOCTYPE") || xml.toUpperCase().contains("<!ENTITY"))throw new IllegalArgumentException("XML 为空、过大或包含 DTD/实体");
        var reader=factory.createXMLStreamReader(new StringReader(xml));
        try{return new BpmnXMLConverter().convertToBpmnModel(reader);}finally{reader.close();}
    }
    public JsonNode validate(String kind,String xml) {
        if("scxml".equals(kind))return tango.request("POST","/lab/validate",java.util.Map.of("xml",xml));
        ObjectNode value=json.createObjectNode();ArrayNode diagnostics=value.putArray("diagnostics");
        try {
            if("system".equals(kind)) {
                var model=cn.cdi8.workbench.systemstate.SystemStateModel.parse(xml);
                value.put("systemModel",model.id());value.put("valid",true);return value;
            }
            var model=parse(xml);
            cn.cdi8.workbench.systemstate.SystemStateModel.validateProcess(model);
            for(var process:model.getProcesses()) for(var element:allElements(process))
                cn.cdi8.workbench.run.ActionContractModel.validateElement(element);
            if(model.getProcesses().isEmpty())throw new IllegalArgumentException("BPMN 缺少 process");
            for(var error:new ProcessValidatorFactory().createDefaultProcessValidator().validate(model)) {
                if(error.isWarning())continue;
                diagnostics.addObject().put("severity","error").put("message",error.getDefaultDescription()).put("elementId",error.getActivityId()==null?"":error.getActivityId());
            }
            value.put("processKey",model.getMainProcess().getId());
        } catch(Exception ex) {
            Throwable cause=ex;while(cause.getCause()!=null)cause=cause.getCause();
            var item=diagnostics.addObject().put("severity","error").put("message",cause.getMessage()==null?cause.toString():cause.getMessage());
            if(cause instanceof XMLStreamException error && error.getLocation()!=null)
                item.put("line",error.getLocation().getLineNumber()).put("column",error.getLocation().getColumnNumber());
        }
        value.put("valid",diagnostics.isEmpty());return value;
    }
    private java.util.List<org.flowable.bpmn.model.FlowElement> allElements(org.flowable.bpmn.model.FlowElementsContainer container) {
        var result=new java.util.ArrayList<org.flowable.bpmn.model.FlowElement>();
        for(var element:container.getFlowElements()) { result.add(element);if(element instanceof org.flowable.bpmn.model.SubProcess sub)result.addAll(allElements(sub)); }
        return result;
    }
}
