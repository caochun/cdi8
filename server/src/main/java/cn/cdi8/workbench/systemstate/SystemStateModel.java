package cn.cdi8.workbench.systemstate;

import java.io.StringReader;
import java.util.*;
import javax.xml.stream.*;
import org.flowable.bpmn.model.*;

/** Declarative whole-system states and evidence-gated transitions. */
public final class SystemStateModel {
    public static final String NS = "https://cdi8.cn/system-state/v1";
    public record Predicate(String collection, String component, Set<String> states, Set<String> outerStates) {}
    public record State(String id, String label, List<Predicate> predicates) {}
    public record Transition(String id, String from, String to) {}

    private final String id;
    private final String initial;
    private final long evidenceMaxAgeMs;
    private final Map<String, State> states;
    private final Map<String, Transition> transitions;

    private SystemStateModel(String id, String initial, long evidenceMaxAgeMs,
                             Map<String, State> states, Map<String, Transition> transitions) {
        this.id=id; this.initial=initial; this.evidenceMaxAgeMs=evidenceMaxAgeMs;
        this.states=Map.copyOf(states); this.transitions=Map.copyOf(transitions);
    }
    public String id() { return id; }
    public String initial() { return initial; }
    public long evidenceMaxAgeMs() { return evidenceMaxAgeMs; }
    public State state(String id) {
        State value=states.get(id);
        if(value==null) throw new IllegalArgumentException("系统状态未定义："+id);
        return value;
    }
    public Transition transition(String id) {
        Transition value=transitions.get(id);
        if(value==null) throw new IllegalArgumentException("系统状态转换未定义："+id);
        return value;
    }
    public Set<String> stateIds() { return states.keySet(); }
    public Collection<State> states() { return states.values(); }

    public static SystemStateModel parse(String xml) {
        try {
            var factory=XMLInputFactory.newFactory();
            factory.setProperty(XMLInputFactory.SUPPORT_DTD,false);
            factory.setProperty("javax.xml.stream.isSupportingExternalEntities",false);
            var reader=factory.createXMLStreamReader(new StringReader(xml));
            String id=null,initial=null;long maxAge=2000;
            Map<String,State> states=new LinkedHashMap<>();Map<String,Transition> transitions=new LinkedHashMap<>();
            StateBuilder state=null;
            while(reader.hasNext()) {
                int event=reader.next();
                if(event==XMLStreamConstants.START_ELEMENT) {
                    String name=reader.getLocalName();
                    if("system-state-model".equals(name)) {
                        if(!NS.equals(reader.getNamespaceURI()))throw new IllegalArgumentException("系统状态模型命名空间错误");
                        id=required(reader,"id");initial=required(reader,"initial");
                        String age=reader.getAttributeValue(null,"evidenceMaxAgeMs");if(age!=null)maxAge=Long.parseLong(age);
                    } else if("state".equals(name)) {
                        state=new StateBuilder(required(reader,"id"),required(reader,"label"));
                    } else if("require".equals(name)) {
                        if(state==null)throw new IllegalArgumentException("系统状态条件必须位于 state 内");
                        String collection=required(reader,"collection"),component=required(reader,"component");
                        Set<String> leaves=words(reader.getAttributeValue(null,"states"));
                        Set<String> outers=words(reader.getAttributeValue(null,"outerStates"));
                        if(leaves.isEmpty()&&outers.isEmpty())throw new IllegalArgumentException("系统状态 require 缺少 states 或 outerStates");
                        state.predicates.add(new Predicate(collection,component,leaves,outers));
                    } else if("transition".equals(name)) {
                        var transition=new Transition(required(reader,"id"),required(reader,"from"),required(reader,"to"));
                        if(transitions.putIfAbsent(transition.id(),transition)!=null)throw new IllegalArgumentException("系统状态转换重复："+transition.id());
                    } else if(!"system-state-model".equals(name))throw new IllegalArgumentException("不支持的系统状态元素："+name);
                } else if(event==XMLStreamConstants.END_ELEMENT && "state".equals(reader.getLocalName())) {
                    if(state==null)throw new IllegalArgumentException("系统状态格式错误");
                    if(states.putIfAbsent(state.id,new State(state.id,state.label,List.copyOf(state.predicates)))!=null)
                        throw new IllegalArgumentException("系统状态重复："+state.id);
                    state=null;
                }
            }
            reader.close();
            if(id==null||initial==null||!states.containsKey(initial))throw new IllegalArgumentException("系统状态模型缺少有效 initial");
            for(var stateDefinition:states.values())if(!stateDefinition.id().equals(initial)&&stateDefinition.predicates().isEmpty())
                throw new IllegalArgumentException("非初始系统状态必须声明设备条件："+stateDefinition.id());
            if(maxAge<100||maxAge>60000)throw new IllegalArgumentException("evidenceMaxAgeMs 必须在 100–60000 之间");
            for(var transition:transitions.values())if(!states.containsKey(transition.from())||!states.containsKey(transition.to()))
                throw new IllegalArgumentException("系统状态转换引用未定义状态："+transition.id());
            for(var transition:transitions.values())if(transition.from().equals(transition.to()))
                throw new IllegalArgumentException("系统状态转换不能指向自身："+transition.id());
            return new SystemStateModel(id,initial,maxAge,states,transitions);
        } catch(XMLStreamException ex) { throw new IllegalArgumentException("系统状态模型 XML 无效："+ex.getMessage(),ex); }
    }
    private static String required(XMLStreamReader reader,String name) {
        String value=reader.getAttributeValue(null,name);
        if(value==null||value.isBlank())throw new IllegalArgumentException("缺少属性 "+name+"（"+reader.getLocalName()+"）");
        return value.trim();
    }
    private static Set<String> words(String value) {
        if(value==null||value.isBlank())return Set.of();
        return Set.of(value.trim().split("\\s+"));
    }
    private static final class StateBuilder {
        final String id,label;final List<Predicate> predicates=new ArrayList<>();
        StateBuilder(String id,String label){this.id=id;this.label=label;}
    }

    public static void validateProcess(BpmnModel model) {
        for(var process:model.getProcesses()) {
            var processElements=new ArrayList<BaseElement>();processElements.add(process);processElements.addAll(owned(process));
            for(var element:processElements)for(var extensions:element.getExtensionElements().values())for(var extension:extensions)
                if("https://cdi8.cn/control/qualification/v1".equals(extension.getNamespace()))
                    throw new IllegalArgumentException("ctl 资格扩展已移除，请迁移到 system-state-model 或 action-contract："+element.getId());
            var bindings=elements(process,"systemModel");
            boolean uses=owned(process).stream().anyMatch(e->!elements(e,"report").isEmpty()||!elements(e,"requires").isEmpty());
            if(uses&&bindings.size()!=1)throw new IllegalArgumentException("使用系统状态的流程必须引用一个 systemModel");
            if(bindings.size()>1)throw new IllegalArgumentException("每个流程只能引用一个 systemModel");
            for(var element:owned(process)) {
                var reports=elements(element,"report");var requires=elements(element,"requires");
                if(reports.size()>1||requires.size()>1)throw new IllegalArgumentException("一个 BPMN 节点最多声明一个系统状态引用："+element.getId());
                for(var report:reports) {
                    String transition=attr(report,"transition");
                    if(transition.isBlank())throw new IllegalArgumentException("report 缺少 transition："+element.getId());
                    if(!(element instanceof ServiceTask))throw new IllegalArgumentException("report 只能用于 serviceTask："+element.getId());
                }
                for(var require:requires) {
                    String state=attr(require,"state");
                    if(state.isBlank())throw new IllegalArgumentException("requires 缺少 state："+element.getId());
                    if(!(element instanceof ServiceTask))throw new IllegalArgumentException("requires 只能用于 serviceTask："+element.getId());
                }
            }
        }
    }
    public static List<ExtensionElement> elements(BaseElement element,String name) {
        return element.getExtensionElements().getOrDefault(name,List.of()).stream().filter(e->NS.equals(e.getNamespace())).toList();
    }
    public static String attr(ExtensionElement element,String name) {
        var value=element.getAttributes().get(name);return value==null||value.isEmpty()?"":value.get(0).getValue();
    }
    private static List<FlowElement> owned(FlowElementsContainer container) {
        var result=new ArrayList<FlowElement>();
        for(var element:container.getFlowElements()) { result.add(element);if(element instanceof SubProcess sub)result.addAll(owned(sub)); }
        return result;
    }
}
