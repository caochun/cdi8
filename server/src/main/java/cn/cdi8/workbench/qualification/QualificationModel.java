package cn.cdi8.workbench.qualification;

import java.util.*;
import org.flowable.bpmn.model.*;

/** The executable contract of the ctl namespace; predicates contain model data only. */
public final class QualificationModel {
    public static final String NS = "https://cdi8.cn/control/qualification/v1";
    private QualificationModel() {}
    public static List<ExtensionElement> elements(BaseElement owner, String name) {
        return owner.getExtensionElements().getOrDefault(name, List.of()).stream()
            .filter(e -> NS.equals(e.getNamespace())).toList();
    }
    public static String attr(ExtensionElement e, String name) {
        String value = e.getAttributeValue(null, name);
        return value == null ? "" : value;
    }
    public static String required(ExtensionElement e, String name) {
        String value = attr(e, name);
        if (value.isBlank()) throw new IllegalArgumentException("ctl:" + e.getName() + " 缺少 " + name);
        return value;
    }
    public static SubProcess scope(FlowElement element) {
        FlowElementsContainer parent = element.getParentContainer();
        while (parent instanceof SubProcess sub) {
            if (!elements(sub, "qualificationPolicy").isEmpty()) return sub;
            parent = sub.getParentContainer();
        }
        return null;
    }
    public static void validate(BpmnModel model) {
        for (var process : model.getProcesses()) validateContainer(process, model);
    }
    private static void validateContainer(FlowElementsContainer container, BpmnModel model) {
        for (FlowElement element : container.getFlowElements()) {
            validateElement(element, model);
            if (element instanceof SubProcess sub) validateContainer(sub, model);
        }
    }
    public static void validateElement(FlowElement element, BpmnModel model) {
        if (element instanceof SubProcess sub && !elements(sub, "qualificationPolicy").isEmpty()) {
            var policies = elements(sub, "qualificationPolicy");
            if (policies.size() != 1) throw new IllegalArgumentException("资格作用域必须只有一个 policy：" + sub.getId());
            var policy = policies.get(0);
            if (!"violation".equals(required(policy,"unknown")) || !"interrupt".equals(required(policy,"onViolation")))
                throw new IllegalArgumentException("当前仅支持 unknown=violation, onViolation=interrupt");
            String message = required(policy,"message");
            long age = Long.parseLong(required(policy,"evidenceMaxAgeMs"));
            if (age < 100 || age > 60000) throw new IllegalArgumentException("evidenceMaxAgeMs 必须在 100–60000 之间");
            if (model != null && sub.getBoundaryEvents().stream().noneMatch(b -> b.isCancelActivity()
                    && b.getEventDefinitions().stream().anyMatch(d -> d instanceof MessageEventDefinition m
                    && model.getMessage(m.getMessageRef()) != null && message.equals(model.getMessage(m.getMessageRef()).getName()))))
                throw new IllegalArgumentException("资格作用域缺少对应的中断消息边界：" + message);
            Set<String> ids = new HashSet<>();
            for (var q : elements(sub,"qualification")) {
                if (!ids.add(required(q,"id"))) throw new IllegalArgumentException("资格 id 重复");
                required(q,"targets");
                var accept = q.getChildElements().getOrDefault("accept",List.of());
                if (accept.isEmpty()) throw new IllegalArgumentException("资格缺少 accept");
                Set<String> components = new HashSet<>();
                for (var a : accept) {
                    if (!NS.equals(a.getNamespace()) || !components.add(required(a,"component")))
                        throw new IllegalArgumentException("accept 组件重复或命名空间错误");
                    if (attr(a,"states").isBlank() && attr(a,"outerStates").isBlank())
                        throw new IllegalArgumentException("accept 缺少 states 或 outerStates");
                }
            }
            if (ids.isEmpty()) throw new IllegalArgumentException("作用域缺少资格定义");
            if (sub.getExecutionListeners().stream().noneMatch(l -> "start".equals(l.getEvent())
                    && "${qualifications.open(execution)}".equals(l.getImplementation()))
                    || sub.getExecutionListeners().stream().noneMatch(l -> "end".equals(l.getEvent())
                    && "${qualifications.close(execution)}".equals(l.getImplementation())))
                throw new IllegalArgumentException("资格作用域必须声明 open/close 执行监听器");
        }
        for (String kind : List.of("establish","require")) for (var binding : elements(element,kind)) {
            var scope = scope(element);
            if (scope == null) throw new IllegalArgumentException("资格绑定不在资格作用域内：" + element.getId());
            String ref = required(binding,"qualificationRef");
            var definition = elements(scope,"qualification").stream().filter(q -> ref.equals(attr(q,"id"))).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未定义资格：" + ref));
            if ("establish".equals(kind)) {
                if (!(element instanceof ServiceTask task) || task.getFieldExtensions().stream().noneMatch(f -> "componentCommand".equals(f.getFieldName()) && "true".equals(f.getStringValue()))
                        || !"verifiedSuccess".equals(required(binding,"on")))
                    throw new IllegalArgumentException("establish 只支持组件命令 verifiedSuccess");
            } else {
                if (!required(binding,"targets").equals(attr(definition,"targets")) || !"all".equals(required(binding,"quantifier")))
                    throw new IllegalArgumentException("require 必须覆盖资格定义的全部 targets");
                String at = required(binding,"at");
                if (!Set.of("dispatch","completion").contains(at)) throw new IllegalArgumentException("不支持的 require.at：" + at);
                if ("completion".equals(at) && (!(element instanceof ServiceTask task) || task.getExecutionListeners().stream().noneMatch(l ->
                        "start".equals(l.getEvent()) && "${qualifications.check(execution)}".equals(l.getImplementation()))))
                    throw new IllegalArgumentException("completion require 缺少 check 监听器");
                if ("dispatch".equals(at) && (!(element instanceof ServiceTask task) || task.getFieldExtensions().stream().noneMatch(f ->
                        "componentCommand".equals(f.getFieldName()) && "true".equals(f.getStringValue()))))
                    throw new IllegalArgumentException("dispatch require 仅支持组件命令");
            }
        }
    }
}
