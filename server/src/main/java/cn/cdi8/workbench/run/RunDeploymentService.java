package cn.cdi8.workbench.run;

import cn.cdi8.workbench.environment.EnvironmentService;
import cn.cdi8.workbench.integration.tango.TangoGateway;
import cn.cdi8.workbench.model.ModelStore;
import cn.cdi8.workbench.model.ModelValidation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.flowable.engine.RepositoryService;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Deploys one immutable new-model process release and starts its shot run. */
@Service
public class RunDeploymentService {
    private static final String FACILITY_DEFINITION = "gxlf-facility";
    private static final String FACILITY_BINDING = "gxlf-facility-binding";
    private static final String FACILITY_STATE = "gxlf-facility-state-projection";
    private final cn.cdi8.workbench.facility.FacilityStateRuntime facilityStates;
    private final ModelStore store;
    private final ModelValidation validation;
    private final TangoGateway tango;
    private final RepositoryService repository;
    private final WorkflowService workflows;
    private final ObjectMapper json;
    private final EnvironmentService environments;

    public RunDeploymentService(ModelStore store, ModelValidation validation, TangoGateway tango,
                                RepositoryService repository, WorkflowService workflows,
                                ObjectMapper json, EnvironmentService environments, cn.cdi8.workbench.facility.FacilityStateRuntime facilityStates) {
        this.facilityStates=facilityStates;
        this.store = store;
        this.validation = validation;
        this.tango = tango;
        this.repository = repository;
        this.workflows = workflows;
        this.json = json;
        this.environments = environments;
    }

    private Document document(String xml) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        return factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    public Map<String, Object> start(JsonNode config) {
        synchronized (environments) {
            environments.ensureIdle();
            JsonNode environment = tango.request("GET", "/lab/environment", null);
            if (!"ready".equals(environment.path("status").asText()))
                throw new IllegalStateException(environment.path("error").asText("请先创建就绪的模拟环境"));
            if (!environment.path("id").asText().equals(config.path("environment").path("id").asText()))
                throw new IllegalStateException("请先应用当前配置，创建模拟环境");
            JsonNode applied = environments.config();
            JsonNode conditions = config.path("conditions").isObject() ? config.path("conditions") : json.createObjectNode();
            if (!applied.path("appliedConditions").equals(conditions)
                    || !applied.path("appliedDevices").equals(config.path("devices"))
                    || applied.path("appliedAutoComplete").asBoolean() != config.path("autoComplete").asBoolean())
                throw new IllegalStateException("设备配置已修改，请重新应用模拟环境");
            if (!tango.connected()) throw new IllegalStateException("模拟设备事件正在重连，请稍后再启动");
            try {
                Map<String,JsonNode> facilities=new LinkedHashMap<>();
                for(String id:List.of(FACILITY_DEFINITION,FACILITY_BINDING,FACILITY_STATE)) {
                    JsonNode revisions=store.get(id).path("revisions");
                    if(revisions.isEmpty())throw new IllegalArgumentException("设施模型尚未发布："+id);
                    facilities.put(id,revisions.get(revisions.size()-1));
                }
                String definitionXml=facilities.get(FACILITY_DEFINITION).path("xml").asText();
                String bindingXml=facilities.get(FACILITY_BINDING).path("xml").asText();
                String projectionXml=facilities.get(FACILITY_STATE).path("xml").asText();
                var facilityDefinition=cn.cdi8.workbench.systemdefinition.SystemDefinitionModel.parse(definitionXml);
                var facilityBinding=cn.cdi8.workbench.facility.FacilityBindingModel.parse(bindingXml,facilityDefinition);
                var facilityProjection=cn.cdi8.workbench.facility.FacilityStateProjectionModel.parse(projectionXml);
                if(!facilityBinding.id().equals(facilityProjection.bindingRef()))throw new IllegalArgumentException("设施投影 bindingRef 不一致");
                Map<String,String> selectedComponents=new LinkedHashMap<>();
                Map<String,JsonNode> liveDevices=new LinkedHashMap<>();
                for(JsonNode device:environment.path("devices"))liveDevices.put(device.path("id").asText(),device);
                Set<String> expectedDevices=new HashSet<>();
                for(JsonNode row:config.path("devices")) {
                    String runtimeKey=row.path("component").asText(),type=facilityBinding.componentForRuntimeKey(runtimeKey);
                    if(type.isBlank())throw new IllegalArgumentException("设施绑定中没有组件："+runtimeKey);
                    String modelId=row.path("model").asText(),revision=row.path("revision").asText();
                    if(!"scxml".equals(store.get(modelId).path("kind").asText()))throw new IllegalArgumentException("设备必须选择 SCXML 模型："+modelId);
                    String xml=store.revision(modelId,revision).path("xml").asText();
                    String previous=selectedComponents.putIfAbsent(type,xml);
                    if(previous!=null&&!previous.equals(xml))throw new IllegalArgumentException("同一组件类型必须使用同一当前模型："+type);
                    int count=row.path("count").asInt(1);
                    if(count<1||count>200)throw new IllegalArgumentException("无效组件实例数："+runtimeKey);
                    for(int i=1;i<=count;i++) {
                        String id=runtimeKey+"_"+String.format("%02d",i);var live=liveDevices.get(id);
                        if(!expectedDevices.add(id)||live==null||!runtimeKey.equals(live.path("component").asText())
                            ||!revision.equals(live.path("revision").asText())
                            ||!cn.cdi8.model.ComponentContract.digest(xml).equals(live.path("modelDigest").asText()))
                            throw new IllegalArgumentException("实际模拟设备模型与当前配置不一致，请重新应用环境："+id);
                    }
                }
                if(!expectedDevices.equals(liveDevices.keySet()))throw new IllegalArgumentException("实际设备目录与当前配置不一致，请重新应用环境");
                var componentModels=validation.components(facilityDefinition).withSelected(facilityDefinition,selectedComponents);
                facilityProjection.validateComponents(facilityDefinition,componentModels);
                Map<String, Document> documents = new LinkedHashMap<>();
                Map<String, String> versions = new LinkedHashMap<>();
                for (JsonNode selected : config.path("processes")) {
                    String model = selected.path("model").asText(), revision = selected.path("revision").asText();
                    JsonNode version = store.revision(model, revision);
                    JsonNode result = validation.validateBpmn(version.path("xml").asText(),facilityDefinition,facilityProjection,componentModels);
                    if (!result.path("valid").asBoolean())
                        throw new IllegalArgumentException("流程版本校验失败：" + model + " " + result.path("diagnostics"));
                    Document doc = document(version.path("xml").asText());
                    var processes = doc.getElementsByTagNameNS("*", "process");
                    for (int i = 0; i < processes.getLength(); i++) {
                        Element process = (Element) processes.item(i);
                        if (!"true".equalsIgnoreCase(process.getAttribute("isExecutable")))
                            throw new IllegalArgumentException("新链路只能运行 isExecutable=true 的 BPMN：" + model);
                    }
                    documents.put(model, doc);
                    versions.put(model, revision);
                }
                String entry = config.path("entry").asText();
                if (!documents.containsKey(entry)) throw new IllegalArgumentException("请选择入口流程的版本");

                String release = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
                Map<String, String> pinned = new HashMap<>();
                Map<String, String> owners = new HashMap<>();
                for (var item : documents.entrySet()) {
                    var processes = item.getValue().getElementsByTagNameNS("*", "process");
                    for (int i = 0; i < processes.getLength(); i++) {
                        String key = ((Element) processes.item(i)).getAttribute("id");
                        if (pinned.putIfAbsent(key, key + "__run_" + release) != null)
                            throw new IllegalArgumentException("重复流程 key：" + key);
                        owners.put(key, item.getKey());
                    }
                }
                Set<String> needed = new LinkedHashSet<>();
                ArrayDeque<String> queue = new ArrayDeque<>();
                queue.add(entry);
                while (!queue.isEmpty()) {
                    String id = queue.remove();
                    if (!needed.add(id)) continue;
                    var calls = documents.get(id).getElementsByTagNameNS("*", "callActivity");
                    for (int i = 0; i < calls.getLength(); i++) {
                        String key = ((Element) calls.item(i)).getAttribute("calledElement");
                        if (!owners.containsKey(key)) throw new IllegalArgumentException("请在配置中选择子流程版本：" + key);
                        queue.add(owners.get(key));
                    }
                }

                var deployment = repository.createDeployment().name("new-model-run-" + release);
                for(var item:facilities.entrySet()) {
                    deployment.addString("facility-model-"+item.getKey()+".xml",item.getValue().path("xml").asText());
                    versions.put("system/"+item.getKey(),item.getValue().path("id").asText());
                }
                componentModels.xml().forEach((type,xml)->deployment.addString("facility-component-"+type+".scxml",xml));
                String entryKey = null;
                for (String id : needed) {
                    Document doc = documents.get(id);
                    var all = doc.getElementsByTagName("*");
                    for (int i = 0; i < all.getLength(); i++) {
                        Element element = (Element) all.item(i);
                        if ("process".equals(element.getLocalName())) {
                            String key = element.getAttribute("id");
                            element.setAttribute("id", pinned.get(key));
                            if (id.equals(entry) && entryKey == null) entryKey = pinned.get(key);
                        }
                        if ("callActivity".equals(element.getLocalName()) && pinned.containsKey(element.getAttribute("calledElement")))
                            element.setAttribute("calledElement", pinned.get(element.getAttribute("calledElement")));
                        if ("BPMNPlane".equals(element.getLocalName()) && pinned.containsKey(element.getAttribute("bpmnElement"))
                                && owners.get(element.getAttribute("bpmnElement")).equals(id))
                            element.setAttribute("bpmnElement", pinned.get(element.getAttribute("bpmnElement")));
                    }
                    var output = new java.io.StringWriter();
                    TransformerFactory.newInstance().newTransformer().transform(new DOMSource(doc), new StreamResult(output));
                    deployment.addString(id + ".bpmn20.xml", output.toString());
                }

                Map<String, Object> variables = new HashMap<>();
                if (config.path("variables").isObject()) variables.putAll(json.convertValue(config.get("variables"), Map.class));
                for(String name:variables.keySet())
                    if(name.startsWith("facilityAction") || name.startsWith("facilityCompletion") || name.startsWith("facilityDepend") || name.startsWith("facilityHold") || name.startsWith("holdFailure") || name.equals("holdHistory") || name.equals("interruptedCompletionWaits") || name.equals("facilityInstances") || name.equals("dependencyHistory") || name.startsWith("command") && !"commandTimeout".equals(name)
                        || Set.of("target","sent","dispatchAttempted","shotRun","failureReason","outcome","finalFacilityState","runtimeChain").contains(name))
                        throw new IllegalArgumentException("不能覆盖内部运行变量："+name);
                var catalog = tango.catalog();
                Map<String, Map<String, String>> devices = new LinkedHashMap<>();
                for (JsonNode device : catalog) {
                    String id = device.path("id").asText();
                    if (!device.path("connected").asBoolean() || tango.cached(id) == null)
                        throw new IllegalStateException("设备未连接：" + id);
                    if (device.path("snapshot").hasNonNull("active_command_id"))
                        throw new IllegalStateException("设备仍有动作：" + id);
                    String runtimeKey=device.path("component").asText();
                    String component="context".equals(runtimeKey)?runtimeKey:facilityBinding.componentForRuntimeKey(runtimeKey);
                    if(component.isBlank())throw new IllegalArgumentException("设施绑定中没有设备组件："+runtimeKey);
                    if(!"context".equals(component) && !cn.cdi8.model.ComponentContract.digest(componentModels.xml().get(component)).equals(tango.cached(id).path("model_digest").asText()))
                        throw new IllegalArgumentException("Tango 设备当前模型与已核验模型不一致："+id);
                    devices.put(id, Map.of("id", id, "component", component,"runtimeKey",runtimeKey));
                }
                populateCollections(variables, devices);
                applyConfiguredBindings(variables, devices, config.path("bindings"));
                Map<String,String> explicitInstances=new LinkedHashMap<>();
                if(config.has("facilityInstances")) {
                    if(!config.path("facilityInstances").isObject())throw new IllegalArgumentException("facilityInstances 必须是逻辑实例到设备编号的对象");
                    config.path("facilityInstances").fields().forEachRemaining(bindingEntry->{
                        if(!bindingEntry.getValue().isTextual())throw new IllegalArgumentException("实例设备编号必须是字符串");
                        explicitInstances.put(bindingEntry.getKey(),bindingEntry.getValue().asText());
                    });
                }
                variables.put("facilityInstances",facilityBinding.topology().bind(devices,explicitInstances));
                variables.putIfAbsent("shotId", config.path("shotId").asText(UUID.randomUUID().toString()));
                variables.putIfAbsent("recipeRef", config.path("recipeRef").asText("workbench-recipe"));
                variables.putIfAbsent("recipeVersion", config.path("recipeVersion").asText("1"));
                variables.putIfAbsent("beamlines", json.convertValue(config.path("beamlines").isArray() ? config.path("beamlines") : List.of(1), List.class));
                variables.putIfAbsent("defaultSource", config.path("defaultSource").asText(facilityBinding.topology().defaultSource()));
                variables.putIfAbsent("sourceSelections",config.path("sourceSelections").isObject()?json.convertValue(config.path("sourceSelections"),Map.class):Map.of());
                variables.putIfAbsent("commandTimeout", "PT15M");
                variables.putIfAbsent("workflowTimeout", "PT45M");
                for(String timeout:List.of("commandTimeout","workflowTimeout")) {
                    if(java.time.Duration.parse(variables.get(timeout).toString()).toMillis()<=0)
                        throw new IllegalArgumentException(timeout+" 必须大于零");
                }
                for(String reserved:List.of("shotRun","commandHistory","finalFacilityState","finalCompletionWaits","failureReason","communicationError"))
                    if(variables.containsKey(reserved))throw new IllegalArgumentException("不能覆盖内部运行变量："+reserved);
                variables.put("outcome", "RUNNING");
                variables.put("workbench", true);
                variables.put("modelVersions", versions);
                variables.put("facilityModels", Map.of("definition", facilityDefinition.id(), "binding", facilityBinding.id(), "stateProjection", facilityProjection.id()));
                variables.put("workbenchConfiguration", json.convertValue(config, Map.class));
                var deployed = cn.cdi8.workbench.facility.FacilityBpmnModel.deployWithModels(facilityDefinition,facilityProjection,componentModels,deployment::deploy);
                facilityStates.activate(definitionXml,bindingXml,projectionXml,componentModels);
                var definition = repository.createProcessDefinitionQuery().deploymentId(deployed.getId())
                    .processDefinitionKey(entryKey).singleResult();
                return workflows.startDefinition(definition.getId(), variables);
            } catch (RuntimeException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new IllegalArgumentException(ex.getMessage(), ex);
            }
        }
    }

    private void populateCollections(Map<String, Object> variables, Map<String, Map<String, String>> devices) {
        List<Map<String, String>> all = devices.values().stream()
            .filter(device -> !"context".equals(device.get("component"))).toList();
        variables.put("allComponents", all);
        variables.putIfAbsent("parameterTargets", all.stream()
            .filter(device -> !Set.of("vacuum", "control-environment").contains(device.get("component"))).toList());
        Map<String,Object> collections=new LinkedHashMap<>();
        for (var target : devices.values()) {
            String collection = target.get("component") + "Targets";
            collections.computeIfAbsent(collection, ignored -> new ArrayList<Map<String, String>>());
            ((List<Map<String, String>>) collections.get(collection)).add(target);
            if (target.get("component").contains("-")) {
                StringBuilder alias = new StringBuilder(); boolean uppercase = false;
                for (char c : target.get("component").toCharArray()) {
                    if (c == '-') { uppercase = true; continue; }
                    alias.append(uppercase ? Character.toUpperCase(c) : c); uppercase = false;
                }
                String aliasCollection = alias + "Targets";
                collections.computeIfAbsent(aliasCollection, ignored -> new ArrayList<Map<String, String>>());
                ((List<Map<String, String>>) collections.get(aliasCollection)).add(target);
            }
        }
        variables.putAll(collections);
    }

    private void applyConfiguredBindings(Map<String, Object> variables, Map<String, Map<String, String>> devices, JsonNode bindings) {
        if (!bindings.isObject()) return;
        var fields = bindings.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            if(field.getKey().startsWith("facility")||Set.of("dependencyHistory","sourceSelections").contains(field.getKey()))throw new IllegalArgumentException("设备集合不能覆盖内部或选源变量："+field.getKey());
            if("allComponents".equals(field.getKey()))throw new IllegalArgumentException("allComponents 由实际设备目录生成，不能覆盖");
            if (!field.getValue().isArray()) throw new IllegalArgumentException("设备集合必须是编号数组：" + field.getKey());
            List<Map<String, String>> targets = new ArrayList<>();
            for (JsonNode id : field.getValue()) {
                if (!devices.containsKey(id.asText())) throw new IllegalArgumentException("绑定设备不存在：" + id.asText());
                targets.add(devices.get(id.asText()));
            }
            variables.put(field.getKey(), targets);
        }
    }
}
