package cn.cdi8.workbench.facility;

import cn.cdi8.workbench.systemdefinition.SystemDefinitionModel;
import java.util.*;
import org.w3c.dom.Element;
import static cn.cdi8.workbench.xml.ModelXml.*;

/** Deployment addresses reference composition and mapping rules; they never redefine them. */
public final class FacilityBindingModel {
    public static final String NS="https://cdi8.cn/facility-binding/v1";
    private final Element root;
    private final Map<String,String> componentByRuntimeKey=new LinkedHashMap<>();
    private final Map<String,String> instanceSetByRuntimeKey=new LinkedHashMap<>();
    private final FacilityInstanceTopology topology;
    private FacilityBindingModel(Element root,SystemDefinitionModel definition) {
        this.root=root;
        var keys=new HashSet<String>();
        for(var b:entries(root,"instanceBindings")) {
            String key=b.getAttribute("runtimeKey"),set=b.getAttribute("instanceSetRef");
            if(!keys.add(key))throw new IllegalArgumentException("设施运行组件键重复："+key);
            if(!b.getAttribute("runtimePattern").matches("[^{}]+\\{index}"))throw new IllegalArgumentException("运行设备模板必须是前缀加 {index}");
            instanceSetByRuntimeKey.put(key,set);
            if(definition!=null)componentByRuntimeKey.put(key,definition.instanceSet(set).type());
        }
        for(var beam:entries(root,"beamlineBindings")) {
            var refs=new HashSet<String>();
            for(var mapping:entries(beam,"mappingBindings"))if(!refs.add(mapping.getAttribute("mappingRef")))throw new IllegalArgumentException("束线映射引用重复");
        }
        if(definition!=null) {
            if(!definition.id().equals(definitionRef()))throw new IllegalArgumentException("设施绑定 definitionRef 不一致");
            if(!Set.copyOf(instanceSetByRuntimeKey.values()).equals(definition.instanceSets().stream().map(SystemDefinitionModel.InstanceSet::id).collect(java.util.stream.Collectors.toSet())))
                throw new IllegalArgumentException("实例绑定必须完整覆盖系统定义的实例集合");
            var externalKeys=new HashSet<String>();
            for(var b:entries(root,"externalBindings")) {
                if(!definition.hasExternal(b.getAttribute("externalEndpointRef")))throw new IllegalArgumentException("外部绑定引用未定义端点："+b.getAttribute("externalEndpointRef"));
                if(!externalKeys.add(b.getAttribute("runtimeKey")))throw new IllegalArgumentException("外部运行键重复");
            }
            topology=FacilityInstanceTopology.parse(root,definition);
        }else topology=null;
    }
    public String id(){return root.getAttribute("id");}
    public String definitionRef(){return root.getAttribute("definitionRef");}
    public int instanceBindingCount(){return entries(root,"instanceBindings").size();}
    public int beamlineBindingCount(){return entries(root,"beamlineBindings").size();}
    public int externalBindingCount(){return entries(root,"externalBindings").size();}
    public FacilityInstanceTopology topology(){if(topology==null)throw new IllegalStateException("绑定尚未与系统定义进行联合校验");return topology;}
    public String componentForRuntimeKey(String key){return componentByRuntimeKey.getOrDefault(key,"");}
    public String instanceSetForRuntimeKey(String key){return instanceSetByRuntimeKey.getOrDefault(key,"");}
    public boolean knowsRuntimeKey(String key){return instanceSetByRuntimeKey.containsKey(key);}
    public static FacilityBindingModel parse(String xml){return parse(xml,null);}
    public static FacilityBindingModel parse(String xml,SystemDefinitionModel definition){return new FacilityBindingModel(cn.cdi8.workbench.xml.ModelXml.parse(xml,"facility-binding"),definition);}
}
