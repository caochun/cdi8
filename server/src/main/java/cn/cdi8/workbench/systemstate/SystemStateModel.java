package cn.cdi8.workbench.systemstate;

import java.io.StringReader;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import org.flowable.bpmn.model.*;

/** Declarative whole-system states and evidence-gated transitions. */
public final class SystemStateModel {
    public static final String NS = "https://cdi8.cn/system-state/v1";
    public record Predicate(String collection, String component, Set<String> states, Set<String> outerStates) {
        public Set<String> components() { return words(component); }
        public boolean matchesComponent(String value) { return components().contains(value); }
    }
    public record Fact(String id, String label, List<Predicate> predicates) {}
    public record Anomaly(String id, String label, String category, Set<String> states, Set<String> outerStates) {}
    public record State(String id, String label, List<Predicate> predicates, List<Predicate> entryPredicates) {
        public List<Predicate> allPredicates() {
            var result = new ArrayList<>(predicates); result.addAll(entryPredicates); return List.copyOf(result);
        }
    }
    public record Transition(String id, String from, String to, String operation, String blockedReason) {}

    private final String id;
    private final String initial;
    private final long evidenceMaxAgeMs;
    private final Map<String, Fact> facts;
    private final Map<String, Anomaly> anomalies;
    private final Map<String, State> states;
    private final Map<String, Transition> transitions;

    private SystemStateModel(String id, String initial, long evidenceMaxAgeMs,
                             Map<String, Fact> facts, Map<String, Anomaly> anomalies,
                             Map<String, State> states, Map<String, Transition> transitions) {
        this.id=id; this.initial=initial; this.evidenceMaxAgeMs=evidenceMaxAgeMs;
        this.facts=Collections.unmodifiableMap(new LinkedHashMap<>(facts));
        this.anomalies=Collections.unmodifiableMap(new LinkedHashMap<>(anomalies));
        this.states=Collections.unmodifiableMap(new LinkedHashMap<>(states));
        this.transitions=Collections.unmodifiableMap(new LinkedHashMap<>(transitions));
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
    public Collection<Fact> facts() { return facts.values(); }
    public Anomaly anomaly(String id) {
        Anomaly value=anomalies.get(id);
        if(value==null) throw new IllegalArgumentException("系统异常类型未定义："+id);
        return value;
    }
    public Collection<Anomaly> anomalies() { return anomalies.values(); }
    public Collection<Transition> transitions() { return transitions.values(); }

    public static SystemStateModel parse(String xml) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            var root = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml))).getDocumentElement();
            if (!"system-state-model".equals(root.getLocalName()) || !NS.equals(root.getNamespaceURI()))
                throw new IllegalArgumentException("系统状态模型根元素或命名空间错误");
            String id = required(root, "id"), initial = required(root, "initial");
            long maxAge = root.hasAttribute("evidenceMaxAgeMs") ? Long.parseLong(root.getAttribute("evidenceMaxAgeMs")) : 2000;
            Map<String, Fact> facts = new LinkedHashMap<>();
            Map<String, Anomaly> anomalies = new LinkedHashMap<>();
            Map<String, State> states = new LinkedHashMap<>();
            Map<String, Transition> transitions = new LinkedHashMap<>();
            var children = children(root);
            // Definitions may follow their uses; facts contain conjunctions of device predicates only.
            for (var child : children) if ("fact".equals(child.getLocalName())) {
                var predicates = new ArrayList<Predicate>();
                for (var condition : children(child)) {
                    if (!"require".equals(condition.getLocalName()) || condition.hasAttribute("scope"))
                        throw new IllegalArgumentException("fact 只能包含无 scope 的 require");
                    predicates.add(predicate(condition));
                }
                if (predicates.isEmpty()) throw new IllegalArgumentException("系统事实不能为空");
                var fact = new Fact(required(child, "id"), required(child, "label"), List.copyOf(predicates));
                if (facts.putIfAbsent(fact.id(), fact) != null) throw new IllegalArgumentException("系统事实重复：" + fact.id());
            }
            for (var child : children) if ("anomaly".equals(child.getLocalName())) {
                if (!children(child).isEmpty()) throw new IllegalArgumentException("anomaly 不能包含子元素");
                var leafStates=words(child.getAttribute("states"));var outerStates=words(child.getAttribute("outerStates"));
                String category=required(child,"category");
                if(leafStates.isEmpty()&&outerStates.isEmpty()&& !Set.of("stateConditionLost","unknown","commandOutcomeUnknown").contains(category))
                    throw new IllegalArgumentException("系统异常类型缺少可观察状态："+child.getAttribute("id"));
                var anomaly=new Anomaly(required(child,"id"),required(child,"label"),category,leafStates,outerStates);
                if(!Set.of("deviceFault","safetyInterlock","stateConditionLost","unknown","commandOutcomeUnknown").contains(anomaly.category()))
                    throw new IllegalArgumentException("未知系统异常分类："+anomaly.category());
                if(anomalies.putIfAbsent(anomaly.id(),anomaly)!=null)throw new IllegalArgumentException("系统异常类型重复："+anomaly.id());
            }
            for (var child : children) switch (child.getLocalName()) {
                case "fact" -> { }
                case "anomaly" -> { }
                case "state" -> {
                    var invariant = new ArrayList<Predicate>(); var entry = new ArrayList<Predicate>();
                    for (var condition : children(child)) {
                        String scope = condition.hasAttribute("scope") ? condition.getAttribute("scope") : "invariant";
                        if (!Set.of("invariant", "entry").contains(scope)) throw new IllegalArgumentException("未知条件 scope：" + scope);
                        List<Predicate> predicates;
                        if ("require".equals(condition.getLocalName())) predicates = List.of(predicate(condition));
                        else if ("use".equals(condition.getLocalName())) {
                            String ref = required(condition, "fact");
                            if (!facts.containsKey(ref)) throw new IllegalArgumentException("引用未定义系统事实：" + ref);
                            if (!children(condition).isEmpty()) throw new IllegalArgumentException("use 不能包含子元素");
                            predicates = facts.get(ref).predicates();
                        } else throw new IllegalArgumentException("不支持的系统状态条件：" + condition.getLocalName());
                        ("entry".equals(scope) ? entry : invariant).addAll(predicates);
                    }
                    var state = new State(required(child, "id"), required(child, "label"), List.copyOf(invariant), List.copyOf(entry));
                    if (states.putIfAbsent(state.id(), state) != null) throw new IllegalArgumentException("系统状态重复：" + state.id());
                }
                case "transition" -> {
                    if (!children(child).isEmpty()) throw new IllegalArgumentException("transition 不能包含子元素");
                    var transition = new Transition(required(child, "id"), required(child, "from"), required(child, "to"),
                        child.getAttribute("operation"), child.getAttribute("blocked"));
                    if (transitions.putIfAbsent(transition.id(), transition) != null)
                        throw new IllegalArgumentException("系统状态转换重复：" + transition.id());
                }
                default -> throw new IllegalArgumentException("不支持的系统状态元素：" + child.getLocalName());
            }
            if (!states.containsKey(initial)) throw new IllegalArgumentException("系统状态模型缺少有效 initial");
            for (var state : states.values()) if (!state.id().equals(initial) && state.predicates().isEmpty())
                throw new IllegalArgumentException("非初始系统状态必须声明设备条件：" + state.id());
            if (maxAge < 100 || maxAge > 60000) throw new IllegalArgumentException("evidenceMaxAgeMs 必须在 100–60000 之间");
            for (var transition : transitions.values()) {
                if (!states.containsKey(transition.from()) || !states.containsKey(transition.to()))
                    throw new IllegalArgumentException("系统状态转换引用未定义状态：" + transition.id());
                if (transition.from().equals(transition.to())) throw new IllegalArgumentException("系统状态转换不能指向自身：" + transition.id());
            }
            return new SystemStateModel(id, initial, maxAge, facts, anomalies, states, transitions);
        } catch (IllegalArgumentException ex) { throw ex; }
        catch (Exception ex) { throw new IllegalArgumentException("系统状态模型 XML 无效：" + ex.getMessage(), ex); }
    }
    private static List<Element> children(Element parent) {
        var result = new ArrayList<Element>();
        for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) if (node instanceof Element child) {
            if (!NS.equals(child.getNamespaceURI())) throw new IllegalArgumentException("系统状态子元素命名空间错误");
            result.add(child);
        }
        return result;
    }
    private static Predicate predicate(Element element) {
        if (!children(element).isEmpty()) throw new IllegalArgumentException("require 不能包含子元素");
        var leaves = words(element.getAttribute("states")); var outers = words(element.getAttribute("outerStates"));
        if (leaves.isEmpty() && outers.isEmpty()) throw new IllegalArgumentException("系统状态 require 缺少 states 或 outerStates");
        return new Predicate(required(element, "collection"), required(element, "component"), leaves, outers);
    }
    private static String required(Element element, String name) {
        String value = element.getAttribute(name).trim();
        if (value.isEmpty()) throw new IllegalArgumentException("缺少属性 " + name + "（" + element.getLocalName() + "）");
        return value;
    }
    private static Set<String> words(String value) {
        if (value == null || value.isBlank()) return Set.of();
        return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(value.trim().split("\\s+"))));
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
