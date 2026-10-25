package cn.cdi8.workbench.facility;

import cn.cdi8.workbench.integration.tango.TangoGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.context.event.EventListener;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

/** Live projection of facility facts. It is intentionally independent from BPMN run state. */
@Component("facilityStates")
public final class FacilityStateRuntime {
    private final ObjectMapper json;
    private final TangoGateway gateway;
    private final FacilityBindingModel binding;
    private final FacilityStateProjectionModel projection;
    private final Document projectionDocument;
    private final Map<String, JsonNode> snapshots = new ConcurrentHashMap<>();
    private long sequence;
    private volatile ObjectNode current;

    public FacilityStateRuntime(ObjectMapper json, TangoGateway gateway) {
        this.json=json; this.gateway=gateway;
        try {
            String bindingXml=resource("system-models/gxlf-facility-binding.xml");
            String projectionXml=resource("system-models/gxlf-facility-state-projection.xml");
            this.binding=FacilityBindingModel.parse(bindingXml);
            this.projection=FacilityStateProjectionModel.parse(projectionXml);
            this.projectionDocument=parse(projectionXml);
        } catch(Exception ex) { throw new IllegalStateException("无法加载设施状态投影模型："+ex.getMessage(),ex); }
        this.current=json.createObjectNode().put("model",projection.id()).put("definition",projection.definitionRef())
            .put("binding",binding.id()).put("integrity","unknown").put("connected",false)
            .put("sequence",0).put("reason","尚未取得 Tango 设施观测").put("changedAt",System.currentTimeMillis());
        current.putObject("facts"); current.putObject("guards"); current.putObject("observations");
    }

    public synchronized ObjectNode current() { return current.deepCopy(); }

    @EventListener
    public synchronized void observe(TangoGateway.Notice notice) {
        JsonNode event=notice.payload();
        sequence++;
        if("connection_lost".equals(event.path("type").asText())) {
            current.put("connected",false).put("integrity","unknown").put("reason",event.path("reason").asText("Tango 事件通道断开"));
            current.put("sequence",sequence).put("changedAt",System.currentTimeMillis());
            evaluate();
            return;
        }
        if(event.path("connected").asBoolean(false)&&event.has("device_id")&&event.has("snapshot"))
            snapshots.put(event.path("device_id").asText(),event.path("snapshot").deepCopy());
        if("ready".equals(event.path("type").asText()))current.put("connected",true);
        evaluate();
    }

    @Scheduled(fixedDelay=2000)
    public synchronized void refresh() {
        if(!gateway.connected()) {
            current.put("connected",false).put("integrity","unknown").put("reason","Tango 连接不可用");
            current.put("sequence",++sequence).put("changedAt",System.currentTimeMillis());
            evaluate(); return;
        }
        try {
            JsonNode catalog=gateway.catalog();
            snapshots.clear();
            if(catalog.isArray())for(JsonNode item:catalog) {
                String id=item.path("id").asText(); JsonNode snapshot=item.path("snapshot");
                if(!id.isBlank()&&snapshot.isObject())snapshots.put(id,snapshot.deepCopy());
            }
            current.put("connected",true).put("reason","");
        } catch(Exception ex) {
            current.put("connected",false).put("integrity","unknown").put("reason","读取设施目录失败："+ex.getMessage());
        }
        current.put("sequence",++sequence).put("changedAt",System.currentTimeMillis());
        evaluate();
    }

    private void evaluate() {
        ObjectNode facts=json.createObjectNode();
        Map<String,Status> statuses=new LinkedHashMap<>();
        Element factsElement=child(projectionDocument.getDocumentElement(),"facts");
        if(factsElement!=null)for(Element fact:children(factsElement,"fact")) {
            Status result=evaluateFact(fact,statuses);
            statuses.put(fact.getAttribute("id"),result);
            ObjectNode item=facts.putObject(fact.getAttribute("id"));
            item.put("status",result.status).put("reason",result.reason);
        }
        ObjectNode guards=json.createObjectNode();
        Element guardsElement=child(projectionDocument.getDocumentElement(),"guards");
        if(guardsElement!=null)for(Element guard:children(guardsElement,"guard")) {
            Status result=Status.ok();
            for(Element req:children(guard,"require")) {
                Status dependency=statuses.get(req.getAttribute("fact"));
                if(dependency==null)dependency=Status.unknown("事实未计算："+req.getAttribute("fact"));
                result=combine(result,dependency);
            }
            ObjectNode item=guards.putObject(guard.getAttribute("id"));item.put("status",result.status).put("reason",result.reason);
        }
        String integrity="valid", reason="";
        for(Status result:statuses.values()) {
            if("unknown".equals(result.status)){integrity="unknown";reason=result.reason;break;}
            if("violated".equals(result.status)){integrity="violated";reason=result.reason;}
        }
        current.set("facts",facts);current.set("guards",guards);
        current.put("integrity",integrity).put("reason",reason).put("connected",gateway.connected());
        ObjectNode observations=(ObjectNode)current.with("observations");observations.removeAll();
        for(var entry:snapshots.entrySet())observations.putObject(entry.getKey()).put("observed",true).put("observedAt",System.currentTimeMillis());
    }

    private Status evaluateFact(Element fact,Map<String,Status> statuses) {
        String observation=fact.getAttribute("observation");
        if(!observation.isBlank())return observationStatus(observation,fact.getAttribute("value"));
        Status result=Status.ok();
        List<Element> requirements=children(fact,"require");
        if(requirements.isEmpty())return Status.unknown("事实没有 require："+fact.getAttribute("id"));
        for(Element req:requirements) {
            String nested=req.getAttribute("fact");
            Status item;
            if(!nested.isBlank())item=statuses.getOrDefault(nested,Status.unknown("事实未计算："+nested));
            else item=deviceRequirement(req);
            result=combine(result,item);
        }
        return result;
    }

    private Status deviceRequirement(Element req) {
        Set<String> components=words(req.getAttribute("instanceSet"));
        if(components.isEmpty())return Status.violated("设备事实缺少 instanceSet");
        String states=req.getAttribute("states"), outerStates=req.getAttribute("outerStates");
        List<Map.Entry<String,JsonNode>> selected=new ArrayList<>();
        for(var entry:snapshots.entrySet()) {
            JsonNode snapshot=entry.getValue();
            String component=componentOf(entry.getKey());
            if(components.contains(component))selected.add(entry);
        }
        if(selected.isEmpty())return Status.violated("没有匹配的设施设备："+req.getAttribute("instanceSet"));
        for(var entry:selected) {
            JsonNode snapshot=entry.getValue();
            if(!matches(snapshot,states,outerStates))return Status.violated("设备状态不满足："+entry.getKey()+" / "+snapshot.path("state_id").asText());
        }
        return Status.ok();
    }

    private Status observationStatus(String id,String expected) {
        if("serviceHealth".equals(id)) {
            if(!gateway.connected())return Status.unknown("Tango 连接不可用");
            if(snapshots.isEmpty())return Status.unknown("尚未取得设备观测");
            return Status.ok();
        }
        // External safety endpoints are deliberately unknown until their runtime bindings
        // produce an observation; no default value can establish a safety guard.
        return Status.unknown("外部观测尚未绑定："+id);
    }

    private String componentOf(String id) {
        int slash=id.indexOf('/'); if(slash>=0)id=id.substring(0,slash);
        for(String component:List.of("seed","shg","preamp","multipass","pump","measurement","frequency-conversion","target-alignment","vacuum","diagnostics","synchronization","switch-driver","cooling","control-environment"))
            if(id.startsWith(component))return component;
        return id;
    }
    private boolean matches(JsonNode snapshot,String states,String outerStates) {
        return words(states).contains(snapshot.path("state_id").asText())||words(outerStates).contains(snapshot.path("outer_state").asText());
    }
    private static Set<String> words(String value){if(value==null||value.isBlank())return Set.of();return new LinkedHashSet<>(Arrays.asList(value.trim().split("\\s+")));}
    private static Status combine(Status left,Status right){if("violated".equals(left.status)||"violated".equals(right.status))return "violated".equals(left.status)?left:right;if("unknown".equals(left.status)||"unknown".equals(right.status))return "unknown".equals(left.status)?left:right;return Status.ok();}
    private record Status(String status,String reason){static Status ok(){return new Status("valid","");}static Status violated(String reason){return new Status("violated",reason);}static Status unknown(String reason){return new Status("unknown",reason);}}

    private static String resource(String path)throws Exception{var resource=new ClassPathResource(path);try(var input=resource.getInputStream()){return new String(input.readAllBytes(),StandardCharsets.UTF_8);}}
    private static Document parse(String xml)throws Exception{var f=DocumentBuilderFactory.newInstance();f.setNamespaceAware(true);f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);f.setFeature("http://xml.org/sax/features/external-general-entities",false);f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);return f.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));}
    private static Element child(Element parent,String name){for(Element child:children(parent))if(name.equals(child.getLocalName()))return child;return null;}
    private static List<Element> children(Element parent){var result=new ArrayList<Element>();for(var node=parent.getFirstChild();node!=null;node=node.getNextSibling())if(node instanceof Element child)result.add(child);return result;}
    private static List<Element> children(Element parent,String name){var result=new ArrayList<Element>();for(Element child:children(parent))if(name.equals(child.getLocalName()))result.add(child);return result;}
}
