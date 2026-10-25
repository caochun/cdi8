package cn.cdi8.workbench.facility;

import cn.cdi8.workbench.systemdefinition.SystemDefinitionModel;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.flowable.bpmn.model.*;
import org.springframework.core.io.ClassPathResource;

/** Validates the facility/run extensions used by the new BPMN design. */
public final class FacilityBpmnModel {
    public static final String FACILITY_NS="https://cdi8.cn/facility/v1";
    public static final String RUN_NS="https://cdi8.cn/shot-run/v1";
    private static final Set<String> STAGES=Set.of("created","resources_reserved","initializing","initialized",
        "parameters_receiving","parameters_received","preparing","prepared","charging","triggering",
        "emission_confirmed","postprocessing","completed","failed","cancelled");
    private FacilityBpmnModel() {}

    public static void validateProcess(BpmnModel model) {
        try {
            var definition=SystemDefinitionModel.parse(resource("system-models/gxlf-facility.system-definition.xml"));
            var projection=FacilityStateProjectionModel.parse(resource("system-models/gxlf-facility-state-projection.xml"));
            for(var process:model.getProcesses()) for(var element:all(process)) {
                for(var extension:extensions(element,"requires",FACILITY_NS)) {
                    String guard=attr(extension,"guard");
                    if(guard.isBlank()||!projection.hasGuard(guard))throw new IllegalArgumentException("fac:requires 引用了未定义 guard："+guard+" / "+element.getId());
                }
                for(var extension:extensions(element,"capability",FACILITY_NS)) {
                    String ref=attr(extension,"ref");int dot=ref.indexOf('.');
                    if(dot<1||dot==ref.length()-1||!definition.hasCapability(ref.substring(0,dot),ref.substring(dot+1)))
                        throw new IllegalArgumentException("fac:capability 引用了未定义能力："+ref+" / "+element.getId());
                }
                for(var extension:extensions(element,"action",FACILITY_NS)) {
                    String component=attr(extension,"component"), capability=attr(extension,"capability"), collection=attr(extension,"collection");
                    if(component.isBlank()||capability.isBlank()||collection.isBlank())
                        throw new IllegalArgumentException("fac:action 必须声明 component、capability、collection："+element.getId());
                    if(!definition.hasCapability(component,capability))
                        throw new IllegalArgumentException("fac:action 引用了未定义能力："+component+"."+capability+" / "+element.getId());
                    if(definition.actionRef(component,capability).isBlank())
                        throw new IllegalArgumentException("fac:action 能力尚未绑定 SCXML 动作："+component+"."+capability+" / "+element.getId());
                }
                for(var extension:extensions(element,"stage",RUN_NS)) {
                    String stage=attr(extension,"value");if(!STAGES.contains(stage))throw new IllegalArgumentException("run:stage 未知阶段："+stage+" / "+element.getId());
                }
                for(var extension:extensions(element,"records",RUN_NS)) {
                    if(attr(extension,"kind").isBlank())throw new IllegalArgumentException("run:records 缺少 kind："+element.getId());
                }
            }
        } catch (RuntimeException ex) { throw ex; }
        catch (Exception ex) { throw new IllegalArgumentException("Flowable 设施扩展校验失败："+ex.getMessage(),ex); }
    }

    private static List<FlowElement> all(FlowElementsContainer container){var result=new ArrayList<FlowElement>();for(var element:container.getFlowElements()){result.add(element);if(element instanceof SubProcess sub)result.addAll(all(sub));}return result;}
    private static List<ExtensionElement> extensions(BaseElement element,String name,String namespace){return element.getExtensionElements().getOrDefault(name,List.of()).stream().filter(item->namespace.equals(item.getNamespace())).toList();}
    private static String attr(ExtensionElement element,String name){var values=element.getAttributes().get(name);return values==null||values.isEmpty()?"":values.get(0).getValue();}
    private static String resource(String path)throws Exception{var resource=new ClassPathResource(path);try(var input=resource.getInputStream()){return new String(input.readAllBytes(),StandardCharsets.UTF_8);}}
}
