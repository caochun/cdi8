package cn.cdi8.workbench.scenarios.gxlf;

import cn.cdi8.workbench.integration.tango.TangoGateway;
import cn.cdi8.workbench.run.RunPolicies;
import cn.cdi8.workbench.run.RunPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.stereotype.Component;

/** GXLF source selection, model identities and stage entry checks. */
@Component
public class GxlfRunPolicy implements RunPolicy {
    static final Set<String> PROCESS_KEYS = Set.of("component_initialization", "parameter_dispatch",
        "optical_frontend_preparation", "formal_emission", "post_emission_processing", "emission_task_lifecycle");
    private final TangoGateway gateway;

    public GxlfRunPolicy(TangoGateway gateway) { this.gateway = gateway; }
    @Override public Set<String> processKeys() { return PROCESS_KEYS; }
    @Override public List<String> defaultSources(String key) {
        return Set.of("component_initialization", "parameter_dispatch").contains(key) ? List.of() : List.of("seed");
    }
    @Override public Map<String, Object> defaults(Map<String, Object> variables) {
        return Map.of("scenarioId", "gxlf", "sourceTargets", variables.getOrDefault("seedTargets", List.of()));
    }
    @Override public List<String> participantCollections(String key) {
        String original = RunPolicies.originalKey(key);
        if (Set.of("component_initialization", "parameter_dispatch").contains(original)) return List.of("allComponents");
        if ("emission_task_lifecycle".equals(original)) return List.of("allComponents", "contextTargets");
        return List.of("sourceTargets", "preampTargets", "multipassTargets", "contextTargets");
    }
    @Override public Map<String, Object> variables(String key, List<String> sources) {
        List<String> selectedSources = sources == null ? List.of() : sources;
        boolean requiresSources = Set.of("optical_frontend_preparation", "formal_emission", "post_emission_processing", "emission_task_lifecycle").contains(key);
        boolean parameterDispatch = key.equals("parameter_dispatch");
        boolean preparation = key.equals("optical_frontend_preparation");
        boolean formalEmission = key.equals("formal_emission");
        boolean postEmission = key.equals("post_emission_processing");
        if (!Set.of("seed", "shg").containsAll(selectedSources)
                || (requiresSources && selectedSources.isEmpty()))
            throw new IllegalArgumentException("请选择至少一类光源");
        List<Map<String,String>> all = new ArrayList<>(), light = new ArrayList<>(), seed = new ArrayList<>(), shg = new ArrayList<>(), preamp = new ArrayList<>(), multipass = new ArrayList<>(), context = new ArrayList<>();
        for (var device : gateway.catalog()) {
            String component = device.path("component").asText();
            String id = device.path("id").asText();
            if (!Set.of("seed","shg","preamp","multipass","context").contains(component)) continue;
            if (!id.matches("[A-Za-z0-9_-]{1,128}")) throw new IllegalArgumentException("非法设备编号");
            if (!device.path("connected").asBoolean() || gateway.cached(id) == null) throw new IllegalStateException("设备未连接：" + id);
            JsonNode state = gateway.snapshot(id);
            if (state.hasNonNull("active_command_id")) throw new IllegalStateException("设备仍有任务：" + id);
            var target = Map.of("id", id, "component", component);
            if (component.equals("context")) context.add(target);
            else {
                all.add(target);
                if (component.equals("seed")) seed.add(target);
                if (component.equals("shg")) shg.add(target);
                if (component.equals("preamp")) preamp.add(target);
                if (component.equals("multipass")) multipass.add(target);
                if (selectedSources.contains(component)) light.add(target);
                String model = state.path("model").asText();
                String expectedModel = Map.of("seed","seed-source-reference-v2","shg","shg-injection-hierarchical-v2",
                    "preamp","preamp-hierarchical-v3","multipass","multipass-hierarchical-v3").get(component);
                if (!model.equals(expectedModel)) throw new IllegalStateException("组件模型不匹配：" + id);
                if (preparation) {
                    Set<String> ready = component.equals("seed")
                        ? Set.of("configured","parameterOk","standbyReady")
                        : Set.of("parameterOk");
                    if (!state.path("outer_state").asText().equals("ready") || !ready.contains(state.path("state_id").asText()))
                        throw new IllegalStateException(id + " 尚未具备发射准备资格，请先完成参数下发");
                } else if (formalEmission) {
                    boolean valid = switch (component) {
                        case "seed", "shg" -> selectedSources.contains(component)
                            ? "emitting".equals(state.path("outer_state").asText())
                            : "ready".equals(state.path("outer_state").asText())
                                && Set.of("configured", "parameterOk", "standbyReady").contains(state.path("state_id").asText());
                        case "preamp" -> "wakeup".equals(state.path("outer_state").asText())
                            && "fineLoopOk".equals(state.path("state_id").asText());
                        case "multipass" -> "collimated".equals(state.path("outer_state").asText())
                            && "collimationReady".equals(state.path("state_id").asText());
                        default -> true;
                    };
                    if (!valid || state.hasNonNull("active_command_id"))
                        throw new IllegalStateException(id + " 尚未具备正式发射资格，请先完成发射准备");
                } else if (postEmission) {
                    boolean valid = switch (component) {
                        case "seed", "shg" -> selectedSources.contains(component)
                            ? "emitting".equals(state.path("outer_state").asText())
                            : "ready".equals(state.path("outer_state").asText());
                        case "preamp" -> "energyMeterCleared".equals(state.path("state_id").asText());
                        case "multipass" -> "collimated".equals(state.path("outer_state").asText())
                            && "collimationReady".equals(state.path("state_id").asText());
                        default -> true;
                    };
                    if (!valid || state.hasNonNull("active_command_id"))
                        throw new IllegalStateException(id + " 尚未具备发射后处理资格，请先完成正式发射");
                } else if (parameterDispatch) {
                    Set<String> ready = component.equals("seed")
                        ? Set.of("qualified","configured","standbyReady")
                        : Set.of("functionReady");
                    if (!state.path("outer_state").asText().equals("ready") || !ready.contains(state.path("state_id").asText()))
                        throw new IllegalStateException(id + " 尚未完成组件初始化，不能进行参数下发");
                } else if (!state.path("outer_state").asText().equals("notReady")) {
                    String outerLabel = state.path("outer_state_label").asText(state.path("outer_state").asText());
                    String stateId = state.path("state_id").asText();
                    throw new IllegalStateException(id + " 当前为 " + outerLabel + "/" + stateId
                        + "，初始化只能从未就绪状态开始；请先执行下电或复位");
                }
            }
        }
        if (all.isEmpty()) throw new IllegalStateException("没有可控组件");
        boolean requiresAllModeledComponents = Set.of("component_initialization", "parameter_dispatch", "formal_emission", "post_emission_processing", "emission_task_lifecycle").contains(key);
        if (requiresAllModeledComponents && (seed.isEmpty() || shg.isEmpty() || preamp.isEmpty() || multipass.isEmpty()))
            throw new IllegalStateException("初始化需要种子源、二倍频、再生与双程放大、多程放大四类组件");
        if (requiresSources && (preamp.isEmpty() || multipass.isEmpty() || light.isEmpty() || context.size()!=1))
            throw new IllegalStateException("准备流程需要所选光源、预放、多程及一个同步/环境条件设备");
        for (String source : selectedSources) if (light.stream().noneMatch(d -> d.get("component").equals(source)))
            throw new IllegalStateException("所选光源不存在：" + source);
        Map<String,Object> variables = new HashMap<>();
        // Initialization deliberately uses a dedicated target collection. It currently
        // contains exactly the four modeled component types; context devices are kept
        // separate for the preparation workflow and never enter initialization gates.
        variables.put("allComponents", all);
        variables.put("seedTargets", seed); variables.put("shgTargets", shg);
        variables.put("sourceTargets", light);
        variables.put("preampTargets", preamp); variables.put("multipassTargets", multipass); variables.put("contextTargets", context);
        variables.put("scenarioId", "gxlf");
        return variables;
    }
}
