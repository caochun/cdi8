package cn.cdi8.control;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.flowable.engine.*;
import org.springframework.stereotype.Service;
import org.w3c.dom.*;

/** Coordinates immutable model releases with one replaceable simulation environment. */
@Service
public class WorkbenchService {
    private final ModelStore store;
    private final ModelValidation validation;
    private final TangoGateway tango;
    private final RepositoryService repository;
    private final RuntimeService runtime;
    private final WorkflowService workflows;
    private final ObjectMapper json;
    public WorkbenchService(ModelStore store,ModelValidation validation,TangoGateway tango,RepositoryService repository,
                            RuntimeService runtime,WorkflowService workflows,ObjectMapper json) {
        this.store=store;this.validation=validation;this.tango=tango;this.repository=repository;
        this.runtime=runtime;this.workflows=workflows;this.json=json;
    }
    public JsonNode validate(JsonNode input){return validation.validate(input.path("kind").asText(),input.path("xml").asText());}
    public synchronized JsonNode save(JsonNode input) {
        String id=input.path("id").asText(UUID.randomUUID().toString());
        return store.save(id,input.path("name").asText(),input.path("kind").asText(),input.path("xml").asText());
    }
    public synchronized JsonNode publish(String id) {
        var model=store.get(id);
        return store.addRevision(id,validation.validate(model.path("kind").asText(),model.path("draft").asText()));
    }
    public JsonNode config() {
        try {Path path=store.directory().resolve("configuration.json");return Files.exists(path)?json.readTree(path.toFile()):json.createObjectNode();}
        catch(Exception ex){throw new IllegalStateException(ex);}
    }
    public synchronized JsonNode saveConfig(JsonNode input) {
        try {json.writerWithDefaultPrettyPrinter().writeValue(store.directory().resolve("configuration.json").toFile(),input);return input;}
        catch(Exception ex){throw new IllegalStateException(ex);}
    }
    private void idle(){if(runtime.createProcessInstanceQuery().count()>0)throw new IllegalStateException("请先停止正在运行的流程，再应用模拟环境");}
    public synchronized JsonNode apply(JsonNode config) {
      synchronized(workflows) {
        idle();
        ArrayNode devices=json.createArrayNode();Set<String> ids=new HashSet<>();
        for(JsonNode row:config.path("devices")) {
            String modelId=row.path("model").asText(), revision=row.path("revision").asText();
            var model=store.get(modelId);
            if(!"scxml".equals(model.path("kind").asText()))throw new IllegalArgumentException("设备必须使用 SCXML 模型");
            JsonNode version=store.revision(modelId,revision);
            if(row.path("count").asInt(1)<1)throw new IllegalArgumentException("每组至少需要一个实例");
            for(int i=1;i<=row.path("count").asInt(1);i++) {
                if(i>200)throw new IllegalArgumentException("单组实例数不能超过 200");
                String id=row.path("component").asText()+"_"+String.format("%02d",i);
                if(!ids.add(id))throw new IllegalArgumentException("组件类型重复："+row.path("component").asText());
                devices.addObject().put("id",id).put("component",row.path("component").asText())
                    .put("revision",revision).put("xml",version.path("xml").asText())
                    .put("autoComplete",row.path("autoComplete").asBoolean())
                    .put("delayMs",row.path("delayMs").asInt(500));
            }
        }
        var environment=tango.request("POST","/lab/environment",Map.of("devices",devices,"autoComplete",config.path("autoComplete").asBoolean(),"conditions",config.path("conditions").isObject()?config.path("conditions"):json.createObjectNode()));
        tango.reconnect();
        for(int attempt=0;attempt<100;attempt++) {
            if(tango.connected() && ids.stream().allMatch(id->tango.cached(id)!=null))break;
            try {Thread.sleep(100);}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IllegalStateException("环境切换被中断");}
        }
        if(!tango.connected() || ids.stream().anyMatch(id->tango.cached(id)==null))throw new IllegalStateException("模拟环境已创建，但事件重连未完成，请检查连接后重新应用");
        ObjectNode saved=config.deepCopy();saved.set("environment",environment);saved.set("appliedDevices",config.path("devices").deepCopy());saved.put("appliedAutoComplete",config.path("autoComplete").asBoolean());saved.set("appliedConditions",config.path("conditions").isObject()?config.path("conditions").deepCopy():json.createObjectNode());saveConfig(saved);return saved;
      }
    }
    private Document document(String xml) throws Exception {
        var factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        return factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    public synchronized Map<String,Object> start(JsonNode config) {
        idle();
        JsonNode environment=tango.request("GET","/lab/environment",null);
        if(!environment.path("id").asText().equals(config.path("environment").path("id").asText()))
            throw new IllegalStateException("请先应用当前配置，创建模拟环境");
        JsonNode applied=config();
        if(!applied.path("appliedConditions").equals(config.path("conditions").isObject()?config.path("conditions"):json.createObjectNode())||!applied.path("appliedDevices").equals(config.path("devices"))||applied.path("appliedAutoComplete").asBoolean()!=config.path("autoComplete").asBoolean())throw new IllegalStateException("设备配置已修改，请重新应用模拟环境");
        if(!tango.connected())throw new IllegalStateException("模拟设备事件正在重连，请稍后再启动");
        try {
            Map<String,Document> documents=new LinkedHashMap<>();Map<String,String> versions=new LinkedHashMap<>();
            for(JsonNode selected:config.path("processes")) {
                String model=selected.path("model").asText(),revision=selected.path("revision").asText();
                JsonNode version=store.revision(model,revision);
                JsonNode result=validation.validate("bpmn",version.path("xml").asText());
                if(!result.path("valid").asBoolean())throw new IllegalArgumentException("流程版本校验失败："+model+" "+result.path("diagnostics"));
                documents.put(model,document(version.path("xml").asText()));versions.put(model,revision);
            }
            String entry=config.path("entry").asText();
            if(!documents.containsKey(entry))throw new IllegalArgumentException("请选择入口流程的版本");
            String release=UUID.randomUUID().toString().replace("-","").substring(0,12);
            Map<String,String> pinned=new HashMap<>();Map<String,String> owners=new HashMap<>();
            for(var item:documents.entrySet()) {
                var processes=item.getValue().getElementsByTagNameNS("*","process");
                for(int i=0;i<processes.getLength();i++) {
                    String key=((Element)processes.item(i)).getAttribute("id");
                    if(pinned.putIfAbsent(key,key+"__lab_"+release)!=null)throw new IllegalArgumentException("重复流程 key："+key);
                    owners.put(key,item.getKey());
                }
            }
            // Deploy only the reachable dependency closure and pin every callActivity.
            Set<String> needed=new LinkedHashSet<>();ArrayDeque<String> todo=new ArrayDeque<>();todo.add(entry);
            while(!todo.isEmpty()) {
                String id=todo.remove();if(!needed.add(id))continue;
                var calls=documents.get(id).getElementsByTagNameNS("*","callActivity");
                for(int i=0;i<calls.getLength();i++) {
                    String key=((Element)calls.item(i)).getAttribute("calledElement");
                    if(!owners.containsKey(key))throw new IllegalArgumentException("请在配置中选择子流程版本："+key);
                    todo.add(owners.get(key));
                }
            }
            var deployment=repository.createDeployment().name("workbench-"+release);
            String entryKey=null;
            for(String id:needed) {
                Document doc=documents.get(id);var all=doc.getElementsByTagName("*");
                for(int i=0;i<all.getLength();i++) {
                    Element element=(Element)all.item(i);
                    if("process".equals(element.getLocalName())) {
                        String key=element.getAttribute("id");element.setAttribute("id",pinned.get(key));
                        if(id.equals(entry)&&entryKey==null)entryKey=pinned.get(key);
                    }
                    if("callActivity".equals(element.getLocalName()) && pinned.containsKey(element.getAttribute("calledElement")))
                        element.setAttribute("calledElement",pinned.get(element.getAttribute("calledElement")));
                    // A task may have the same ID as a child process key. Only a plane's
                    // process reference is renamed; shape/edge references still name tasks.
                    if("BPMNPlane".equals(element.getLocalName()) && pinned.containsKey(element.getAttribute("bpmnElement")) && owners.get(element.getAttribute("bpmnElement")).equals(id))
                        element.setAttribute("bpmnElement",pinned.get(element.getAttribute("bpmnElement")));
                }
                var output=new java.io.StringWriter();TransformerFactory.newInstance().newTransformer().transform(new DOMSource(doc),new StreamResult(output));
                deployment.addString(id+".bpmn20.xml",output.toString());
            }
            Map<String,Object> variables=new HashMap<>();
            if(config.path("variables").isObject())variables.putAll(json.convertValue(config.get("variables"),Map.class));
            var catalog=tango.catalog();Map<String,Map<String,String>> devices=new LinkedHashMap<>();
            for(JsonNode device:catalog) {
                String id=device.path("id").asText();
                if(!device.path("connected").asBoolean()||tango.cached(id)==null)throw new IllegalStateException("设备未连接："+id);
                if(device.path("snapshot").hasNonNull("active_command_id"))throw new IllegalStateException("设备仍有动作："+id);
                devices.put(id,Map.of("id",id,"component",device.path("component").asText()));
            }
            variables.put("allComponents",devices.values().stream().filter(d->!d.get("component").equals("context")).toList());
            for(var target:devices.values()) {
                String collection=target.get("component")+"Targets";
                variables.computeIfAbsent(collection,k->new ArrayList<Map<String,String>>());
                ((List<Map<String,String>>)variables.get(collection)).add(target);
            }
            var bindings=config.path("bindings");
            if(bindings.isObject())for(var fields=bindings.fields();fields.hasNext();) {
                var field=fields.next();List<Map<String,String>> targets=new ArrayList<>();
                if(!field.getValue().isArray())throw new IllegalArgumentException("设备集合必须是编号数组："+field.getKey());
                for(JsonNode id:field.getValue()) {
                    if(!devices.containsKey(id.asText()))throw new IllegalArgumentException("绑定设备不存在："+id.asText());
                    targets.add(devices.get(id.asText()));
                }
                variables.put(field.getKey(),targets);
            }
            variables.putIfAbsent("sourceTargets",devices.values().stream().filter(d->"seed".equals(d.get("component"))).toList());
            variables.putIfAbsent("commandTimeout","PT15M");variables.putIfAbsent("workflowTimeout","PT45M");
            variables.put("outcome","RUNNING");variables.put("workbench",true);variables.put("modelVersions",versions);
            variables.put("workbenchConfiguration",json.convertValue(config,Map.class));
            // Validate command contracts against the actual chosen model versions before deployment.
            validateContracts(documents,needed,variables,config);
            var deployed=deployment.deploy();
            var definition=repository.createProcessDefinitionQuery().deploymentId(deployed.getId()).processDefinitionKey(entryKey).singleResult();
            return workflows.startDefinition(definition.getId(),variables);
        }catch(RuntimeException ex){throw ex;}catch(Exception ex){throw new IllegalArgumentException(ex.getMessage(),ex);}
    }
    private void validateContracts(Map<String,Document> documents,Set<String> needed,Map<String,Object> variables,JsonNode config)throws Exception {
        Map<String,JsonNode> metadata=new HashMap<>();
        for(JsonNode row:config.path("devices")) {
            String xml=store.revision(row.path("model").asText(),row.path("revision").asText()).path("xml").asText();
            var data=document(xml).getElementsByTagNameNS("*","data");
            for(int i=0;i<data.getLength();i++)if("componentMetadata".equals(((Element)data.item(i)).getAttribute("id")))
                metadata.put(row.path("component").asText(),json.readTree(data.item(i).getTextContent()));
        }
        for(String id:needed) {
            var tasks=documents.get(id).getElementsByTagNameNS("*","serviceTask");
            for(int i=0;i<tasks.getLength();i++) {
                Element task=(Element)tasks.item(i);var fields=task.getElementsByTagNameNS("*","field");Map<String,String> values=new HashMap<>();
                for(int n=0;n<fields.getLength();n++) {Element f=(Element)fields.item(n);values.put(f.getAttribute("name"),f.hasAttribute("stringValue")?f.getAttribute("stringValue"):f.getTextContent().trim());}
                if(!"true".equals(values.get("componentCommand")) && !"component-command".equals(task.getAttributeNS("http://flowable.org/bpmn","type")))continue;
                var loops=task.getElementsByTagNameNS("*","multiInstanceLoopCharacteristics");
                if(loops.getLength()==0)continue;
                String expression=((Element)loops.item(0)).getAttributeNS("http://flowable.org/bpmn","collection");
                if(!expression.matches("\\$\\{[A-Za-z0-9_]+}"))throw new IllegalArgumentException("组件命令集合必须使用 ${变量名}："+task.getAttribute("id"));
                String collection=expression.substring(2,expression.length()-1);
                if(!(variables.get(collection) instanceof List<?> targets))throw new IllegalArgumentException("设备集合未绑定："+collection);
                for(Object object:targets) {
                    var target=(Map<?,?>)object;String component=target.get("component").toString();
                    if(component.equals("context"))continue;
                    JsonNode model=metadata.get(component);
                    if(model==null)throw new IllegalArgumentException("缺少组件模型："+component);
                    String action=resolve(values.get("action"),component), expected=resolve(values.get("expectedState"),component);
                    if(!model.path("actions").has(action))throw new IllegalArgumentException(task.getAttribute("id")+" 引用了不存在的动作："+action);
                    if(!expected.isBlank())for(String state:expected.split("\\|"))if(!model.path("inner_labels").has(state))
                        throw new IllegalArgumentException(task.getAttribute("id")+" 引用了不存在的状态："+state);
                }
            }
        }
    }
    private String resolve(String value,String component)throws Exception {
        if(value==null)return "";
        try {JsonNode node=json.readTree(value);return node.isObject()?node.path(component).asText():value;}catch(com.fasterxml.jackson.core.JsonProcessingException ex){return value;}
    }
}
