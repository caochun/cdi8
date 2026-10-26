package cn.cdi8.workbench.systemdefinition;

import java.util.*;
import org.w3c.dom.Element;
import static cn.cdi8.workbench.xml.ModelXml.*;

/** Static composition and executable structural contracts, independent of live device state. */
public final class SystemDefinitionModel {
    public static final String NS = "https://cdi8.cn/system-definition/v1";
    public record InstanceSet(String id, String type, int quantity, String pattern) {
        public String logical(int index) {
            if (index < 1 || index > quantity) throw invalid("实例索引越界：" + id + "/" + index);
            return pattern.replace("{index}", String.format(Locale.ROOT, "%02d", index));
        }
    }
    public record Member(String instanceSet, int first, int last, boolean defaultSource) {
        public boolean includes(int index) { return index >= first && index <= last; }
    }
    public record Mapping(String id, String kind, String resource, int groupSize, List<Member> members) {}
    public record Endpoint(String kind, String ref, String port) {}
    private final Element root;
    private final Map<String,Element> types, controls, externals, interfaces, signals, paths;
    private final Map<String,Integer> resources = new LinkedHashMap<>();
    private final Map<String,InstanceSet> instanceSets = new LinkedHashMap<>();
    private final Map<String,List<String>> groups = new LinkedHashMap<>();
    private final Map<String,Mapping> mappings = new LinkedHashMap<>();
    private final CapabilityDependencies dependencies;
    private int connections;

    private SystemDefinitionModel(Element root) {
        this.root = root;
        types = index(entries(root,"componentTypes")); controls = index(entries(root,"controlNodes"));
        externals = index(entries(root,"externalEndpoints")); interfaces = index(entries(root,"interfaces"));
        signals = index(entries(root,"signals")); paths = index(entries(child(root,"topology"),"paths"));
        for (var e : entries(root,"resources")) resources.put(e.getAttribute("id"), Integer.parseInt(e.getAttribute("quantity")));
        for (var signal : signals.values()) requireRef(interfaces, signal.getAttribute("interfaceRef"), "信号接口");
        for (var owner : concat(types.values(),controls.values(),externals.values())) {
            if (owner.hasAttribute("interfaceRef")) requireRef(interfaces, owner.getAttribute("interfaceRef"), "对象接口");
            if (owner.hasAttribute("stateModelHref")) required(owner,"stateModelHref");
            for (var port : entries(owner,"ports")) {
                requireRef(interfaces, port.getAttribute("interfaceRef"), "端口接口");
                if (!"componentType".equals(owner.getLocalName()) && child(port,"outputs") != null)
                    throw invalid("当前只支持组件端口声明运行输出：" + owner.getAttribute("id"));
            }
            for (var cap : entries(owner,"capabilities"))
                if (!"componentType".equals(owner.getLocalName()) && child(cap,"requirements") != null)
                    throw invalid("当前只支持组件能力声明运行依赖：" + owner.getAttribute("id"));
        }
        var logicalIds = new HashSet<String>();
        for (var e : entries(root,"instanceSets")) {
            var set = new InstanceSet(e.getAttribute("id"), e.getAttribute("typeRef"), Integer.parseInt(e.getAttribute("quantity")), e.getAttribute("pattern"));
            requireRef(types,set.type,"实例类型");
            if (!set.pattern.matches("[^{}]+\\{index}")) throw invalid("实例模板必须是前缀加 {index}：" + set.id);
            instanceSets.put(set.id,set);
            for (int i=1;i<=set.quantity;i++) if (!logicalIds.add(set.logical(i))) throw invalid("逻辑实例名重复："+set.logical(i));
        }
        for (var e : entries(root,"instanceGroups")) {
            var members = new LinkedHashSet<String>();
            for (var member : entries(e,"members")) {
                String ref = member.getAttribute("instanceSetRef"); instanceSet(ref);
                if (!members.add(ref)) throw invalid("实例组成员重复："+ref);
            }
            groups.put(e.getAttribute("id"),List.copyOf(members));
        }
        for (var e : entries(root,"mappings")) parseMapping(e);
        for (var path : paths.values()) {
            if (path.hasAttribute("resourceRef")) resourceQuantity(path.getAttribute("resourceRef"));
            var endpointIds = new HashSet<String>();
            for (var ep : entries(path,"endpoints")) {
                var endpoint = endpoint(ep,false);
                if (!endpointIds.add(endpoint.kind+"/"+endpoint.ref)) throw invalid("路径端点重复："+endpoint.ref);
            }
            for (var edge : entries(path,"connections")) {
                var source = endpoint(child(edge,"source"),true); var target = endpoint(child(edge,"target"),true);
                for (var port : ports(source)) if (!Set.of("out","inout").contains(port.getAttribute("direction"))) throw invalid("连接源不是输出端口："+source);
                for (var port : ports(target)) if (!Set.of("in","inout").contains(port.getAttribute("direction"))) throw invalid("连接目标不是输入端口："+target);
                for (var a : ports(source)) for (var b : ports(target)) {
                    String ai=a.getAttribute("interfaceRef"), bi=b.getAttribute("interfaceRef");
                    if (("physical".equals(interfaces.get(ai).getAttribute("category")) || "physical".equals(interfaces.get(bi).getAttribute("category"))) && !ai.equals(bi))
                        throw invalid("物理连接接口不匹配："+edge.getAttribute("id"));
                }
                if (edge.hasAttribute("mappingRef")) {
                    var mapping = mapping(edge.getAttribute("mappingRef"));
                    if (!mapping.resource.equals(path.getAttribute("resourceRef"))) throw invalid("连接映射与路径资源不一致："+edge.getAttribute("id"));
                    if (mapping.members.stream().noneMatch(m -> m.instanceSet.equals(source.ref)||m.instanceSet.equals(target.ref)))
                        throw invalid("连接映射未包含任一连接端点："+edge.getAttribute("id"));
                }
                connections++;
            }
        }
        for (var e : entries(root,"structuralConstraints")) {
            if ("instanceCount".equals(e.getLocalName()) && instanceSet(e.getAttribute("instanceSetRef")).quantity != Integer.parseInt(e.getAttribute("quantity")))
                throw invalid("实例数量约束不满足："+e.getAttribute("id"));
            if ("mappingCoverage".equals(e.getLocalName())) mapping(e.getAttribute("mappingRef"));
        }
        dependencies = CapabilityDependencies.parse(root,this);
    }
    private void parseMapping(Element e) {
        String id=e.getAttribute("id"), kind=e.getAttribute("kind"), resource=e.getAttribute("resourceRef");
        int quantity=resourceQuantity(resource), size=e.hasAttribute("groupSize")?Integer.parseInt(e.getAttribute("groupSize")):1;
        if ("grouped".equals(kind) != e.hasAttribute("groupSize")) throw invalid("仅 grouped 映射必须声明 groupSize："+id);
        var members = new ArrayList<Member>(); var ids = new HashSet<String>();
        for (var m : entries(e,"members")) {
            var set=instanceSet(m.getAttribute("instanceSetRef"));
            if (!ids.add(set.id)) throw invalid("映射成员重复："+set.id);
            if (!"selection".equals(kind) && (m.hasAttribute("first")||m.hasAttribute("last")||m.hasAttribute("default"))) throw invalid("仅 selection 映射支持选源范围："+id);
            int first=m.hasAttribute("first")?Integer.parseInt(m.getAttribute("first")):1;
            int last=m.hasAttribute("last")?Integer.parseInt(m.getAttribute("last")):quantity;
            boolean defaultSource=Set.of("true","1").contains(m.getAttribute("default"));
            if (first>last||last>quantity) throw invalid("映射范围越界："+id);
            if ("selection".equals(kind) && (set.quantity!=1 || (defaultSource&&(first!=1||last!=quantity)))) throw invalid("选源必须为单实例，默认光源须覆盖整个资源："+id);
            if (!"selection".equals(kind) && (long)set.quantity*size!=quantity) throw invalid("映射不能完整覆盖资源："+id+" / "+set.id);
            members.add(new Member(set.id,first,last,defaultSource));
        }
        if ("selection".equals(kind) && members.stream().filter(Member::defaultSource).count()!=1) throw invalid("选源映射必须有且只有一个默认光源："+id);
        for (var prior : mappings.values()) if (prior.resource.equals(resource)) for (var member : members)
            if (prior.members.stream().anyMatch(p->p.instanceSet.equals(member.instanceSet))) throw invalid("资源中的实例集合映射重复："+member.instanceSet);
        mappings.put(id,new Mapping(id,kind,resource,size,List.copyOf(members)));
    }
    public Endpoint endpoint(Element e, boolean withPort) {
        String kind=null, ref=null;
        for (String candidate : List.of("instanceSetRef","instanceGroupRef","controlNodeRef","externalEndpointRef")) if(e.hasAttribute(candidate)) {
            if(kind!=null)throw invalid("端点只能声明一种对象引用"); kind=candidate;ref=e.getAttribute(candidate);
        }
        if(kind==null)throw invalid("端点缺少对象引用");
        switch(kind) {
            case "instanceSetRef" -> instanceSet(ref);
            case "instanceGroupRef" -> requireRef(groups,ref,"实例组");
            case "controlNodeRef" -> requireRef(controls,ref,"控制节点");
            case "externalEndpointRef" -> requireRef(externals,ref,"外部端点");
        }
        var endpoint=new Endpoint(kind,ref,withPort?required(e,"portRef"):"");
        if(withPort)ports(endpoint);
        return endpoint;
    }
    private List<Element> ports(Endpoint e) {
        List<Element> owners=switch(e.kind) {
            case "instanceSetRef" -> List.of(types.get(instanceSet(e.ref).type));
            case "instanceGroupRef" -> groups.get(e.ref).stream().map(ref->types.get(instanceSet(ref).type)).toList();
            case "controlNodeRef" -> List.of(controls.get(e.ref));
            case "externalEndpointRef" -> List.of(externals.get(e.ref));
            default -> throw invalid("端点种类无效");
        };
        return owners.stream().map(owner->entries(owner,"ports").stream().filter(p->p.getAttribute("id").equals(e.port)).findFirst()
            .orElseThrow(()->invalid("端点引用未定义端口："+e.ref+"/"+e.port+"（"+owner.getAttribute("id")+"）"))).toList();
    }
    public Endpoint observationSource(Element source) {
        var endpoint=endpoint(source,true);
        for(var port:ports(endpoint))if(!Set.of("out","inout").contains(port.getAttribute("direction")))
            throw invalid("观测来源不是输出端口："+endpoint.ref+"/"+endpoint.port);
        return endpoint;
    }
    public List<String> endpointInstanceSets(Endpoint e) { return switch(e.kind) {
        case "instanceSetRef" -> List.of(e.ref);
        case "instanceGroupRef" -> groups.get(e.ref);
        default -> List.of();
    }; }
    public String id(){return root.getAttribute("id");}
    public String label(){return root.getAttribute("label");}
    public int componentTypeCount(){return types.size();}
    public int instanceSetCount(){return instanceSets.size();}
    public int structuralConstraintCount(){return entries(root,"structuralConstraints").size();}
    public int connectionCount(){return connections;}
    public int openIssueCount(){return entries(child(root,"annotations"),"issues").size();}
    public CapabilityDependencies dependencies(){return dependencies;}
    public Collection<InstanceSet> instanceSets(){return Collections.unmodifiableCollection(instanceSets.values());}
    public Collection<Mapping> mappings(){return Collections.unmodifiableCollection(mappings.values());}
    public InstanceSet instanceSet(String id){return requireRef(instanceSets,id,"实例集合");}
    public Mapping mapping(String id){return requireRef(mappings,id,"映射");}
    public int resourceQuantity(String id){return requireRef(resources,id,"资源");}
    public boolean hasType(String id){return types.containsKey(id);}
    public Set<String> componentTypes(){return Collections.unmodifiableSet(types.keySet());}
    public String stateModelHref(String type){return requireRef(types,type,"组件类型").getAttribute("stateModelHref");}
    public Map<String,String> actionRefs(String type){
        Map<String,String> result=new LinkedHashMap<>();
        for(var capability:entries(requireRef(types,type,"组件类型"),"capabilities"))
            if(capability.hasAttribute("actionRef"))result.put(capability.getAttribute("id"),required(capability,"actionRef"));
        return Collections.unmodifiableMap(result);
    }
    public boolean hasExternal(String id){return externals.containsKey(id);}
    public boolean hasSignal(String id){return signals.containsKey(id);}
    public String signalInterface(String id){return requireRef(signals,id,"信号").getAttribute("interfaceRef");}
    public String actionRef(String component,String capability) {
        Element owner=types.getOrDefault(component,controls.get(component));
        return entries(owner,"capabilities").stream().filter(c->capability.equals(c.getAttribute("id"))).map(c->c.getAttribute("actionRef")).findFirst().orElse("");
    }
    public boolean hasCapability(String component,String capability) {
        return entries(types.getOrDefault(component,controls.get(component)),"capabilities").stream().anyMatch(c->capability.equals(c.getAttribute("id")));
    }
    public static SystemDefinitionModel parse(String xml){return new SystemDefinitionModel(cn.cdi8.workbench.xml.ModelXml.parse(xml,"system-definition"));}
    private static Map<String,Element> index(List<Element> elements){var map=new LinkedHashMap<String,Element>();elements.forEach(e->map.put(e.getAttribute("id"),e));return map;}
    @SafeVarargs private static List<Element> concat(Collection<Element>... collections){var list=new ArrayList<Element>();for(var c:collections)list.addAll(c);return list;}
    private static <T> T requireRef(Map<String,T> map,String ref,String kind){T result=map.get(ref);if(result==null)throw invalid(kind+"引用未定义："+ref);return result;}
    private static IllegalArgumentException invalid(String message){return new IllegalArgumentException(message);}
}
