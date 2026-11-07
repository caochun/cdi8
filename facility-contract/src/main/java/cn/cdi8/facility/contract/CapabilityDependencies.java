package cn.cdi8.facility.contract;

import java.util.*;
import org.w3c.dom.Element;
import static cn.cdi8.facility.contract.xml.ModelXml.*;

/** Capability contracts use validated topology; connectivity alone creates no running dependency. */
public final class CapabilityDependencies {
    public record Input(String id,String path,String port,String signal,String maintain) {}
    public record Fact(String id,String fact,String maintain) {}
    public record Output(String componentType,String port,String signal,String fact,List<Input> inputs) {}
    public record Connection(String path,String id,String source,String sourcePort,String target,String targetPort,
                             String mappingRef,String resourceRef) {}
    private final Map<String,List<Input>> actions=new LinkedHashMap<>();
    private final Map<String,List<Fact>> actionFacts=new LinkedHashMap<>();
    private final Map<String,Output> outputs=new LinkedHashMap<>();
    private final List<Connection> connections=new ArrayList<>();
    private final Map<String,String> directions=new LinkedHashMap<>();
    private final Map<String,String> sourceRoles=new LinkedHashMap<>();
    private final SystemDefinitionModel definition;
    private CapabilityDependencies(SystemDefinitionModel definition){this.definition=definition;}

    public List<Input> inputs(String componentType,String capability){return actions.getOrDefault(componentType+"/"+capability,List.of());}
    public List<Fact> facts(String componentType,String capability){return actionFacts.getOrDefault(componentType+"/"+capability,List.of());}
    public Collection<Fact> facts(){return actionFacts.values().stream().flatMap(Collection::stream).toList();}
    public Collection<Output> outputs(){return Collections.unmodifiableCollection(outputs.values());}
    public Output output(String componentType,String port,String signal){return outputs.get(key(componentType,port,signal));}
    public List<Connection> incoming(String componentType,Input input){return connections.stream().filter(c->c.path.equals(input.path)&&c.target.equals(componentType)&&c.targetPort.equals(input.port)).toList();}
    public boolean isSource(Connection edge){return Set.of("source","alternate-source").contains(sourceRoles.getOrDefault(edge.path+"/"+edge.source,""));}
    public boolean allocated(Connection edge,int beamline) {
        if(edge.resourceRef.isBlank()||beamline<1||beamline>definition.resourceQuantity(edge.resourceRef))return false;
        if(edge.mappingRef.isBlank())return true;
        var mapping=definition.mapping(edge.mappingRef);
        return !"selection".equals(mapping.kind()) || mapping.members().stream().anyMatch(m->definition.instanceSet(m.instanceSet()).type().equals(edge.source)&&m.includes(beamline));
    }
    public boolean sourceAllowed(String source,int beamline){return connections.stream().filter(this::isSource).filter(e->e.source.equals(source)).anyMatch(e->allocated(e,beamline));}

    public static CapabilityDependencies parse(Element root,SystemDefinitionModel definition) {
        var model=new CapabilityDependencies(definition);
        for(var type:entries(root,"componentTypes")) {
            String componentType=type.getAttribute("id");
            for(var port:entries(type,"ports")) {
                String portId=port.getAttribute("id");model.directions.put(componentType+"/"+portId,port.getAttribute("direction"));
                for(var provision:entries(port,"outputs")) {
                    if(!Set.of("out","inout").contains(port.getAttribute("direction")))throw invalid("outputs 只能声明在输出端口："+componentType+"/"+portId);
                    String signal=provision.getAttribute("signalRef");
                    if(!definition.signalInterface(signal).equals(port.getAttribute("interfaceRef")))throw invalid("输出信号与端口接口不一致："+componentType+"/"+portId);
                    var output=new Output(componentType,portId,signal,provision.getAttribute("factRef"),model.parseInputs(provision,false));
                    if(model.outputs.putIfAbsent(key(componentType,portId,signal),output)!=null)throw invalid("重复输出信号："+componentType+"/"+portId+"/"+signal);
                }
            }
            for(var capability:entries(type,"capabilities")) {
                var inputs=model.parseInputs(capability,true);
                var facts=parseFacts(capability);
                if((!inputs.isEmpty()||!facts.isEmpty())&&capability.getAttribute("actionRef").isBlank())throw invalid("依赖能力必须具有 actionRef："+componentType+"/"+capability.getAttribute("id"));
                model.actions.put(componentType+"/"+capability.getAttribute("id"),inputs);
                model.actionFacts.put(componentType+"/"+capability.getAttribute("id"),facts);
            }
        }
        for(var path:entries(child(root,"topology"),"paths")) {
            String id=path.getAttribute("id");
            for(var ep:entries(path,"endpoints")) if(ep.hasAttribute("instanceSetRef"))
                model.sourceRoles.put(id+"/"+definition.instanceSet(ep.getAttribute("instanceSetRef")).type(),ep.getAttribute("role"));
            for(var c:entries(path,"connections")) {
                var source=definition.endpoint(child(c,"source"),true);var target=definition.endpoint(child(c,"target"),true);
                // Retain unsupported upstream kinds too: an executable input using them must fail validation,
                // even when another incoming edge happens to be supported.
                String sourceType="instanceSetRef".equals(source.kind())?definition.instanceSet(source.ref()).type():source.kind()+"/"+source.ref();
                for(String targetSet:definition.endpointInstanceSets(target)) {
                    String targetType=definition.instanceSet(targetSet).type();
                    model.connections.add(new Connection(id,c.getAttribute("id"),sourceType,source.port(),targetType,target.port(),c.getAttribute("mappingRef"),path.getAttribute("resourceRef")));
                }
            }
        }
        model.actions.forEach((key,inputs)->inputs.forEach(input->model.validateInput(key.substring(0,key.indexOf('/')),input)));
        model.outputs.values().forEach(output->output.inputs.forEach(input->model.validateInput(output.componentType,input)));
        var visited=new HashSet<String>();
        for(var output:model.outputs.values())model.visit(output,new HashSet<>(),visited);
        return model;
    }
    private void validateInput(String componentType,Input input) {
        if(!Set.of("in","inout").contains(directions.getOrDefault(componentType+"/"+input.port,"")))throw invalid("依赖引用未定义输入端口："+componentType+"/"+input.port);
        var incoming=incoming(componentType,input);
        if(incoming.isEmpty())throw invalid("依赖输入没有拓扑连接："+componentType+"/"+input.port+" @ "+input.path);
        for(var edge:incoming) {
            if(output(edge.source,edge.sourcePort,input.signal)==null)throw invalid("上游未声明输出信号："+edge.source+"/"+edge.sourcePort+" / "+input.signal);
            if(edge.resourceRef.isBlank())throw invalid("能力依赖路径缺少资源作用域："+edge.path);
            for(String type:List.of(edge.source,edge.target)) {
                var sets=definition.instanceSets().stream().filter(s->s.type().equals(type)).toList();
                if(sets.size()!=1)throw invalid("当前能力依赖要求类型唯一对应实例集合："+type);
                if(sets.get(0).quantity()>1&&definition.mappings().stream().filter(m->m.resource().equals(edge.resourceRef))
                    .noneMatch(m->m.members().stream().anyMatch(member->member.instanceSet().equals(sets.get(0).id()))))throw invalid("依赖端点没有资源映射："+type);
            }
            if(isSource(edge)&&(edge.mappingRef.isBlank()||!"selection".equals(definition.mapping(edge.mappingRef).kind())))throw invalid("光源依赖连接必须引用选源映射："+edge.id);
        }
    }
    private void visit(Output output,Set<String> visiting,Set<String> visited) {
        String key=key(output.componentType,output.port,output.signal);
        if(visited.contains(key))return;
        if(!visiting.add(key))throw invalid("输出依赖存在环："+key);
        for(var input:output.inputs)for(var edge:incoming(output.componentType,input))visit(output(edge.source,edge.sourcePort,input.signal),visiting,visited);
        visiting.remove(key);visited.add(key);
    }
    private List<Input> parseInputs(Element parent,boolean capability) {
        var result=new ArrayList<Input>();
        var ids=new HashSet<String>();
        for(var req:entries(parent,"requirements")) {
            if(!ids.add(req.getAttribute("id")))throw invalid("依赖 id 重复："+req.getAttribute("id"));
            if("factRequirement".equals(req.getLocalName())) {
                if(!capability)throw invalid("输出依赖只接受 inputRequirement");
                continue;
            }
            String maintain=capability?required(req,"maintain"):"execution";
            if(!capability&&req.hasAttribute("maintain"))throw invalid("输出依赖继承调用者保持范围，不能指定 maintain");
            String signal=req.getAttribute("signalRef");
            if(!definition.hasSignal(signal))throw invalid("输入依赖引用未定义信号："+signal);
            result.add(new Input(req.getAttribute("id"),req.getAttribute("pathRef"),req.getAttribute("inputPortRef"),signal,maintain));
        }
        return List.copyOf(result);
    }
    public static List<Fact> parseFacts(Element parent) {
        return entries(parent,"requirements").stream().filter(e->"factRequirement".equals(e.getLocalName()))
            .map(e->new Fact(e.getAttribute("id"),e.getAttribute("factRef"),e.getAttribute("maintain"))).toList();
    }
    private static String key(String componentType,String port,String signal){return componentType+"/"+port+"/"+signal;}
    private static IllegalArgumentException invalid(String message){return new IllegalArgumentException(message);}
}
