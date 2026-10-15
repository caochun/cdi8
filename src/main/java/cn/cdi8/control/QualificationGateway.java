package cn.cdi8.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import org.flowable.bpmn.model.ReceiveTask;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.delegate.BpmnError;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.runtime.Execution;
import org.springframework.stereotype.Component;

/** Domain gateway: durable command aggregation, current qualification and loss monitoring.
 * Standard receiveTask is its persistence carrier; gxlf:qualificationGateway defines its semantics.
 */
@Component("qualification")
public class QualificationGateway {
    public static final String LOST_MESSAGE = "QualificationLost";
    private static final String RECORDS = "qualificationGates";
    private final RuntimeService runtime;
    private final TangoGateway gateway;
    private final ObjectMapper json;
    private final ArrayBlockingQueue<JsonNode> observations = new ArrayBlockingQueue<>(4096);
    private final AtomicBoolean overflow = new AtomicBoolean();
    private final Map<String,Long> lastQuery = new HashMap<>();

    public QualificationGateway(RuntimeService runtime, TangoGateway gateway, ObjectMapper json) {
        this.runtime = runtime; this.gateway = gateway; this.json = json;
    }

    public void event(TangoGateway.Notice notice) {
        if (!observations.offer(notice.payload().deepCopy())) overflow.set(true);
    }

    /** Register every command before the wait activity is committed; no I/O here. */
    public void prepare(DelegateExecution e, String gateId) {
        var gate = (ReceiveTask)e.getCurrentFlowElement().getParentContainer().getFlowElement(gateId);
        var extensions = gate.getExtensionElements().get("qualificationGateway");
        if (extensions == null || extensions.size() != 1) throw invalid("资格网关缺少配置");
        ObjectNode state = json.createObjectNode();
        extensions.get(0).getAttributes().forEach((key, values) -> state.put(key, values.get(0).getValue()));
        for (String key : List.of("targets", "action", "state", "outer", "continuationStates", "timeout"))
            if (state.path(key).asText().isBlank()) throw invalid("资格网关配置缺少 " + key);
        String timeout = state.path("timeout").asText();
        if (timeout.matches("\\$\\{[A-Za-z][A-Za-z0-9_]*}"))
            timeout = Objects.toString(e.getVariable(timeout.substring(2, timeout.length()-1)), "");
        try {
            Duration duration = Duration.parse(timeout);
            if (duration.isNegative() || duration.isZero()) throw new IllegalArgumentException();
        } catch (RuntimeException ex) { throw invalid("资格网关等待时限必须为正 ISO-8601 时长"); }
        state.put("resolvedTimeout", timeout);
        String pid = e.getProcessInstanceId();
        @SuppressWarnings("unchecked") var targets = (List<Map<String,String>>)e.getVariable(state.path("targets").asText());
        if (targets == null || targets.isEmpty()) throw invalid("资格网关的参与组件不能为空");
        ObjectNode records = records(pid);
        if (records.isEmpty()) { observations.clear(); overflow.set(false); lastQuery.clear(); }
        state.put("id", gateId); state.put("name", gate.getName()); state.put("status", "WAITING");
        ArrayNode commands = state.putArray("commands");
        Set<String> ids = new HashSet<>();
        for (var target : targets) {
            String device = target.get("id");
            if (!ids.add(device)) throw invalid("资格网关的组件清单重复：" + device);
            String command = UUID.nameUUIDFromBytes((pid + ":" + gateId + ":" + device).getBytes(StandardCharsets.UTF_8)).toString();
            commands.addObject().put("commandId", command).put("commandDevice", device)
                .put("commandAction", state.path("action").asText()).put("commandNode", gateId)
                .put("expectedState", state.path("state").asText()).put("sent", false).put("acquired", false);
        }
        records.set(gateId, state); save(pid, records);
        runtime.setVariable(pid, "qualificationStage", gateId);
        runtime.setVariable(pid, "qualificationGatePassed", false);
        runtime.setVariable(pid, "qualificationTimeout", timeout);
    }

    public boolean handles(Execution e) { return records(e.getProcessInstanceId()).has(e.getActivityId()); }

    /** Called by the existing event/result pump, only after the wait is durable. */
    public void advance(Execution e, Set<String> dirty) {
        String pid = e.getProcessInstanceId(), gateId = e.getActivityId();
        ObjectNode records = records(pid), state = (ObjectNode)records.get(gateId);
        String reason = loss(pid);
        if (reason != null) { interrupt(pid, reason); return; }
        for (JsonNode item : state.path("commands")) {
            ObjectNode command = (ObjectNode)item;
            if (command.path("acquired").asBoolean()) continue;
            String device = command.path("commandDevice").asText(), id = command.path("commandId").asText();
            boolean sent = command.path("sent").asBoolean();
            long now = System.currentTimeMillis();
            if (!dirty.contains(device) && now - lastQuery.getOrDefault(id, 0L) < (sent ? 5000 : 1000)) continue;
            lastQuery.put(id, now);
            JsonNode result;
            try {
                result = sent ? gateway.result(device, id) : gateway.execute(device, id, state.path("action").asText(), Map.of());
                command.put("sent", true);
            } catch (TangoGateway.GatewayException ex) {
                if (ex.status != 409) throw ex;
                result = json.valueToTree(Map.of("command_id", id, "action", state.path("action").asText(),
                    "status", "rejected", "reason", ex.getMessage()));
            }
            command.put("commandResult", result.toString()); save(pid, records);
            String status = result.path("status").asText();
            if (Set.of("accepted", "executing").contains(status)) continue;
            if (!id.equals(result.path("command_id").asText())
                    || !state.path("action").asText().equals(result.path("action").asText())
                    || !"succeeded".equals(status)) {
                reject(e, records, state, device + " 检查未成功或命令关联不匹配：" + status); return;
            }
            JsonNode snapshot = gateway.snapshot(device);
            if (!matches(snapshot, state) || snapshot.hasNonNull("active_command_id") || !sameResult(snapshot, id)) {
                reject(e, records, state, device + " 未达到本次检查的目标资格"); return;
            }
            command.put("acquired", true);
            command.put("revision", result.path("snapshot").path("state_revision").asLong(snapshot.path("state_revision").asLong(-1)));
            save(pid, records);
        }
        for (JsonNode command : state.path("commands")) if (!command.path("acquired").asBoolean()) return;
        // Read every component again at the join, including early completions.
        reason = freshLoss(pid, records);
        if (reason != null) { interrupt(pid, reason); return; }
        state.put("status", "PASSED"); save(pid, records);
        runtime.setVariable(pid, "communicationError", "");
        runtime.trigger(e.getId(), Map.of("qualificationGatePassed", true));
    }

    private void reject(Execution e, ObjectNode records, ObjectNode state, String reason) {
        state.put("status", "FAILED"); save(e.getProcessInstanceId(), records);
        runtime.trigger(e.getId(), Map.of("qualificationGatePassed", false, "failureReason", reason));
    }

    /** Gate receipts and policies are process variables, including across restarts. */
    public String loss(String pid) {
        ObjectNode records = records(pid);
        if (records.isEmpty()) return null;
        Object existing = runtime.getVariable(pid, "qualificationLossReason");
        if (existing != null) return existing.toString();
        String reason = overflow.getAndSet(false) ? "资格事件缓冲溢出，无法确认期间资格持续有效" : null;
        JsonNode event;
        while ((event = observations.poll()) != null) {
            String lost = check(records, stage(pid), event.path("device_id").asText(),
                event.path("connected").asBoolean() ? event.path("snapshot") : null, true);
            if (reason == null) reason = lost;
        }
        for (String device : devices(records)) {
            String lost = check(records, stage(pid), device, gateway.cached(device), false);
            if (reason == null) reason = lost;
        }
        return latch(pid, reason);
    }

    private String freshLoss(String pid, ObjectNode records) {
        String reason = loss(pid);
        if (reason != null) return reason;
        if (!gateway.connected()) return latch(pid, "Tango 事件连接不可用");
        for (String device : devices(records)) {
            reason = check(records, stage(pid), device, gateway.snapshot(device), false);
            if (reason != null) return latch(pid, reason);
        }
        return loss(pid);
    }

    private String check(ObjectNode records, String active, String device, JsonNode snapshot, boolean event) {
        JsonNode acquiredGate = null, receipt = null;
        for (JsonNode gate : records) for (JsonNode command : gate.path("commands"))
            if (command.path("commandDevice").asText().equals(device) && command.path("acquired").asBoolean()) {
                acquiredGate = gate; receipt = command;
            }
        if (receipt == null) return null;
        long revision = snapshot == null ? -1 : snapshot.path("state_revision").asLong(-1);
        if (event && revision >= 0 && revision <= receipt.path("revision").asLong(-1)) return null;
        boolean ownStage = acquiredGate.path("id").asText().equals(active);
        String required = acquiredGate.path("outer").asText() + "/" + acquiredGate.path("state").asText();
        Set<String> allowed = ownStage ? Set.of(required)
            : Set.of(acquiredGate.path("continuationStates").asText().split("\\s+"));
        String current = snapshot == null ? "unknown" : snapshot.path("outer_state").asText() + "/" + snapshot.path("state_id").asText();
        if (!allowed.contains(current)) return device + " 已取得的检查资格失效：" + current;
        if (snapshot.hasNonNull("active_command_id")) {
            boolean ownNextCommand = false;
            if (!ownStage) for (JsonNode command : records.path(active).path("commands"))
                if (device.equals(command.path("commandDevice").asText())
                        && snapshot.path("active_command_id").asText().equals(command.path("commandId").asText())) ownNextCommand = true;
            if (!ownNextCommand) return device + " 检查资格建立后出现其他活动命令";
        } else if (!sameResult(snapshot, receipt.path("commandId").asText())) {
            boolean ownNextResult = false;
            if (!ownStage) for (JsonNode command : records.path(active).path("commands"))
                if (device.equals(command.path("commandDevice").asText())
                        && sameResult(snapshot, command.path("commandId").asText())) ownNextResult = true;
            if (!ownNextResult) return device + " 当前资格不再对应本轮检查命令";
        }
        return null;
    }

    public void finish(DelegateExecution e) {
        String reason = freshLoss(e.getProcessInstanceId(), records(e.getProcessInstanceId()));
        if (reason != null) { e.setVariable("failureReason", reason); throw invalid(reason); }
        e.setVariable("outcome", "SUCCEEDED");
    }

    public void timeout(DelegateExecution e, String gate) {
        String pid = e.getProcessInstanceId(); ObjectNode records = records(pid);
        ((ObjectNode)records.path(gate)).put("status", "TIMED_OUT"); save(pid, records);
        e.setVariable("failureReason", records.path(gate).path("name").asText() + " 等待超时");
    }

    private void interrupt(String pid, String reason) {
        runtime.setVariable(pid, "failureReason", reason);
        close(pid);
        var subscription = runtime.createEventSubscriptionQuery().processInstanceId(pid).eventName(LOST_MESSAGE).singleResult();
        runtime.messageEventReceived(LOST_MESSAGE, subscription.getExecutionId());
    }
    public void close(String pid) {
        ObjectNode records = records(pid);
        for (JsonNode gate : records) if ("WAITING".equals(gate.path("status").asText()))
            ((ObjectNode)gate).put("status", runtime.getVariable(pid, "qualificationLossReason") == null ? "STOPPED" : "INVALIDATED");
        if (!records.isEmpty()) save(pid, records);
    }
    private String latch(String pid, String reason) {
        if (reason != null) runtime.setVariable(pid, "qualificationLossReason", reason);
        return reason;
    }
    private boolean matches(JsonNode snapshot, JsonNode state) {
        return snapshot != null && state.path("outer").asText().equals(snapshot.path("outer_state").asText())
            && state.path("state").asText().equals(snapshot.path("state_id").asText());
    }
    private boolean sameResult(JsonNode snapshot, String command) {
        return !snapshot.hasNonNull("last_result") || command.equals(snapshot.path("last_result").path("command_id").asText());
    }
    private Set<String> devices(ObjectNode records) {
        Set<String> result = new LinkedHashSet<>();
        for (JsonNode gate : records) for (JsonNode command : gate.path("commands")) result.add(command.path("commandDevice").asText());
        return result;
    }
    private String stage(String pid) { return Objects.toString(runtime.getVariable(pid, "qualificationStage"), ""); }
    private ObjectNode records(String pid) {
        Object value = runtime.getVariable(pid, RECORDS);
        try { return value == null ? json.createObjectNode() : (ObjectNode)json.readTree(value.toString()); }
        catch (Exception ex) { throw invalid("资格证据解析失败"); }
    }
    private void save(String pid, ObjectNode value) { runtime.setVariable(pid, RECORDS, value.toString()); }
    private BpmnError invalid(String reason) { return new BpmnError("QUALIFICATION_INVALID", reason); }
}
