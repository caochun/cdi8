package cn.cdi8.workbench.run;

import cn.cdi8.workbench.environment.EnvironmentService;
import cn.cdi8.workbench.integration.tango.TangoGateway;
import cn.cdi8.workbench.model.ModelStore;
import cn.cdi8.workbench.model.ModelValidation;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.flowable.engine.RepositoryService;
import org.springframework.stereotype.Service;
import org.w3c.dom.*;

/** Pins a complete process release to immutable models and an applied environment. */
@Service
public class RunDeploymentService {
    private final ModelStore store;
    private final ModelValidation validation;
    private final TangoGateway tango;
    private final RepositoryService repository;
    private final WorkflowService workflows;
    private final ObjectMapper json;
    private final EnvironmentService environments;
    private final RunPolicies runPolicies;

    public RunDeploymentService(ModelStore store, ModelValidation validation, TangoGateway tango,
                                RepositoryService repository, WorkflowService workflows,
                                ObjectMapper json, EnvironmentService environments, RunPolicies runPolicies) {
        this.store = store;
        this.validation = validation;
        this.tango = tango;
        this.repository = repository;
        this.workflows = workflows;
        this.json = json;
        this.environments = environments;
        this.runPolicies = runPolicies;
    }

    private Document document(String xml) throws Exception {
        var factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        return factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    public Map<String,Object> start(JsonNode config) {
        synchronized (environments) {
            return startLocked(config);
        }
    }

    private Map<String,Object> startLocked(JsonNode config) {
        environments.ensureIdle();
        JsonNode environment=tango.request("GET","/lab/environment",null);
        if(!"ready".equals(environment.path("status").asText()))
            throw new IllegalStateException(environment.path("error").asText("请先创建就绪的模拟环境"));
        if(!environment.path("id").asText().equals(config.path("environment").path("id").asText()))
            throw new IllegalStateException("请先应用当前配置，创建模拟环境");
        JsonNode applied=environments.config();
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
            Map<String,String> systemModels=new LinkedHashMap<>();
            for(String processId:needed) {
                var refs=documents.get(processId).getElementsByTagNameNS(cn.cdi8.workbench.systemstate.SystemStateModel.NS,"systemModel");
                for(int n=0;n<refs.getLength();n++) {
                    String id=((Element)refs.item(n)).getAttribute("ref");
                    if(id.isBlank())throw new IllegalArgumentException("systemModel 缺少 ref");
                    JsonNode record=store.get(id);
                    if(!"system".equals(record.path("kind").asText()))throw new IllegalArgumentException("引用的不是系统状态模型："+id);
                    JsonNode revision=record.path("revisions").isEmpty()?null:record.path("revisions").get(record.path("revisions").size()-1);
                    if(revision==null)throw new IllegalArgumentException("系统状态模型尚未发布："+id);
                    String xml=revision.path("xml").asText();
                    if(!cn.cdi8.workbench.systemstate.SystemStateModel.parse(xml).id().equals(id))
                        throw new IllegalArgumentException("系统状态模型 id 与模型库 id 不一致："+id);
                    systemModels.put(id,xml);
                }
            }
            for(var model:systemModels.entrySet())deployment.addString("system-model-"+model.getKey()+".xml",model.getValue());
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
            runPolicies.find(entryKey).ifPresent(policy -> policy.defaults(variables).forEach(variables::putIfAbsent));
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
            var refs=documents.get(id).getElementsByTagNameNS(cn.cdi8.workbench.systemstate.SystemStateModel.NS,"systemModel");
            for(int r=0;r<refs.getLength();r++) {
                String modelId=((Element)refs.item(r)).getAttribute("ref");
                var record=store.get(modelId);var revision=record.path("revisions").get(record.path("revisions").size()-1);
                var declared=cn.cdi8.workbench.systemstate.SystemStateModel.parse(revision.path("xml").asText());
                var predicates=new ArrayList<cn.cdi8.workbench.systemstate.SystemStateModel.Predicate>();
                declared.states().forEach(state->predicates.addAll(state.allPredicates()));
                declared.facts().forEach(fact->predicates.addAll(fact.predicates()));
                for(var predicate:predicates)for(String component:predicate.components()) {
                    JsonNode componentModel=metadata.get(component);
                    if(componentModel==null)throw new IllegalArgumentException("系统状态谓词缺少组件模型："+component);
                    for(String key:predicate.states())if(!componentModel.path("inner_labels").has(key))throw new IllegalArgumentException("系统状态引用不存在的叶状态："+key);
                    for(String key:predicate.outerStates())if(!componentModel.path("outer_labels").has(key))throw new IllegalArgumentException("系统状态引用不存在的外层状态："+key);
                }
            }
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
