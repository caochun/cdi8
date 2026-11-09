package cn.cdi8.facility.contract;

import cn.cdi8.facility.contract.SystemDefinitionModel;
import java.util.*;
import org.w3c.dom.Element;
import static cn.cdi8.facility.contract.xml.ModelXml.*;

/** Typed conditions with acyclic fact references and explicit three-valued junctions. */
public final class FacilityStateProjectionModel {
    public static final String NS="https://cdi8.cn/facility-state/v1";
    public enum FactRole {
        STATE("state"), QUALIFICATION("qualification"), DERIVED("derived");
        private final String value;
        FactRole(String value){this.value=value;}
        public String value(){return value;}
        static FactRole parse(String value){
            return switch(value){
                case "state" -> STATE;
                case "qualification" -> QUALIFICATION;
                case "derived" -> DERIVED;
                default -> throw invalid("事实角色无效："+value);
            };
        }
    }
    public enum FactScope {
        FACILITY("facility"), INSTANCE_SET("instance-set");
        private final String value;
        FactScope(String value){this.value=value;}
        public String value(){return value;}
        static FactScope parse(String value){
            return switch(value){
                case "facility" -> FACILITY;
                case "instance-set" -> INSTANCE_SET;
                default -> throw invalid("事实范围无效："+value);
            };
        }
    }
    public record DeviceFact(String id,String componentType,String label,String states,String outerStates) {}
    public record Condition(String kind,String ref,String states,String outerStates,String expected,String action,boolean active,boolean anyDevice,List<Condition> terms) {}
    public record Predicate(String id,String label,FactRole role,FactScope scope,String scopeRef,Condition condition) {}
    public record Observation(String id,String adapter,Element source) {}
    private final Element root;
    private final long evidenceMaxAgeMs;
    private final Map<String,DeviceFact> deviceFacts=new LinkedHashMap<>();
    private final Map<String,Observation> observations=new LinkedHashMap<>();
    private final Map<String,Predicate> facts=new LinkedHashMap<>(),guards=new LinkedHashMap<>();
    private final List<Predicate> evaluationOrder=new ArrayList<>();

    private FacilityStateProjectionModel(Element root) {
        this.root=root;
        try{evidenceMaxAgeMs=Long.parseLong(root.getAttribute("evidenceMaxAgeMs"));}
        catch(NumberFormatException ex){throw invalid("evidenceMaxAgeMs 超出范围");}
        for(var e:entries(root,"observations")) {
            var source=child(e,"source");
            if(List.of("instanceSetRef","instanceGroupRef","controlNodeRef","externalEndpointRef").stream().filter(source::hasAttribute).count()!=1)
                throw invalid("观测来源必须恰好声明一种对象引用："+e.getAttribute("id"));
            observations.put(e.getAttribute("id"),new Observation(e.getAttribute("id"),e.getAttribute("adapter"),source));
        }
        for(var e:entries(root,"deviceFacts")) {
            statesRequired(e);
            String id=e.getAttribute("id");deviceFacts.put(id,new DeviceFact(id,e.getAttribute("typeRef"),e.getAttribute("label"),e.getAttribute("states"),e.getAttribute("outerStates")));
        }
        for(var e:entries(root,"facts"))facts.put(e.getAttribute("id"),predicate(e));
        for(var e:entries(root,"guards"))guards.put(e.getAttribute("id"),predicate(e));
        validateFactRoles();
        validateFactScopes();
        for(var p:concat(facts.values(),guards.values())) validateLocal(p.condition);
        var visited=new HashSet<String>();
        for(String id:facts.keySet())visit(id,new HashSet<>(),visited);
    }
    public String id(){return root.getAttribute("id");}
    public String definitionRef(){return root.getAttribute("definitionRef");}
    public String bindingRef(){return root.getAttribute("bindingRef");}
    public long evidenceMaxAgeMs(){return evidenceMaxAgeMs;}
    public int observationCount(){return observations.size();}
    public int factCount(){return facts.size();}
    public int guardCount(){return guards.size();}
    public boolean hasGuard(String id){return guards.containsKey(id);}
    public boolean hasFact(String id){return facts.containsKey(id);}
    public FactRole factRole(String id){
        var fact=facts.get(id);
        if(fact==null)throw invalid("事实引用未定义："+id);
        return fact.role();
    }
    public FactScope factScope(String id){
        var fact=facts.get(id);
        if(fact==null)throw invalid("事实引用未定义："+id);
        return fact.scope();
    }
    public String factScopeRef(String id){
        var fact=facts.get(id);
        if(fact==null)throw invalid("事实引用未定义："+id);
        return fact.scopeRef();
    }
    public List<Predicate> evaluationOrder(){return List.copyOf(evaluationOrder);}
    public Collection<Predicate> guards(){return Collections.unmodifiableCollection(guards.values());}
    public Collection<DeviceFact> deviceFacts(){return Collections.unmodifiableCollection(deviceFacts.values());}
    public Observation observation(String id){return observations.get(id);}

    /** Cross-document validation is mandatory before activating the three models together. */
    public void validateDependencies(SystemDefinitionModel definition) {
        if(!definition.id().equals(definitionRef()))throw invalid("设施投影 definitionRef 不一致");
        for(var fact:deviceFacts.values())if(!definition.hasType(fact.componentType))throw invalid("设备事实引用未定义类型："+fact.componentType);
        for(var observation:observations.values()) {
            var endpoint=definition.observationSource(observation.source);
            if(!"external".equals(observation.adapter)&&definition.endpointInstanceSets(endpoint).isEmpty())throw invalid("设备观测适配器必须引用实例集合或实例组："+observation.id);
            if(!"external".equals(observation.adapter)&&!"state-out".equals(endpoint.port()))throw invalid("设备快照适配器必须引用 state-out："+observation.id);
        }
        for(var p:concat(facts.values(),guards.values()))validateCross(p.condition,definition);
        for(var p:concat(facts.values(),guards.values()))validateScope(p,definition);
        for(var output:definition.dependencies().outputs()) {
            var fact=deviceFacts.get(output.fact());
            if(fact==null||!fact.componentType.equals(output.componentType()))throw invalid("输出信号引用未定义或类型不匹配的设备事实："+output.componentType()+" / "+output.fact());
        }
        for(var requirement:definition.dependencies().facts())requireFact(requirement.fact());
        for(var admission:definition.operationAdmissions())for(var requirement:admission.requirements())requireFact(requirement.fact());
        for(var constraint:definition.operatingConstraints()) {
            requireFact(constraint.activation());requireFact(constraint.release());
            for(var requirement:constraint.requirements())requireFact(requirement.fact());
            requireSupervisionSafe(constraint.id(),constraint.activation());
            requireSupervisionSafe(constraint.id(),constraint.release());
            for(var requirement:constraint.requirements())requireSupervisionSafe(constraint.id(),requirement.fact());
            var applicability=new HashSet<String>();
            factReferences(facts.get(constraint.activation()).condition,applicability);
            factReferences(facts.get(constraint.release()).condition,applicability);
            for(var requirement:constraint.requirements())if(applicability.contains(requirement.fact()))
                throw invalid("工作状态的启用/解除不能依赖被要求的事实："+constraint.id()+" / "+requirement.fact());
        }
    }
    private void requireFact(String ref){if(!facts.containsKey(ref))throw invalid("系统约束引用未定义事实："+ref);}
    private void requireSupervisionSafe(String constraint,String fact){
        if(!supervisionSafe(fact,new HashSet<>()))
            throw invalid("监督义务不能依赖流程资格事实："+constraint+" / "+fact);
    }
    private boolean supervisionSafe(String id,Set<String> visiting){
        if(!visiting.add(id))throw invalid("事实依赖存在环："+id);
        var predicate=facts.get(id);
        boolean safe=predicate.role()!=FactRole.QUALIFICATION;
        if(safe)safe=supervisionSafeCondition(predicate.condition(),visiting);
        visiting.remove(id);
        return safe;
    }
    private boolean supervisionSafeCondition(Condition condition,Set<String> visiting){
        if("factPredicate".equals(condition.kind())&&!supervisionSafe(condition.ref(),visiting))return false;
        for(var term:condition.terms())if(!supervisionSafeCondition(term,visiting))return false;
        return true;
    }
    private void factReferences(Condition condition,Set<String> refs) {
        if("factPredicate".equals(condition.kind)&&refs.add(condition.ref))factReferences(facts.get(condition.ref).condition,refs);
        condition.terms.forEach(term->factReferences(term,refs));
    }
    private void validateCross(Condition condition,SystemDefinitionModel definition) {
        if(Set.of("devicePredicate","actionPredicate").contains(condition.kind))definition.instanceSet(condition.ref);
        condition.terms.forEach(c->validateCross(c,definition));
    }
    private void validateFactRoles() {
        for(var fact:facts.values()) {
            if(fact.role()==FactRole.DERIVED&&!Set.of("all","any").contains(fact.condition().kind()))
                throw invalid("derived 事实必须由 all/any 组合定义："+fact.id());
        }
    }
    private void validateFactScopes() {
        for(var predicate:concat(facts.values(),guards.values())) {
            if(predicate.scope()==FactScope.FACILITY&&!predicate.scopeRef().isBlank())
                throw invalid("facility 范围不能声明 scopeRef："+predicate.id());
            if(predicate.scope()==FactScope.INSTANCE_SET&&predicate.scopeRef().isBlank())
                throw invalid("instance-set 范围必须声明 scopeRef："+predicate.id());
        }
    }
    private void validateScope(Predicate predicate,SystemDefinitionModel definition) {
        if(predicate.scope()!=FactScope.INSTANCE_SET)return;
        definition.instanceSet(predicate.scopeRef());
        validateScopedCondition(predicate.condition(),predicate.scopeRef(),definition,predicate.id());
    }
    private void validateScopedCondition(Condition condition,String scope,SystemDefinitionModel definition,String owner) {
        if(Set.of("devicePredicate","actionPredicate").contains(condition.kind())&&!scope.equals(condition.ref()))
            throw invalid("实例范围事实引用了其他实例集合："+owner+" / "+condition.ref());
        if("observationPredicate".equals(condition.kind())) {
            var observation=observations.get(condition.ref());
            var endpoint=definition.observationSource(observation.source);
            var sets=definition.endpointInstanceSets(endpoint);
            if(!sets.isEmpty()&&(sets.size()!=1||!scope.equals(sets.get(0))))
                throw invalid("实例范围事实的观测来源与 scopeRef 不一致："+owner+" / "+condition.ref());
            if(sets.isEmpty())throw invalid("实例范围事实不能引用设施级观测："+owner+" / "+condition.ref());
        }
        if("factPredicate".equals(condition.kind())) {
            var referenced=facts.get(condition.ref());
            if(referenced.scope()!=FactScope.INSTANCE_SET||!scope.equals(referenced.scopeRef()))
                throw invalid("实例范围事实不能引用其他范围事实："+owner+" / "+condition.ref());
        }
        condition.terms().forEach(term->validateScopedCondition(term,scope,definition,owner));
    }
    public void validateComponents(SystemDefinitionModel definition,FacilityComponentModels models) {
        validateDependencies(definition);
        for(var fact:deviceFacts.values())models.validateStates(fact.componentType,fact.states,fact.outerStates,"deviceFact "+fact.id);
        for(var predicate:concat(facts.values(),guards.values()))validateStates(predicate.condition,definition,models);
    }
    private void validateStates(Condition c,SystemDefinitionModel definition,FacilityComponentModels models) {
        if("devicePredicate".equals(c.kind))models.validateStates(definition.instanceSet(c.ref).type(),c.states,c.outerStates,"devicePredicate "+c.ref);
        if("actionPredicate".equals(c.kind)&&!models.model(definition.instanceSet(c.ref).type()).hasAction(c.action))
            throw invalid("actionPredicate 引用未知动作："+c.ref+" / "+c.action);
        if("observationPredicate".equals(c.kind)) {
            var observation=observations.get(c.ref);
            if("device-state".equals(observation.adapter))
                for(String set:definition.endpointInstanceSets(definition.endpoint(observation.source,true))) {
                    var model=models.model(definition.instanceSet(set).type());
                    for(String id:words(c.expected))if(!model.hasLeaf(id)&&!model.hasOuter(id))throw invalid("设备状态观测引用未知状态："+c.ref+" / "+id);
                }
        }
        c.terms.forEach(term->validateStates(term,definition,models));
    }
    private void validateLocal(Condition c) {
        if("factPredicate".equals(c.kind)&&!facts.containsKey(c.ref))throw invalid("事实引用未定义："+c.ref);
        if("observationPredicate".equals(c.kind)) {
            var observation=observations.get(c.ref);
            if(observation==null)throw invalid("观测引用未定义："+c.ref);
            if("device-connectivity".equals(observation.adapter)&&!c.expected.isBlank())throw invalid("连接观测不接受 expected 值");
            if("device-state".equals(observation.adapter)&&c.expected.isBlank())throw invalid("设备状态观测缺少 expected");
        }
        c.terms.forEach(this::validateLocal);
    }
    private void visit(String id,Set<String> visiting,Set<String> visited) {
        if(visited.contains(id))return;
        if(!visiting.add(id))throw invalid("事实依赖存在环："+id);
        if(visiting.size()>128)throw invalid("事实依赖层数超过 128");
        visitReferences(facts.get(id).condition,visiting,visited);
        visiting.remove(id);visited.add(id);evaluationOrder.add(facts.get(id));
    }
    private void visitReferences(Condition c,Set<String> visiting,Set<String> visited) {
        if("factPredicate".equals(c.kind))visit(c.ref,visiting,visited);
        c.terms.forEach(term->visitReferences(term,visiting,visited));
    }
    private static Predicate predicate(Element e){return new Predicate(e.getAttribute("id"),e.getAttribute("label"),
        FactRole.parse(e.hasAttribute("role")?e.getAttribute("role"):"state"),
        FactScope.parse(e.hasAttribute("scope")?e.getAttribute("scope"):"facility"),e.getAttribute("scopeRef"),
        condition(children(child(e,"condition")).get(0),0));}
    private static Condition condition(Element e,int depth) {
        if(depth>64)throw invalid("条件嵌套超过 64 层");
        String kind=e.getLocalName();
        if("devicePredicate".equals(kind))statesRequired(e);
        String ref=switch(kind){case "factPredicate"->e.getAttribute("factRef");case "observationPredicate"->e.getAttribute("observationRef");case "devicePredicate","actionPredicate"->e.getAttribute("instanceSetRef");default->"";};
        return new Condition(kind,ref,e.getAttribute("states"),e.getAttribute("outerStates"),e.getAttribute("expected"),e.getAttribute("actionRef"),
            Set.of("true","1").contains(e.getAttribute("active")),"any".equals(e.getAttribute("quantifier")),children(e).stream().map(term->condition(term,depth+1)).toList());
    }
    private static void statesRequired(Element e){if(e.getAttribute("states").isBlank()&&e.getAttribute("outerStates").isBlank())throw invalid("设备判据缺少 states 或 outerStates："+e.getAttribute("id"));}
    @SafeVarargs private static List<Predicate> concat(Collection<Predicate>... collections){var result=new ArrayList<Predicate>();for(var c:collections)result.addAll(c);return result;}
    public static FacilityStateProjectionModel parse(String xml){return new FacilityStateProjectionModel(cn.cdi8.facility.contract.xml.ModelXml.parse(xml,"facility-state-projection"));}
    private static IllegalArgumentException invalid(String message){return new IllegalArgumentException(message);}
}
