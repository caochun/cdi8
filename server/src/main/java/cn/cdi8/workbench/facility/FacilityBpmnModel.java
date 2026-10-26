package cn.cdi8.workbench.facility;

import cn.cdi8.workbench.systemdefinition.SystemDefinitionModel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
    private record DeploymentModels(SystemDefinitionModel definition,FacilityStateProjectionModel projection,FacilityComponentModels components) {}
    private static final ThreadLocal<DeploymentModels> DEPLOYMENT=new ThreadLocal<>();

    /** Flowable parses synchronously: candidate references must not mutate the active facility on a failed deployment. */
    public static <T> T deployWithModels(SystemDefinitionModel definition,FacilityStateProjectionModel projection,
                                        FacilityComponentModels components,java.util.function.Supplier<T> deploy) {
        var previous=DEPLOYMENT.get();DEPLOYMENT.set(new DeploymentModels(definition,projection,components));
        try{return deploy.get();}finally{if(previous==null)DEPLOYMENT.remove();else DEPLOYMENT.set(previous);}
    }
    public static void validateDeployment(BpmnModel model,FacilityStateRuntime states) {
        var candidate=DEPLOYMENT.get();
        if(candidate==null)validateProcess(model,states.definition(),states.projection(),states.components());
        else validateProcess(model,candidate.definition,candidate.projection,candidate.components);
    }

    public static void validateProcess(BpmnModel model) {
        try {
            validateProcess(model,SystemDefinitionModel.parse(resource("system-models/gxlf-facility.system-definition.xml")),
                FacilityStateProjectionModel.parse(resource("system-models/gxlf-facility-state-projection.xml")));
        }catch(RuntimeException ex){throw ex;}catch(Exception ex){throw new IllegalArgumentException(ex);}
    }

    public static void validateProcess(BpmnModel model,SystemDefinitionModel definition,FacilityStateProjectionModel projection) {
        validateProcess(model,definition,projection,FacilityComponentModels.bundled(definition));
    }
    public static void validateProcess(BpmnModel model,SystemDefinitionModel definition,FacilityStateProjectionModel projection,FacilityComponentModels components) {
        try {
            for(var process:model.getProcesses())for(var element:all(process)) {
                rejectLegacy(element);
                FacilityBpmnGrammar.validate(element);
                if(hold(element)!=null)validateHold(model,element,definition,projection);
                if(process.isExecutable() && element instanceof CallActivity)
                    throw new IllegalArgumentException("新链路尚未支持跨 callActivity 的发次上下文与失败传播，请先使用同一流程内的任务/子过程："+element.getId());
                if(element instanceof ServiceTask task && ("${componentCommand}".equals(task.getImplementation())
                    || Objects.toString(task.getImplementation(),"").contains("systemStates.")
                    || Objects.toString(task.getImplementation(),"").contains("tango.")))
                    throw new IllegalArgumentException("旧运行入口已移除，请使用 fac:action："+element.getId());
                if(element instanceof ServiceTask task && "${facilityActions}".equals(task.getImplementation())
                    && actions(element).isEmpty() && completion(element)==null)
                    throw new IllegalArgumentException("设施任务必须声明 fac:action 或 fac:completion："+element.getId());
            }
            for(var process:model.getProcesses()){rejectLegacy(process);FacilityBpmnGrammar.validate(process);}
            // Ordinary BPMN models do not depend on the GXLF facility catalog. Keep
            // validation generic until a process actually declares facility extensions.
            boolean usesFacilityExtensions = false;
            for (var process : model.getProcesses()) for (var element : all(process)) {
                if (!extensions(element, "requires", FACILITY_NS).isEmpty()
                        || !extensions(element, "completion", FACILITY_NS).isEmpty()
                        || !extensions(element, "capability", FACILITY_NS).isEmpty()
                        || !actions(element).isEmpty()
                        || !extensions(element, "stage", RUN_NS).isEmpty()
                        || !extensions(element, "records", RUN_NS).isEmpty()) {
                    usesFacilityExtensions = true;
                    break;
                }
            }
            if (!usesFacilityExtensions) return;
            for(var process:model.getProcesses()) for(var element:all(process)) {
                Completion completion=completion(element);
                if(completion!=null) {
                    if(!(element instanceof ServiceTask task))throw new IllegalArgumentException("fac:completion 当前仅支持 serviceTask："+element.getId());
                    if(process.isExecutable() && !"${facilityActions}".equals(task.getImplementation()))
                        throw new IllegalArgumentException("完成条件任务必须使用 delegateExpression=${facilityActions}："+element.getId());
                    if("fact".equals(completion.kind()) ? !projection.hasFact(completion.ref()) : !projection.hasGuard(completion.ref()))
                        throw new IllegalArgumentException("fac:completion 引用了未定义 "+completion.kind()+"："+completion.ref()+" / "+element.getId());
                }
                for(var extension:extensions(element,"requires",FACILITY_NS)) {
                    if(!(element instanceof ServiceTask))throw new IllegalArgumentException("fac:requires 当前仅支持 serviceTask："+element.getId());
                    String guard=attr(extension,"guardRef");
                    if(guard.isBlank()||!projection.hasGuard(guard))throw new IllegalArgumentException("fac:requires 引用了未定义 guard："+guard+" / "+element.getId());
                }
                for(var extension:extensions(element,"capability",FACILITY_NS)) {
                    if(process.isExecutable())throw new IllegalArgumentException("fac:capability 仅为设计标注，不能用于可执行流程："+element.getId());
                    String owner=attr(extension,"ownerRef"),capability=attr(extension,"capabilityRef");
                    if(!definition.hasCapability(owner,capability))throw new IllegalArgumentException("fac:capability 引用了未定义能力："+owner+"."+capability);
                }
                for(var extension:actions(element)) {
                    if(!(element instanceof ServiceTask task))throw new IllegalArgumentException("fac:action 必须放在 serviceTask："+element.getId());
                    if(process.isExecutable() && !"${facilityActions}".equals(task.getImplementation()))
                        throw new IllegalArgumentException("设施动作任务必须使用 delegateExpression=${facilityActions}，记录表达式请放在独立任务："+element.getId());
                    String component=attr(extension,"typeRef"), capability=attr(extension,"capabilityRef"), collection=attr(extension,"collectionVar");
                    if(component.isBlank()||capability.isBlank()||collection.isBlank())
                        throw new IllegalArgumentException("fac:action 必须声明 typeRef、capabilityRef、collectionVar："+element.getId());
                    if(!definition.hasCapability(component,capability))
                        throw new IllegalArgumentException("fac:action 引用了未定义能力："+component+"."+capability+" / "+element.getId());
                    if(definition.actionRef(component,capability).isBlank())
                        throw new IllegalArgumentException("fac:action 能力尚未绑定 SCXML 动作："+component+"."+capability+" / "+element.getId());
                    if(process.isExecutable() && attr(extension,"expectedStateRef").isBlank())
                        throw new IllegalArgumentException("设施动作必须声明 expectedStateRef："+element.getId());
                    if(!attr(extension,"expectedStateRef").isBlank())components.validateStates(component,attr(extension,"expectedStateRef"),"","fac:action "+element.getId());
                }
                for(var extension:extensions(element,"stage",RUN_NS)) {
                    if(!(element instanceof ServiceTask))throw new IllegalArgumentException("run:stage 当前仅支持 serviceTask："+element.getId());
                    String stage=attr(extension,"value");if(!STAGES.contains(stage))throw new IllegalArgumentException("run:stage 未知阶段："+stage+" / "+element.getId());
                }
                for(var extension:extensions(element,"records",RUN_NS)) {
                    if(process.isExecutable())throw new IllegalArgumentException("run:records 尚为设计标注；可执行流程自动保存设备结果，其他证据须显式调用 shotRuns.record："+element.getId());
                    if(attr(extension,"kind").isBlank())throw new IllegalArgumentException("run:records 缺少 kind："+element.getId());
                    if(!attr(extension,"factRef").isBlank()&&!projection.hasFact(attr(extension,"factRef")))
                        throw new IllegalArgumentException("run:records 引用了未定义事实："+attr(extension,"factRef"));
                }
            }
        } catch (RuntimeException ex) { throw ex; }
        catch (Exception ex) { throw new IllegalArgumentException("Flowable 设施扩展校验失败："+ex.getMessage(),ex); }
    }

    /** A task may wait for one fact or one composed guard; both use three-valued evaluation. */
    public record Completion(String kind,String ref,Duration timeout) {}

    public static Completion completion(BaseElement element) {
        var declarations=extensions(element,"completion",FACILITY_NS);
        if(declarations.isEmpty())return null;
        if(declarations.size()!=1)throw new IllegalArgumentException("每个任务只能声明一个 fac:completion，请使用 guard 组合多个事实："+element.getId());
        var declaration=declarations.get(0);
        for(String attribute:declaration.getAttributes().keySet())
            if(!Set.of("factRef","guardRef","timeout").contains(attribute))
                throw new IllegalArgumentException("fac:completion 不支持属性："+attribute);
        if(!declaration.getChildElements().isEmpty())throw new IllegalArgumentException("fac:completion 不支持子元素");
        String fact=attr(declaration,"factRef"),guard=attr(declaration,"guardRef"),timeout=attr(declaration,"timeout");
        if(fact.isBlank()==guard.isBlank())throw new IllegalArgumentException("fac:completion 必须且只能引用一个 fact 或 guard："+element.getId());
        try {
            Duration duration=Duration.parse(timeout);
            if(duration.toMillis()<=0)throw new IllegalArgumentException();
            Math.addExact(System.currentTimeMillis(),duration.toMillis());
            return new Completion(fact.isBlank()?"guard":"fact",fact.isBlank()?guard:fact,duration);
        }catch(RuntimeException ex){throw new IllegalArgumentException("fac:completion timeout 必须是正的 ISO-8601 时长（至少 1 毫秒）："+element.getId());}
    }

    private static void rejectLegacy(BaseElement element) {
        for(var values:element.getExtensionElements().values())for(var extension:values)
            if(Set.of("https://cdi8.cn/system-state/v1","https://cdi8.cn/control/action-contract/v1").contains(extension.getNamespace()))
                throw new IllegalArgumentException("旧系统状态/动作契约扩展已移除："+element.getId());
        if(element instanceof ServiceTask task && task.getFieldExtensions().stream().anyMatch(field->"componentCommand".equals(field.getFieldName())))
            throw new IllegalArgumentException("旧组件命令标记已移除："+element.getId());
        if(element instanceof ReceiveTask && element.getAttributes().values().stream().flatMap(Collection::stream)
            .anyMatch(attribute->"https://cdi8.cn/workbench".equals(attribute.getNamespace())))
            throw new IllegalArgumentException("旧条件等待扩展已移除："+element.getId());
    }

    public static List<ExtensionElement> actions(BaseElement element) {
        return extensions(element,"actions",FACILITY_NS).stream()
            .flatMap(container->container.getChildElements().getOrDefault("action",List.of()).stream())
            .filter(action->FACILITY_NS.equals(action.getNamespace())).toList();
    }

    public record HeldInput(String type,String capability,String requirement,String collection) {}
    public record HeldCondition(String kind,String ref) {}
    public record Hold(String errorRef,List<HeldInput> inputs,List<HeldCondition> conditions) {}

    public static Hold hold(BaseElement element) {
        var values=extensions(element,"hold",FACILITY_NS);
        if(values.isEmpty())return null;
        var ext=values.get(0);var inputs=new ArrayList<HeldInput>();var conditions=new ArrayList<HeldCondition>();
        for(var child:ext.getChildElements().values().stream().flatMap(Collection::stream).toList()) {
            if("input".equals(child.getName()))inputs.add(new HeldInput(attr(child,"typeRef"),attr(child,"capabilityRef"),attr(child,"requirementRef"),attr(child,"collectionVar")));
            else {
                String fact=attr(child,"factRef"),guard=attr(child,"guardRef");
                if(fact.isBlank()==guard.isBlank())throw new IllegalArgumentException("fac:condition 必须且只能引用一个 factRef 或 guardRef");
                conditions.add(new HeldCondition(fact.isBlank()?"guard":"fact",fact.isBlank()?guard:fact));
            }
        }
        return new Hold(attr(ext,"errorRef"),List.copyOf(inputs),List.copyOf(conditions));
    }

    private static void validateHold(BpmnModel model,FlowElement element,SystemDefinitionModel definition,FacilityStateProjectionModel projection) {
        var hold=hold(element);
        if(!(element instanceof SubProcess sub)||sub instanceof EventSubProcess||sub instanceof Transaction
            || sub.getLoopCharacteristics()!=null||sub.isForCompensation()||sub.isAsynchronous()||sub.isAsynchronousLeave())
            throw new IllegalArgumentException("fac:hold 只支持普通内嵌子流程："+element.getId());
        for(var parent=sub.getSubProcess();parent!=null;parent=parent.getSubProcess())
            if(hold(parent)!=null)throw new IllegalArgumentException("暂不支持嵌套 fac:hold，请把保持范围展开为明确的阶段："+sub.getId());
        if(!model.getErrors().containsKey(hold.errorRef())||model.getErrors().get(hold.errorRef()).isBlank())
            throw new IllegalArgumentException("fac:hold errorRef 必须引用带 errorCode 的 BPMN error："+hold.errorRef());
        String code=model.getErrors().get(hold.errorRef());
        var boundaries=model.getProcesses().stream().flatMap(p->all(p).stream()).filter(e->e instanceof BoundaryEvent)
            .map(e->(BoundaryEvent)e).filter(e->sub.getId().equals(e.getAttachedToRefId())).toList();
        // Flowable's error-event behavior is always interrupting, although its converter stores cancelActivity=false.
        if(boundaries.size()!=1 || boundaries.get(0).getOutgoingFlows().isEmpty()
            || boundaries.get(0).getEventDefinitions().size()!=1 || !(boundaries.get(0).getEventDefinitions().get(0) instanceof ErrorEventDefinition error)
            || !code.equals(model.getErrors().getOrDefault(error.getErrorCode(),error.getErrorCode())))
            throw new IllegalArgumentException("fac:hold 必须有唯一匹配 errorRef 的中断型边界错误事件及异常路径："+sub.getId());
        Set<HeldInput> uniqueInputs=new HashSet<>();Set<HeldCondition> uniqueConditions=new HashSet<>();
        for(var input:hold.inputs()) {
            if(!uniqueInputs.add(input))throw new IllegalArgumentException("重复保持输入："+input);
            if(definition.dependencies().inputs(input.type(),input.capability()).stream().noneMatch(i->i.id().equals(input.requirement())))
                throw new IllegalArgumentException("fac:input 未定义能力输入依赖："+input.type()+"/"+input.capability()+"/"+input.requirement());
        }
        for(var condition:hold.conditions()) {
            if(!uniqueConditions.add(condition))throw new IllegalArgumentException("重复保持条件："+condition.ref());
            if("fact".equals(condition.kind())?!projection.hasFact(condition.ref()):!projection.hasGuard(condition.ref()))
                throw new IllegalArgumentException("fac:condition 未定义："+condition.ref());
        }
        // Restrict unsupported escape paths rather than silently losing monitoring coverage.
        for(var child:all(sub)) {
            if(child instanceof SubProcess || child instanceof ScriptTask || child instanceof SendTask || child instanceof ManualTask
                || child instanceof BoundaryEvent || child instanceof CallActivity
                || (child instanceof Activity a && (a.getLoopCharacteristics()!=null||a.isAsynchronous()||a.isAsynchronousLeave()))
                || (child instanceof EndEvent end && !end.getEventDefinitions().isEmpty())
                || (child instanceof FlowNode node && !(child instanceof EndEvent) && node.getOutgoingFlows().isEmpty()))
                throw new IllegalArgumentException("保持子流程包含暂不支持的活动/隐式出口："+child.getId());
        }
        if(sub.getFlowElements().stream().noneMatch(e->e instanceof EndEvent))throw new IllegalArgumentException("保持子流程必须有显式结束事件："+sub.getId());
        var starts=sub.getFlowElements().stream().filter(e->e instanceof StartEvent).map(e->(StartEvent)e).toList();
        if(starts.size()!=1||!starts.get(0).getEventDefinitions().isEmpty())throw new IllegalArgumentException("保持子流程必须有唯一普通开始事件："+sub.getId());
    }

    private static List<FlowElement> all(FlowElementsContainer container){var result=new ArrayList<FlowElement>();for(var element:container.getFlowElements()){result.add(element);if(element instanceof SubProcess sub)result.addAll(all(sub));}return result;}
    private static List<ExtensionElement> extensions(BaseElement element,String name,String namespace){return element.getExtensionElements().getOrDefault(name,List.of()).stream().filter(item->namespace.equals(item.getNamespace())).toList();}
    private static String attr(ExtensionElement element,String name){var values=element.getAttributes().get(name);return values==null||values.isEmpty()?"":values.get(0).getValue();}
    private static String resource(String path)throws Exception{var resource=new ClassPathResource(path);try(var input=resource.getInputStream()){return new String(input.readAllBytes(),StandardCharsets.UTF_8);}}
}
