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
import cn.cdi8.workbench.systemdefinition.SystemDefinitionModel;
import cn.cdi8.workbench.facility.FacilityBindingModel;
import cn.cdi8.workbench.facility.FacilityStateProjectionModel;
import cn.cdi8.workbench.facility.FacilityBpmnModel;
import cn.cdi8.workbench.facility.FacilityComponentModels;

@Service
public class ModelValidation {
    private final ObjectMapper json;
    private final TangoGateway tango;
    private final cn.cdi8.workbench.facility.FacilityStateRuntime states;
    private final ModelStore store;
    @org.springframework.beans.factory.annotation.Autowired
    public ModelValidation(ObjectMapper json,TangoGateway tango,cn.cdi8.workbench.facility.FacilityStateRuntime states,ModelStore store){this.json=json;this.tango=tango;this.states=states;this.store=store;}
    public ModelValidation(ObjectMapper json,TangoGateway tango,cn.cdi8.workbench.facility.FacilityStateRuntime states){this(json,tango,states,null);}
    public String publishedXml(String id) {
        var revisions=store.get(id).path("revisions");
        if(revisions.isEmpty())throw new IllegalArgumentException("引用模型尚未发布："+id);
        return revisions.get(revisions.size()-1).path("xml").asText();
    }
    public FacilityComponentModels components(SystemDefinitionModel definition) {
        return store==null?FacilityComponentModels.bundled(definition):FacilityComponentModels.resolve(definition,href->{
            String id=FacilityComponentModels.modelId(href);
            if(!"scxml".equals(store.get(id).path("kind").asText()))throw new IllegalArgumentException("stateModelHref 必须引用 SCXML："+href);
            return publishedXml(id);
        });
    }
    private SystemDefinitionModel publishedDefinition(){return store==null?states.definition():SystemDefinitionModel.parse(publishedXml("gxlf-facility"));}
    private FacilityStateProjectionModel publishedProjection(){return store==null?states.projection():FacilityStateProjectionModel.parse(publishedXml("gxlf-facility-state-projection"));}
    public static BpmnModel parse(String xml) throws Exception {
        cn.cdi8.workbench.facility.FacilityBpmnGrammar.validateXml(xml);
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
                switch (rootName(xml)) {
                    case "system-definition" -> {
                        var model=SystemDefinitionModel.parse(xml);
                        components(model);
                        value.put("systemDefinition",model.id());
                        value.put("componentTypes",model.componentTypeCount());
                        value.put("instanceSets",model.instanceSetCount());
                        value.put("structuralConstraints",model.structuralConstraintCount());
                        value.put("connections",model.connectionCount());
                        value.put("openIssues",model.openIssueCount());
                    }
                    case "facility-binding" -> {
                        var model=FacilityBindingModel.parse(xml,publishedDefinition());
                        value.put("facilityBinding",model.id());
                        value.put("instanceBindings",model.instanceBindingCount());
                        value.put("beamlineBindings",model.beamlineBindingCount());
                        value.put("externalBindings",model.externalBindingCount());
                    }
                    case "facility-state-projection" -> {
                        var model=FacilityStateProjectionModel.parse(xml);
                        var definition=publishedDefinition();model.validateComponents(definition,components(definition));
                        var binding=store==null?states.binding():FacilityBindingModel.parse(publishedXml("gxlf-facility-binding"),definition);
                        if(!binding.id().equals(model.bindingRef()))throw new IllegalArgumentException("设施投影 bindingRef 不一致");
                        value.put("facilityStateProjection",model.id());
                        value.put("observations",model.observationCount());
                        value.put("facts",model.factCount());
                        value.put("guards",model.guardCount());
                    }
                    default -> throw new IllegalArgumentException("不支持的系统模型根元素："+rootName(xml));
                }
                value.put("validationScope","referenced-models");
                value.put("valid",true);return value;
            }
            return validateBpmn(xml,publishedDefinition(),publishedProjection(),components(publishedDefinition()));
        } catch(Exception ex) {
            diagnostic(diagnostics,ex);
        }
        value.put("valid",diagnostics.isEmpty());return value;
    }
    public JsonNode validateBpmn(String xml,SystemDefinitionModel definition,FacilityStateProjectionModel projection,FacilityComponentModels components) {
        ObjectNode value=json.createObjectNode();ArrayNode diagnostics=value.putArray("diagnostics");
        try {
            var model=parse(xml);
            projection.validateComponents(definition,components);
            FacilityBpmnModel.validateProcess(model,definition,projection,components);
            if(model.getProcesses().isEmpty())throw new IllegalArgumentException("BPMN 缺少 process");
            for(var error:new ProcessValidatorFactory().createDefaultProcessValidator().validate(model)) {
                if(error.isWarning())continue;
                diagnostics.addObject().put("severity","error").put("message",error.getDefaultDescription()).put("elementId",error.getActivityId()==null?"":error.getActivityId());
            }
            value.put("processKey",model.getMainProcess().getId());
        } catch(Exception ex) {
            diagnostic(diagnostics,ex);
        }
        value.put("valid",diagnostics.isEmpty());return value;
    }
    private static void diagnostic(ArrayNode diagnostics,Exception ex) {
            Throwable cause=ex;while(cause.getCause()!=null)cause=cause.getCause();
            var item=diagnostics.addObject().put("severity","error").put("message",cause.getMessage()==null?cause.toString():cause.getMessage());
            if(cause instanceof org.xml.sax.SAXParseException error)
                item.put("line",error.getLineNumber()).put("column",error.getColumnNumber());
            if(cause instanceof XMLStreamException error && error.getLocation()!=null)
                item.put("line",error.getLocation().getLineNumber()).put("column",error.getLocation().getColumnNumber());
    }
    private static String rootName(String xml) throws Exception {
        var factory=javax.xml.parsers.DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        return factory.newDocumentBuilder().parse(new org.xml.sax.InputSource(new StringReader(xml))).getDocumentElement().getLocalName();
    }
}
