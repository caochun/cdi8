package cn.cdi8.workbench.facility;

import java.util.*;
import org.w3c.dom.Element;

/** Finite, explicit expansion of binding templates; never infers a type from a device ID. */
public final class FacilityInstanceTopology {
    public record Instance(String logical,String component,String runtime,String runtimeKey) {}
    private final Map<String,Instance> instances=new LinkedHashMap<>();
    private final Map<Integer,Map<String,String>> roles=new LinkedHashMap<>();
    private final Map<Integer,Set<String>> sources=new LinkedHashMap<>();
    private String defaultSource;
    public int beamlineCount(){return roles.size();}
    public String defaultSource(){return defaultSource;}
    private final Set<String> sourceTypes=new HashSet<>();

    static FacilityInstanceTopology parse(Element root,cn.cdi8.workbench.systemdefinition.SystemDefinitionModel definition) {
        var model=new FacilityInstanceTopology();
        var runtimeIds=new HashSet<String>();
        for(var binding:cn.cdi8.workbench.xml.ModelXml.entries(root,"instanceBindings")) {
            var set=definition.instanceSet(binding.getAttribute("instanceSetRef"));
            for(int index=1;index<=set.quantity();index++) {
                String runtime=binding.getAttribute("runtimePattern").replace("{index}",index(index));
                if(!runtimeIds.add(runtime))throw new IllegalArgumentException("运行设备模板产生重复设备名："+runtime);
                model.instances.put(set.logical(index),new Instance(set.logical(index),set.type(),runtime,binding.getAttribute("runtimeKey")));
            }
        }
        var bindings=cn.cdi8.workbench.xml.ModelXml.entries(root,"beamlineBindings");
        if(bindings.size()!=1)throw new IllegalArgumentException("当前运行时仅支持一个束线资源集合");
        for(var binding:bindings) {
            String resource=binding.getAttribute("resourceRef");
            int quantity=definition.resourceQuantity(resource);
            var mappings=new ArrayList<cn.cdi8.workbench.systemdefinition.SystemDefinitionModel.Mapping>();
            for(var ref:cn.cdi8.workbench.xml.ModelXml.entries(binding,"mappingBindings")) {
                var mapping=definition.mapping(ref.getAttribute("mappingRef"));
                if(!resource.equals(mapping.resource()))throw new IllegalArgumentException("束线绑定引用了其他资源的映射："+mapping.id());
                mappings.add(mapping);
            }
            var expected=definition.mappings().stream().filter(m->resource.equals(m.resource())).map(m->m.id()).collect(java.util.stream.Collectors.toSet());
            if(!expected.equals(mappings.stream().map(m->m.id()).collect(java.util.stream.Collectors.toSet())))throw new IllegalArgumentException("束线绑定必须引用该资源的全部映射");
            if(mappings.stream().filter(m->"selection".equals(m.kind())).count()!=1)throw new IllegalArgumentException("束线资源必须有且只有一个选源映射");
            for(int beam=1;beam<=quantity;beam++) {
                Map<String,String> roles=new LinkedHashMap<>();Set<String> sources=new LinkedHashSet<>();
                for(var mapping:mappings)for(var member:mapping.members()) {
                    var set=definition.instanceSet(member.instanceSet());
                    if("selection".equals(mapping.kind())) {
                        model.sourceTypes.add(set.type());
                        if(member.defaultSource())model.defaultSource=set.type();
                        if(member.includes(beam))sources.add(set.logical(1));
                    }else if(roles.putIfAbsent(set.type(),set.logical((beam-1)/mapping.groupSize()+1))!=null)
                        throw new IllegalArgumentException("束线角色无法唯一解析类型："+set.type());
                }
                model.roles.put(beam,Map.copyOf(roles));model.sources.put(beam,Set.copyOf(sources));
            }
        }
        return model;
    }
    public String logical(String component,int beam,String source) {
        if(!roles.containsKey(beam))throw new IllegalArgumentException("束线未绑定："+beam);
        for(String logical:sources.get(beam))if(instances.get(logical).component.equals(component)) {
            if(!component.equals(source))throw new IllegalArgumentException("该光源不是本发选源："+component);
            return logical;
        }
        if(sourceTypes.contains(component))throw new IllegalArgumentException("该束线不允许所选光源："+component);
        String logical=roles.get(beam).get(component);
        if(logical!=null) {
            if(!instances.get(logical).component.equals(component))throw new IllegalArgumentException("束线角色与实例类型不一致："+component);
            return logical;
        }
        var candidates=instances.values().stream().filter(i->i.component.equals(component)).toList();
        if(candidates.size()==1)return candidates.get(0).logical;
        throw new IllegalArgumentException("无法唯一解析束线实例："+beam+" / "+component);
    }
    /** Runtime IDs come from a declared simulator pattern or an explicit deployment mapping. */
    public Map<String,String> bind(Map<String,? extends Map<String,String>> devices,Map<String,String> explicit) {
        var resolved=new LinkedHashMap<String,String>();
        for(var instance:instances.values()) {
            String runtime=explicit.getOrDefault(instance.logical,instance.runtime);
            if(runtime.isBlank()||!devices.containsKey(runtime)) {
                if(explicit.containsKey(instance.logical))throw new IllegalArgumentException("显式绑定设备不存在："+runtime);
                continue;
            }
            if(!instance.runtimeKey.equals(devices.get(runtime).getOrDefault("runtimeKey",devices.get(runtime).get("component"))))throw new IllegalArgumentException("实例绑定组件类型不匹配："+instance.logical);
            if(resolved.containsValue(runtime))throw new IllegalArgumentException("运行设备被重复绑定："+runtime);
            resolved.put(instance.logical,runtime);
        }
        for(String logical:explicit.keySet())if(!instances.containsKey(logical))throw new IllegalArgumentException("显式绑定逻辑实例不存在："+logical);
        return Map.copyOf(resolved);
    }
    private static String index(int index){return String.format(Locale.ROOT,"%02d",index);}
}
