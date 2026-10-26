package cn.cdi8.model;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.io.StringReader;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import org.xml.sax.InputSource;

/** Shared adapter grammar and static references. SCXML execution remains the engine's responsibility. */
public final class ComponentContract {
    public static final String NS="http://www.w3.org/2005/07/scxml";
    private static final ObjectMapper JSON=new ObjectMapper()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final JsonNode metadata;
    private final Map<String,Element> states=new LinkedHashMap<>();
    private final Map<String,String> outer=new LinkedHashMap<>();
    private final Set<String> leaves=new LinkedHashSet<>(), outers=new LinkedHashSet<>();

    public ComponentContract(String xml) {
        Element root=document(xml).getDocumentElement();
        if(!is(root,"scxml") || !"1.0".equals(root.getAttribute("version")))throw invalid("要求 SCXML 1.0 根元素");
        var nodes=root.getElementsByTagNameNS(NS,"*");
        Set<String> ids=new HashSet<>();Element data=null;
        for(int i=0;i<nodes.getLength();i++) {
            Element e=(Element)nodes.item(i);
            if(e.hasAttribute("id") && !ids.add(e.getAttribute("id")))throw invalid("重复 id："+e.getAttribute("id"));
            if(is(e,"parallel"))throw invalid("组件快照协议要求单一叶子状态，不支持 parallel");
            if(is(e,"state") || is(e,"final")) {
                String id=e.getAttribute("id");if(id.isBlank())throw invalid("组件状态必须声明 id");
                states.put(id,e);Element top=e;
                while(top.getParentNode() instanceof Element parent && is(parent,"state"))top=parent;
                outer.put(id,top.getAttribute("id"));outers.add(top.getAttribute("id"));
                if(children(e).stream().noneMatch(c->is(c,"state")||is(c,"final")))leaves.add(id);
            }
            if(is(e,"data") && "componentMetadata".equals(e.getAttribute("id"))) {
                if(data!=null || !(e.getParentNode() instanceof Element parent) || !is(parent,"datamodel") || parent.getParentNode()!=root
                    || e.hasAttribute("src") || e.hasAttribute("expr") || !children(e).isEmpty())
                    throw invalid("componentMetadata 必须唯一且在根 datamodel 内以 JSON 文本声明");
                data=e;
            }
        }
        if(data==null)throw invalid("缺少 componentMetadata JSON");
        try{metadata=JSON.readTree(data.getTextContent());}
        catch(Exception ex){throw invalid("componentMetadata JSON 无效："+ex.getMessage());}
        validate();
    }
    public JsonNode metadata(){return metadata.deepCopy();}
    public static String digest(String xml) {
        try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
    }
    public boolean hasAction(String id){return metadata.path("actions").has(id);}
    public boolean hasLeaf(String id){return leaves.contains(id);}
    public boolean hasOuter(String id){return outers.contains(id);}
    public String outer(String state){if(!outer.containsKey(state))throw invalid("模型中没有状态："+state);return outer.get(state);}

    private void validate() {
        fields(metadata,Set.of("model_id","model_version","display_name","execution_model","configuration_valid_states",
            "outer_labels","inner_labels","context_defaults","actions","confirmations","fault_codes","annotations"),"componentMetadata");
        string(metadata.path("model_id"),"model_id");string(metadata.path("display_name"),"display_name");
        if(!metadata.path("model_version").isIntegralNumber() || !metadata.path("model_version").canConvertToInt() || metadata.path("model_version").asInt()<1)
            throw invalid("model_version 必须为正整数");
        if(metadata.has("execution_model") && !"hierarchical-single-active-state".equals(metadata.path("execution_model").asText()))
            throw invalid("execution_model 必须为 hierarchical-single-active-state");
        object(metadata.path("context_defaults"),"context_defaults");
        if(metadata.has("annotations"))object(metadata.path("annotations"),"annotations");
        labelMap(metadata.path("outer_labels"),outers,"outer_labels");labelMap(metadata.path("inner_labels"),leaves,"inner_labels");
        if(metadata.has("configuration_valid_states"))refs(metadata.get("configuration_valid_states"),leaves,"configuration_valid_states");
        var actions=metadata.path("actions");object(actions,"actions");if(actions.isEmpty())throw invalid("actions 不能为空");
        actions.fields().forEachRemaining(entry->{
            String path="actions."+entry.getKey();JsonNode action=entry.getValue();
            if(!entry.getKey().matches("[A-Za-z0-9_-]{1,128}"))throw invalid("无效动作名："+entry.getKey());
            fields(action,Set.of("label","request_event","success_events","failure_events","success_key","failure_key","success_observation","failure_observation"),path);
            string(action.path("label"),path+".label");
            String request=event(action.path("request_event"),path+".request_event");
            if(leaves.stream().noneMatch(s->accepts(s,request)))throw invalid(path+".request_event 未在 SCXML 迁移中声明："+request);
            for(String outcome:List.of("success","failure")) {
                string(action.path(outcome+"_key"),path+"."+outcome+"_key");
                var feedback=action.path(outcome+"_events");object(feedback,path+"."+outcome+"_events");
                if(feedback.isEmpty())throw invalid(path+"."+outcome+"_events 不能为空");
                feedback.fields().forEachRemaining(f->{
                    requireRef(leaves,f.getKey(),path+"."+outcome+"_events");
                    if(!f.getValue().isNull()) {
                        String evt=event(f.getValue(),path+"."+outcome+"_events."+f.getKey());
                        // A request can change the leaf before feedback (including eventless transitions).
                        // Check references here; actual enablement is owned by the SCXML engine.
                        if(leaves.stream().noneMatch(s->accepts(s,evt)))throw invalid(path+" 的反馈事件未在 SCXML 中声明："+evt);
                    }
                });
                if(action.has(outcome+"_observation")) {
                    var observation=action.get(outcome+"_observation");
                    if(observation.isObject())observation.fields().forEachRemaining(f->{requireRef(leaves,f.getKey(),path);nullableString(f.getValue(),path+"."+outcome+"_observation");});
                    else nullableString(observation,path+"."+outcome+"_observation");
                }
            }
        });
        var confirmations=metadata.path("confirmations");
        if(metadata.has("confirmations")) {
            object(confirmations,"confirmations");
            confirmations.fields().forEachRemaining(entry->{
                String path="confirmations."+entry.getKey();var c=entry.getValue();
                fields(c,Set.of("label","source_outer","source_states","machine_event","observation"),path);
                string(c.path("label"),path+".label");string(c.path("observation"),path+".observation");
                Set<String> source=refs(c.path("source_outer"),outers,path+".source_outer");
                if(source.isEmpty())throw invalid(path+".source_outer 不能为空");
                String evt=event(c.path("machine_event"),path+".machine_event");
                Set<String> candidates=new HashSet<>();for(String leaf:leaves)if(source.contains(outer(leaf)))candidates.add(leaf);
                if(c.has("source_states"))for(String state:refs(c.get("source_states"),leaves,path+".source_states")) {
                    if(!candidates.contains(state))throw invalid(path+".source_states 不属于 source_outer："+state);
                    if(!accepts(state,evt))throw invalid(path+".machine_event 在 source_states 上未声明："+state+" / "+evt);
                }
                if(candidates.stream().noneMatch(s->accepts(s,evt)))throw invalid(path+".machine_event 未在来源状态的 SCXML 迁移中声明："+evt);
            });
        }
        if(metadata.has("fault_codes")) {
            object(metadata.get("fault_codes"),"fault_codes");
            metadata.get("fault_codes").fields().forEachRemaining(e->{
                if(!confirmations.has(e.getKey()))throw invalid("fault_codes 引用未知确认事件："+e.getKey());string(e.getValue(),"fault_codes."+e.getKey());
            });
        }
    }
    /** Static event matching only: conditions and transition priority are evaluated by SCXML at runtime. */
    private boolean accepts(String state,String event) {
        for(Node node=states.get(state);node instanceof Element e;node=node.getParentNode())
            for(Element transition:children(e))if(is(transition,"transition"))
                for(String descriptor:transition.getAttribute("event").trim().split("\\s+")) {
                    String prefix=descriptor.endsWith(".*")?descriptor.substring(0,descriptor.length()-2):descriptor;
                    if("*".equals(descriptor) || !prefix.isEmpty() && (event.equals(prefix)||event.startsWith(prefix+".")))return true;
                }
        return false;
    }
    private static void fields(JsonNode value,Set<String> allowed,String path){object(value,path);value.fieldNames().forEachRemaining(k->{if(!allowed.contains(k))throw invalid(path+" 不支持字段："+k);});}
    private static void object(JsonNode value,String path){if(value==null||!value.isObject())throw invalid(path+" 必须为对象");}
    private static String string(JsonNode value,String path){if(value==null||!value.isTextual()||value.asText().isBlank())throw invalid(path+" 必须为非空字符串");return value.asText();}
    private static void nullableString(JsonNode value,String path){if(!value.isNull())string(value,path);}
    private static String event(JsonNode value,String path){String event=string(value,path);if(event.matches(".*[\\s*].*"))throw invalid(path+" 必须为单个具体事件名");return event;}
    private static void requireRef(Set<String> ids,String id,String path){if(!ids.contains(id))throw invalid(path+" 引用未知状态："+id);}
    private static void labelMap(JsonNode value,Set<String> ids,String path){object(value,path);value.fields().forEachRemaining(e->{requireRef(ids,e.getKey(),path);string(e.getValue(),path+"."+e.getKey());});}
    private static Set<String> refs(JsonNode value,Set<String> ids,String path){
        if(!value.isArray())throw invalid(path+" 必须为状态数组");Set<String> result=new HashSet<>();
        for(var item:value){String id=string(item,path);requireRef(ids,id,path);if(!result.add(id))throw invalid(path+" 重复状态："+id);}return result;
    }
    private static boolean is(Element e,String local){return NS.equals(e.getNamespaceURI())&&local.equals(e.getLocalName());}
    private static List<Element> children(Element e){var list=new ArrayList<Element>();for(Node n=e.getFirstChild();n!=null;n=n.getNextSibling())if(n instanceof Element child)list.add(child);return list;}
    public static Document document(String xml) {
        try {
            if(xml==null||xml.isBlank()||xml.length()>2_000_000)throw invalid("XML 为空或过大");
            var factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        }catch(Exception ex){throw invalid("模型 XML 无效："+ex.getMessage());}
    }
    private static IllegalArgumentException invalid(String message){return new IllegalArgumentException(message);}
}
