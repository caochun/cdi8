package cn.cdi8.control.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.function.Function;
import static cn.cdi8.control.model.Conditions.require;

/** Detached model bundle. Rules used during a run are restored from its database snapshot. */
public final class ControlModel {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final JsonNode bundle;
    public ControlModel(JsonNode bundle) { this.bundle=bundle.deepCopy(); }
    public JsonNode json() { return bundle.deepCopy(); }
    public JsonNode actions() { return bundle.path("actions"); }
    public JsonNode workflow() { return bundle.path("workflow"); }
    public JsonNode catalog() { return bundle.path("catalog"); }
    public String processId() { return workflow().path("process_id").asText(); }
    public JsonNode device(String id) { require(catalog().path("devices").has(id),"unknown device: "+id); return catalog().get("devices").get(id); }
    public List<String> group(String id) {
        require(catalog().path("groups").has(id),"unknown group: "+id);
        List<String> result=new ArrayList<>();catalog().get("groups").get(id).forEach(n->result.add(n.asText()));return List.copyOf(result);
    }
    public JsonNode node(String id) {require(workflow().path("nodes").has(id),"unknown node: "+id);return workflow().get("nodes").get(id);}
    public String contractRef(String node,String device) {
        var binding=node(node);require(group(binding.path("group").asText()).contains(device),"device not selected by node: "+node+"/"+device);
        String type=device(device).path("system_type").asText();
        require(binding.path("contracts").has(type),"missing contract for type: "+type);
        return binding.get("contracts").get(type).asText();
    }
    public JsonNode contract(String ref) {require(actions().path("contracts").has(ref),"unknown contract: "+ref);return actions().get("contracts").get(ref);}

    public Map<String,Object> parameters(String node,String device, JsonNode overrides) {
        return parameters(node,device,overrides,true);
    }
    private Map<String,Object> parameters(String node,String device,JsonNode overrides,boolean requireInputs) {
        var binding=node(node);JsonNode supplied=overrides.path(node);
        if(supplied.isMissingNode())supplied=JSON.createObjectNode();
        require(supplied.isObject(),"parameters must be an object: "+node);
        ObjectNode effective=(ObjectNode)binding.path("parameters").deepCopy();
        supplied.fields().forEachRemaining(e->effective.set(e.getKey(),e.getValue()));
        var schema=contract(contractRef(node,device)).path("parameters");
        effective.fields().forEachRemaining(e->{require(schema.has(e.getKey()),"unknown parameter: "+node+"."+e.getKey());Conditions.checkValue(e.getValue(),schema.get(e.getKey()),e.getKey());});
        schema.fields().forEachRemaining(e->{if(requireInputs&&e.getValue().path("required").asBoolean(false))require(effective.has(e.getKey()),"required parameter: "+node+"."+e.getKey());});
        return JSON.convertValue(effective,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
    }
    public void validateParameters(JsonNode overrides) {
        require(overrides.isObject(),"node parameters must be an object");
        overrides.fieldNames().forEachRemaining(this::node);
        workflow().get("nodes").fieldNames().forEachRemaining(n->group(node(n).path("group").asText()).forEach(d->parameters(n,d,overrides)));
    }
    public Map<String,Object> variables() {
        Map<String,Object> values=new HashMap<>();
        workflow().path("variables").fields().forEachRemaining(entry->{
            var rule=entry.getValue();var targets=group(rule.path("group").asText());
            values.put(entry.getKey(),rule.path("mode").asText().equals("single")?targets.get(0):new ArrayList<>(targets));
        });return values;
    }
    public boolean condition(String id, Function<String,Map<String,Object>> states) {
        require(workflow().path("conditions").has(id),"unknown workflow condition: "+id);
        return eval(workflow().get("conditions").get(id),states);
    }
    private boolean eval(JsonNode rule,Function<String,Map<String,Object>> states) {
        if(rule.has("all")){for(var child:rule.get("all"))if(!eval(child,states))return false;return true;}
        if(rule.has("any")){for(var child:rule.get("any"))if(eval(child,states))return true;return false;}
        boolean all=rule.path("quantifier").asText().equals("all");
        for(String target:group(rule.get("group").asText())) {
            boolean match=Conditions.matches(rule.get("condition"),states.apply(target));
            if(all&&!match)return false;if(!all&&match)return true;
        }
        return all;
    }
    public void validate() {
        keys(bundle,"actions","catalog","workflow","run_parameters");
        keys(actions(),"version","state_fields","initial_states","contracts");
        keys(catalog(),"version","devices","groups");
        keys(workflow(),"version","process_id","variables","nodes","conditions");
        for(String section:List.of("actions","catalog","workflow"))require(bundle.path(section).path("version").asInt()==1,"unsupported version: "+section);
        require(processId().matches("[A-Za-z0-9_]+"),"invalid process id");
        var fields=actions().path("state_fields");require(fields.isObject()&&!fields.isEmpty(),"missing state fields");
        fields.fields().forEachRemaining(e->validateSchema(e.getValue(),false));
        require(actions().path("contracts").isObject()&&!actions().get("contracts").isEmpty(),"missing contracts");
        actions().get("contracts").fields().forEachRemaining(e->{
            var c=e.getValue();require(c.path("system_type").isTextual()&&c.path("action").isTextual(),"contract needs system_type and action: "+e.getKey());
            keys(c,"system_type","action","source_row","parameters","precondition","completion");
            require(actions().path("initial_states").has(c.get("system_type").asText()),"missing type initial state");
            Conditions.validate(c.path("precondition"),fields);Conditions.validate(c.path("completion"),fields);
            require(c.path("parameters").isObject(),"missing parameter schema");
            c.get("parameters").fields().forEachRemaining(p->{
                validateSchema(p.getValue(),true);
            });
        });
        require(catalog().path("devices").isObject()&&!catalog().get("devices").isEmpty(),"missing devices");
        Set<String> addresses=new HashSet<>();
        catalog().get("devices").fields().forEachRemaining(e->{
            keys(e.getValue(),"system_type","adapter_id");
            String address=e.getValue().path("adapter_id").asText();
            require(address.matches("[A-Za-z0-9_-]+")&&addresses.add(address),"duplicate/invalid adapter_id");
            require(actions().path("initial_states").has(e.getValue().path("system_type").asText()),"unknown device type");
        });
        require(catalog().path("groups").isObject()&&!catalog().get("groups").isEmpty(),"missing groups");
        catalog().get("groups").fields().forEachRemaining(e->{
            var list=e.getValue();require(list.isArray()&&!list.isEmpty(),"empty group: "+e.getKey());Set<String> seen=new HashSet<>();
            list.forEach(d->{require(d.isTextual()&&seen.add(d.asText()),"duplicate/invalid instance");device(d.asText());});
        });
        workflow().path("variables").fields().forEachRemaining(e->{var v=e.getValue();keys(v,"group","mode");var g=group(v.path("group").asText());require(Set.of("list","single").contains(v.path("mode").asText()),"invalid variable mode");if(v.path("mode").asText().equals("single"))require(g.size()==1,"single variable needs exactly one instance");});
        workflow().path("conditions").fields().forEachRemaining(e->validateGroupRule(e.getValue(),fields));
        require(workflow().path("nodes").isObject()&&!workflow().get("nodes").isEmpty(),"missing bindings");
        workflow().get("nodes").fields().forEachRemaining(e->{
            var n=e.getValue();require(n.path("parameters").isObject(),"missing node parameters");
            keys(n,"activity_id","group","contracts","parameters","guard");
            if(n.has("guard"))require(workflow().path("conditions").has(n.get("guard").asText()),"unknown guard: "+n.get("guard"));
            Set<String> types=new HashSet<>();
            for(String d:group(n.path("group").asText())) {
                String type=device(d).get("system_type").asText();types.add(type);
                require(contract(contractRef(e.getKey(),d)).path("system_type").asText().equals(type),"contract/device type mismatch");
            }
            require(types.size()==n.path("contracts").size(),"extra or missing type bindings");
        });
        actions().path("initial_states").fields().forEachRemaining(e->{require(e.getValue().isObject(),"initial state must be object");e.getValue().fields().forEachRemaining(v->{require(fields.has(v.getKey()),"unknown initial state field");Conditions.checkValue(v.getValue(),fields.get(v.getKey()),v.getKey());});});
        workflow().get("nodes").fieldNames().forEachRemaining(n->group(node(n).path("group").asText()).forEach(d->parameters(n,d,JSON.createObjectNode(),false)));
    }
    private static void keys(JsonNode object,String... allowed) {
        require(object.isObject(),"model section must be an object");Set<String> names=Set.of(allowed);
        object.fieldNames().forEachRemaining(name->require(names.contains(name),"unsupported model property: "+name));
    }
    private static void validateSchema(JsonNode schema,boolean parameter) {
        if(parameter)keys(schema,"type","required","nullable","values");
        else keys(schema,"type","nullable","values");
        require(Set.of("string","boolean","integer","number","object").contains(schema.path("type").asText()),"unknown field or parameter type");
        for(String flag:List.of("required","nullable"))
            if(schema.has(flag))require(schema.get(flag).isBoolean(),flag+" must be boolean");
        if(schema.has("values")) {
            require(schema.get("values").isArray()&&!schema.get("values").isEmpty(),"enum values must be a non-empty array");
            ObjectNode typeOnly=schema.deepCopy();typeOnly.remove("values");
            schema.get("values").forEach(v->Conditions.checkValue(v,typeOnly,"enum value"));
        }
    }
    private void validateGroupRule(JsonNode r,JsonNode fields) {
        require(r.isObject(),"invalid workflow condition");
        if(r.has("all")||r.has("any")) {
            require(r.size()==1,"invalid compound workflow condition");var list=r.has("all")?r.get("all"):r.get("any");require(list.isArray()&&!list.isEmpty(),"empty workflow condition");list.forEach(c->validateGroupRule(c,fields));
        } else {
            require(r.size()==3&&r.has("condition"),"workflow leaf needs group, quantifier, condition");
            group(r.path("group").asText());require(Set.of("all","any").contains(r.path("quantifier").asText()),"invalid quantifier");Conditions.validate(r.get("condition"),fields);
        }
    }
}
