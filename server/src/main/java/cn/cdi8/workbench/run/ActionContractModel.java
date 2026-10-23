package cn.cdi8.workbench.run;

import java.util.*;
import org.flowable.bpmn.model.*;

/** BPMN declarations for device-native readiness and optional experiment entry restrictions. */
public final class ActionContractModel {
    public static final String NS="https://cdi8.cn/control/action-contract/v1";
    private ActionContractModel() {}
    public static List<ExtensionElement> elements(BaseElement element,String name) {
        return element.getExtensionElements().getOrDefault(name,List.of()).stream().filter(e->NS.equals(e.getNamespace())).toList();
    }
    public static String attr(ExtensionElement element,String name) {
        var value=element.getAttributes().get(name);return value==null||value.isEmpty()?"":value.get(0).getValue();
    }
    public static void validateElement(FlowElement element) {
        var contracts=elements(element,"actionContract");
        var entries=elements(element,"entryCondition");
        if(contracts.isEmpty()&&entries.isEmpty())return;
        if(!(element instanceof ServiceTask task)||task.getFieldExtensions().stream().noneMatch(f->"componentCommand".equals(f.getFieldName())&&"true".equals(f.getStringValue())))
            throw new IllegalArgumentException("动作契约和进入条件仅用于组件命令："+element.getId());
        if(contracts.size()>1||entries.size()>1)throw new IllegalArgumentException("组件命令最多声明一个动作契约和进入条件："+element.getId());
        if(!contracts.isEmpty()&&!contracts.get(0).getAttributes().isEmpty())throw new IllegalArgumentException("actionContract 不接受属性");
        if(!entries.isEmpty()) {
            var entry=entries.get(0);
            if(entry.getAttributes().keySet().stream().anyMatch(name->!Set.of("states","outerStates","idle").contains(name)))
                throw new IllegalArgumentException("entryCondition 包含不支持的属性");
            if(!"true".equals(attr(entry,"idle"))||(attr(entry,"states").isBlank()&&attr(entry,"outerStates").isBlank()))
                throw new IllegalArgumentException("entryCondition 必须指定状态且要求 idle=true");
        }
        if(task.getFieldExtensions().stream().noneMatch(f->"expectedState".equals(f.getFieldName())&&f.getStringValue()!=null&&!f.getStringValue().isBlank()))
            throw new IllegalArgumentException("动作契约命令缺少 expectedState："+element.getId());
    }
}
