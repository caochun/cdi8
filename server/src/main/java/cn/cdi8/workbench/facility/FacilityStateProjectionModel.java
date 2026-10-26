package cn.cdi8.workbench.facility;

import cn.cdi8.workbench.systemdefinition.SystemDefinitionModel;
import java.util.*;
import org.w3c.dom.Element;
import static cn.cdi8.workbench.xml.ModelXml.*;

/** Typed conditions with acyclic fact references and explicit three-valued junctions. */
public final class FacilityStateProjectionModel {
    public static final String NS="https://cdi8.cn/facility-state/v1";
    public record DeviceFact(String id,String component,String label,String states,String outerStates) {}
    public record Condition(String kind,String ref,String states,String outerStates,String expected,List<Condition> terms) {}
    public record Predicate(String id,String label,Condition condition) {}
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
    public List<Predicate> evaluationOrder(){return List.copyOf(evaluationOrder);}
    public Collection<Predicate> guards(){return Collections.unmodifiableCollection(guards.values());}
    public Collection<DeviceFact> deviceFacts(){return Collections.unmodifiableCollection(deviceFacts.values());}
    public Observation observation(String id){return observations.get(id);}

    /** Cross-document validation is mandatory before activating the three models together. */
    public void validateDependencies(SystemDefinitionModel definition) {
        if(!definition.id().equals(definitionRef()))throw invalid("设施投影 definitionRef 不一致");
        for(var fact:deviceFacts.values())if(!definition.hasType(fact.component))throw invalid("设备事实引用未定义类型："+fact.component);
        for(var observation:observations.values()) {
            var endpoint=definition.observationSource(observation.source);
            if(!"external".equals(observation.adapter)&&definition.endpointInstanceSets(endpoint).isEmpty())throw invalid("设备观测适配器必须引用实例集合或实例组："+observation.id);
            if(!"external".equals(observation.adapter)&&!"state-out".equals(endpoint.port()))throw invalid("设备快照适配器必须引用 state-out："+observation.id);
        }
        for(var p:concat(facts.values(),guards.values()))validateCross(p.condition,definition);
        for(var output:definition.dependencies().outputs()) {
            var fact=deviceFacts.get(output.fact());
            if(fact==null||!fact.component.equals(output.component()))throw invalid("输出信号引用未定义或类型不匹配的设备事实："+output.component()+" / "+output.fact());
        }
    }
    private void validateCross(Condition condition,SystemDefinitionModel definition) {
        if("devicePredicate".equals(condition.kind))definition.instanceSet(condition.ref);
        condition.terms.forEach(c->validateCross(c,definition));
    }
    public void validateComponents(SystemDefinitionModel definition,FacilityComponentModels models) {
        validateDependencies(definition);
        for(var fact:deviceFacts.values())models.validateStates(fact.component,fact.states,fact.outerStates,"deviceFact "+fact.id);
        for(var predicate:concat(facts.values(),guards.values()))validateStates(predicate.condition,definition,models);
    }
    private void validateStates(Condition c,SystemDefinitionModel definition,FacilityComponentModels models) {
        if("devicePredicate".equals(c.kind))models.validateStates(definition.instanceSet(c.ref).type(),c.states,c.outerStates,"devicePredicate "+c.ref);
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
    private static Predicate predicate(Element e){return new Predicate(e.getAttribute("id"),e.getAttribute("label"),condition(children(child(e,"condition")).get(0),0));}
    private static Condition condition(Element e,int depth) {
        if(depth>64)throw invalid("条件嵌套超过 64 层");
        String kind=e.getLocalName();
        if("devicePredicate".equals(kind))statesRequired(e);
        String ref=switch(kind){case "factPredicate"->e.getAttribute("factRef");case "observationPredicate"->e.getAttribute("observationRef");case "devicePredicate"->e.getAttribute("instanceSetRef");default->"";};
        return new Condition(kind,ref,e.getAttribute("states"),e.getAttribute("outerStates"),e.getAttribute("expected"),children(e).stream().map(term->condition(term,depth+1)).toList());
    }
    private static void statesRequired(Element e){if(e.getAttribute("states").isBlank()&&e.getAttribute("outerStates").isBlank())throw invalid("设备判据缺少 states 或 outerStates："+e.getAttribute("id"));}
    @SafeVarargs private static List<Predicate> concat(Collection<Predicate>... collections){var result=new ArrayList<Predicate>();for(var c:collections)result.addAll(c);return result;}
    public static FacilityStateProjectionModel parse(String xml){return new FacilityStateProjectionModel(cn.cdi8.workbench.xml.ModelXml.parse(xml,"facility-state-projection"));}
    private static IllegalArgumentException invalid(String message){return new IllegalArgumentException(message);}
}
