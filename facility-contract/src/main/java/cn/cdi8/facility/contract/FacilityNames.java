package cn.cdi8.facility.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** Reads earlier persisted field names at contract boundaries; new records use explicit names. */
public final class FacilityNames {
    private FacilityNames() {}

    public static void rename(ObjectNode value,String name,String legacy) {
        if(!value.has(legacy))return;
        if(value.has(name)&&!value.get(name).equals(value.get(legacy)))
            throw new IllegalArgumentException("字段冲突："+name+" / "+legacy);
        value.set(name,value.remove(legacy));
    }
    public static String componentType(Map<?,?> target) {
        Object value=target.get("componentType"),legacy=target.get("component");
        if(value!=null&&legacy!=null&&!value.equals(legacy))throw new IllegalArgumentException("组件类型字段冲突");
        return Objects.toString(value!=null?value:legacy,"");
    }
    public static Map<String,Object> target(Map<?,?> source) {
        var value=new LinkedHashMap<String,Object>();source.forEach((key,item)->value.put(key.toString(),item));
        String type=componentType(source);
        value.remove("component");value.put("componentType",type);
        Object old=value.remove("runtimeKey");
        if(old!=null) {
            if(value.containsKey("deviceBindingKey")&&!old.equals(value.get("deviceBindingKey")))
                throw new IllegalArgumentException("设备绑定键字段冲突");
            value.put("deviceBindingKey",old);
        }
        return value;
    }
    public static ObjectNode dependencyPlan(ObjectNode plan) {
        rename(plan,"componentType","component");
        for(JsonNode item:plan.path("requirements"))if(item instanceof ObjectNode requirement) {
            rename(requirement,"componentType","component");
            rename(requirement,"componentInstanceId","logical");
            if(requirement.path("observation") instanceof ObjectNode observation)rename(observation,"componentType","component");
        }
        return plan;
    }
}
