package cn.cdi8.workbench.facility;

import cn.cdi8.model.ComponentContract;
import cn.cdi8.workbench.systemdefinition.SystemDefinitionModel;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.springframework.core.io.ClassPathResource;

/** Actual SCXML contracts linked to a facility type, independent of runtime observations. */
public final class FacilityComponentModels {
    private static final Map<String,String> BUILTINS=new ConcurrentHashMap<>();
    private final Map<String,String> xml;
    private final Map<String,ComponentContract> models=new LinkedHashMap<>();
    private FacilityComponentModels(SystemDefinitionModel definition,Map<String,String> xml) {
        this.xml=Map.copyOf(xml);
        for(String type:definition.componentTypes()) {
            if(!xml.containsKey(type))throw new IllegalArgumentException("缺少组件 SCXML："+type);
            ComponentContract model=new ComponentContract(xml.get(type));models.put(type,model);
            for(var action:definition.actionRefs(type).entrySet())
                if(!model.hasAction(action.getValue()))throw new IllegalArgumentException("系统能力引用未知 SCXML 动作："+type+"."+action.getKey()+" / "+action.getValue());
        }
    }
    public static String modelId(String href) {
        if(href==null||!href.matches("components/[A-Za-z0-9_-]{1,128}\\.scxml"))
            throw new IllegalArgumentException("stateModelHref 必须是组件模型库路径 components/<模型编号>.scxml："+href);
        return href.substring("components/".length(),href.length()-".scxml".length());
    }
    public static FacilityComponentModels resolve(SystemDefinitionModel definition,Function<String,String> resolver) {
        Map<String,String> xml=new LinkedHashMap<>();
        for(String type:definition.componentTypes()) {
            String href=definition.stateModelHref(type);modelId(href);
            xml.put(type,resolver.apply(href));
        }
        return new FacilityComponentModels(definition,xml);
    }
    public static FacilityComponentModels bundled(SystemDefinitionModel definition) {
        return resolve(definition,href->BUILTINS.computeIfAbsent(href,key->{
            try(var input=new ClassPathResource("component-models/"+modelId(key)+".scxml").getInputStream()) {
                return new String(input.readAllBytes(),StandardCharsets.UTF_8);
            }catch(Exception ex){throw new IllegalArgumentException("stateModelHref 无法解析："+key,ex);}
        }));
    }
    public FacilityComponentModels withSelected(SystemDefinitionModel definition,Map<String,String> selected) {
        Map<String,String> merged=new LinkedHashMap<>(xml);
        for(var entry:selected.entrySet()) {
            ComponentContract current=model(entry.getKey()),actual=new ComponentContract(entry.getValue());
            if(!current.metadata().path("model_id").equals(actual.metadata().path("model_id")))
                throw new IllegalArgumentException("实际设备模型与 stateModelHref 的模型身份不一致："+entry.getKey());
            merged.put(entry.getKey(),entry.getValue());
        }
        return new FacilityComponentModels(definition,merged);
    }
    public static FacilityComponentModels archived(SystemDefinitionModel definition,Map<String,String> xml){return new FacilityComponentModels(definition,xml);}
    public Map<String,String> xml(){return xml;}
    public ComponentContract model(String type){
        var model=models.get(type);if(model==null)throw new IllegalArgumentException("未定义组件 SCXML："+type);return model;
    }
    public void validateStates(String type,String states,String outers,String location) {
        var model=model(type);
        for(String id:cn.cdi8.workbench.xml.ModelXml.words(states))if(!model.hasLeaf(id))throw new IllegalArgumentException(location+" 引用未知叶子状态："+type+" / "+id);
        for(String id:cn.cdi8.workbench.xml.ModelXml.words(outers))if(!model.hasOuter(id))throw new IllegalArgumentException(location+" 引用未知外层状态："+type+" / "+id);
    }
}
