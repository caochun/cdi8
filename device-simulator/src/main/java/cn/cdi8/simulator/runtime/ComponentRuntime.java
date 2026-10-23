package cn.cdi8.simulator.runtime;

import cn.cdi8.simulator.engine.ScxmlEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static cn.cdi8.simulator.runtime.ComponentModel.*;

/** Durable component command protocol. SCXML transitions are delegated to the Apache engine. */
public class ComponentRuntime implements AutoCloseable {
    private final ComponentModel model;
    private final String sourceId = UUID.randomUUID().toString();
    private final String modelRevision;
    private final ScxmlEngine engine;
    private final Connection db;

    public ComponentRuntime(ComponentModel model, Path database) {
        this(model, database, null);
    }

    public ComponentRuntime(ComponentModel model, Path database, String modelRevision) {
        this.model=model;
        this.modelRevision=modelRevision;
        engine=new ScxmlEngine(model.xml);
        try {
            Path path=database.toAbsolutePath();
            Files.createDirectories(path.getParent());
            db=DriverManager.getConnection("jdbc:sqlite:"+path);
            sql("PRAGMA busy_timeout=10000");
            try {
                transaction(() -> {
                    var tables=rows("SELECT name FROM sqlite_master WHERE type='table'");
                    boolean fresh=tables.isEmpty();
                    boolean current=tables.stream().anyMatch(t -> "java_component_schema".equals(t.get("name")));
                    boolean legacySeed=tables.stream().anyMatch(t -> "java_seed_schema".equals(t.get("name")))
                        && "seed-source-reference-v2".equals(model.metadata.path("model_id").asText());
                    if (!fresh && !current && !legacySeed)
                        throw new IllegalArgumentException("请为 Java 组件使用独立数据库路径");
                    if (fresh) {
                        sql("CREATE TABLE java_component_schema(version INTEGER NOT NULL,model TEXT NOT NULL,digest TEXT NOT NULL)");
                        sql("INSERT INTO java_component_schema VALUES(1,?,?)",model.metadata.path("model_id").asText(),model.digest);
                        sql("CREATE TABLE component_state(id INTEGER PRIMARY KEY CHECK(id=1),leaf TEXT NOT NULL,active_id TEXT,last_id TEXT,last_result_id TEXT,context TEXT NOT NULL)");
                        sql("CREATE TABLE component_commands(id TEXT PRIMARY KEY,action TEXT NOT NULL,parameters TEXT NOT NULL,status TEXT NOT NULL,key_state TEXT,completion TEXT,result TEXT NOT NULL)");
                        sql("CREATE TABLE component_events(sequence INTEGER PRIMARY KEY AUTOINCREMENT,event TEXT NOT NULL,command_id TEXT,event_id TEXT,source TEXT NOT NULL,target TEXT NOT NULL,detail TEXT NOT NULL,occurred_at TEXT NOT NULL DEFAULT(strftime('%Y-%m-%dT%H:%M:%fZ','now')))");
                        sql("CREATE TABLE component_receipts(id TEXT PRIMARY KEY,payload TEXT NOT NULL,result TEXT NOT NULL)");
                        sql("INSERT INTO component_state VALUES(1,?,NULL,NULL,NULL,?)",engine.leaf(),encode(model.metadata.path("context_defaults")));
                    } else {
                        var schema=first("SELECT * FROM "+(current?"java_component_schema":"java_seed_schema"));
                        if (((Number)schema.get("version")).intValue()!=1 || !model.digest.equals(schema.get("digest"))
                                || !model.metadata.path("model_id").asText().equals(schema.get("model")))
                            throw new IllegalArgumentException("数据库模型或版本不匹配，请使用新路径");
                        engine.restore((String)state().get("leaf"));
                    }
                    return null;
                });
            } catch (Exception ex) { db.close(); throw ex; }
        } catch (Exception ex) { throw new IllegalStateException("无法创建 Java 组件运行时："+ex.getMessage(),ex); }
    }

    @FunctionalInterface private interface Work<T> { T run() throws Exception; }
    private <T> T transaction(Work<T> work) {
        try {
            db.setAutoCommit(false);
            T value=work.run(); db.commit(); return value;
        } catch (Exception ex) {
            try {
                db.rollback();
                var state=state();
                if (state!=null) engine.restore((String)state.get("leaf"));
            } catch (Exception restore) { ex.addSuppressed(restore); }
            if (ex instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(ex.getMessage(),ex);
        } finally {
            try { db.setAutoCommit(true); } catch (SQLException ex) { throw new IllegalStateException(ex); }
        }
    }
    private void sql(String statement,Object... args) throws SQLException {
        try (var query=db.prepareStatement(statement)) {
            for (int i=0;i<args.length;i++) query.setObject(i+1,args[i]);
            query.execute();
        }
    }
    private List<Map<String,Object>> rows(String statement,Object... args) throws SQLException {
        try (var query=db.prepareStatement(statement)) {
            for (int i=0;i<args.length;i++) query.setObject(i+1,args[i]);
            try (var result=query.executeQuery()) {
                List<Map<String,Object>> rows=new ArrayList<>();
                while (result.next()) {
                    Map<String,Object> row=new LinkedHashMap<>();
                    for (int i=1;i<=result.getMetaData().getColumnCount();i++) row.put(result.getMetaData().getColumnLabel(i),result.getObject(i));
                    rows.add(row);
                }
                return rows;
            }
        }
    }
    private Map<String,Object> first(String statement,Object... args) throws SQLException {
        var rows=rows(statement,args);return rows.isEmpty()?null:rows.get(0);
    }
    private Map<String,Object> state() throws SQLException { return first("SELECT * FROM component_state WHERE id=1"); }
    private Map<String,Object> command(String id) throws SQLException {
        var row=first("SELECT * FROM component_commands WHERE id=?",id);
        if (row==null) throw new IllegalArgumentException("未知任务编号："+id);
        return row;
    }
    private void requireObject(JsonNode value) {
        if (value==null || !value.isObject()) throw new IllegalArgumentException("请求必须是 JSON 对象");
    }
    private void saveContext(ObjectNode context) throws SQLException { sql("UPDATE component_state SET context=? WHERE id=1",encode(context)); }
    private void setLeaf() throws SQLException { sql("UPDATE component_state SET leaf=? WHERE id=1",engine.leaf()); }
    private void invalidateConfiguration(ObjectNode context) {
        boolean valid=false;
        for (JsonNode leaf:model.metadata.path("configuration_valid_states")) if (engine.leaf().equals(leaf.asText())) valid=true;
        if (!valid && context.has("configuration")) context.putObject("configuration").put("status","unknown").putNull("version");
    }
    private void log(String event,ScxmlEngine.Step step,JsonNode detail,String command,String receipt) throws SQLException {
        ObjectNode value=detail.deepCopy();
        if (!step.transitions().isEmpty()) {
            var transition=value.putObject("transition");
            transition.set("exit_states",JSON.valueToTree(step.exits()));
            transition.set("entry_states",JSON.valueToTree(step.entries()));
            transition.set("transitions",JSON.valueToTree(step.transitions()));
        }
        sql("INSERT INTO component_events(event,command_id,event_id,source,target,detail) VALUES(?,?,?,?,?,?)",
            event,command,receipt,step.source(),step.target(),encode(value));
    }

    private ObjectNode snapshotValue() throws SQLException {
        var state=state();String leaf=(String)state.get("leaf"),outer=model.outer(leaf);
        var active=state.get("active_id")==null?null:command((String)state.get("active_id"));
        var last=state.get("last_id")==null?null:command((String)state.get("last_id"));
        var result=state.get("last_result_id")==null?null:command((String)state.get("last_result_id"));
        ObjectNode context=(ObjectNode)parse((String)state.get("context"));
        var value=JSON.createObjectNode();
        value.put("source_id",sourceId);
        value.put("supports_entry_condition",true);
        value.put("supports_action_contract",true);
        value.set("action_contracts",actionContracts(active!=null));
        value.put("state_revision",((Number)first("SELECT COALESCE(MAX(sequence),0) AS revision FROM component_events").get("revision")).longValue());
        value.put("model",model.metadata.path("model_id").asText()).put("model_version",model.metadata.path("model_version").asInt());
        value.put("model_revision",modelRevision);
        value.put("component",model.metadata.path("display_name").asText()).put("engine","apache-commons-scxml2");
        value.put("state_id",leaf).put("outer_state",outer).put("outer_state_label",model.metadata.path("outer_labels").path(outer).asText());
        value.put("inner_state",leaf).put("inner_state_label",model.metadata.path("inner_labels").path(leaf).asText());
        value.put("active_command_id",(String)state.get("active_id")).put("active_action",active==null?null:(String)active.get("action"));
        value.put("task_state",active!=null?"executing":last==null?"idle":(String)last.get("status"));
        value.set("context",context);
        if (result==null) value.putNull("last_result");
        else value.putObject("last_result").put("command_id",(String)result.get("id")).put("action",(String)result.get("action"))
            .put("status",(String)result.get("status")).put("key_state",(String)result.get("key_state"));
        for (String key:List.of("configuration","faults","data_qualification")) if (context.has(key)) value.set(key,context.get(key).deepCopy());
        return value;
    }
    public synchronized JsonNode snapshot() { return transaction(this::snapshotValue); }
    private String actionIssue(String action,boolean busy) {
        String event=model.requestEvent(action);
        if(busy) return "设备已有执行中的动作";
        try {
            if(!engine.enabled(event)) return "当前状态或守卫不接受动作："+action+"，当前状态为 "+engine.leaf();
        } catch(RuntimeException ex) { return "动作就绪无法确认："+ex.getMessage(); }
        return null;
    }
    private ObjectNode actionContracts(boolean busy) {
        var contracts=JSON.createObjectNode();
        model.metadata.path("actions").fields().forEachRemaining(action -> {
            var contract=contracts.putObject(action.getKey());
            contract.put("label",action.getValue().path("label").asText(action.getKey()));
            contract.putObject("entry").put("idle",true).put("event",model.requestEvent(action.getKey()));
            String issue=actionIssue(action.getKey(),busy);
            contract.put("ready",issue==null);
            if(issue!=null) contract.put("reason",issue);
        });
        return contracts;
    }
    private JsonNode updateResult(String id,String reason) throws SQLException {
        var command=command(id);var result=JSON.createObjectNode();
        result.put("command_id",id).put("action",(String)command.get("action")).put("status",(String)command.get("status"))
            .put("key_state",(String)command.get("key_state"));
        result.set("parameters",parse((String)command.get("parameters")));result.set("snapshot",snapshotValue());
        if (reason!=null) result.put("reason",reason);
        sql("UPDATE component_commands SET result=? WHERE id=?",encode(result),id);return result;
    }
    public synchronized JsonNode result(String id) {
        if (id==null || !id.matches("[A-Za-z0-9_-]{1,128}")) throw new IllegalArgumentException("非法任务编号");
        return transaction(()->parse((String)command(id).get("result")));
    }
    public synchronized JsonNode execute(JsonNode request) {
        requireObject(request);String id=identifier(request,"command_id"),action=text(request,"action");
        JsonNode parameters=request.has("parameters")?request.get("parameters"):JSON.createObjectNode();
        if (!parameters.isObject()) throw new IllegalArgumentException("parameters 必须为 JSON 对象");
        return transaction(()->{
            var previous=first("SELECT * FROM component_commands WHERE id=?",id);
            if (previous!=null) {
                if (!action.equals(previous.get("action")) || !parameters.equals(parse((String)previous.get("parameters"))))
                    throw new IllegalArgumentException("同一任务编号不能用于不同动作或参数");
                return parse((String)previous.get("result"));
            }
            var state=state();
            String entryIssue=checkEntryCondition(parameters,state);
            // Workflow conditions only tighten these intrinsic device rules.
            // Every caller gets the same durable refusal, even without a guard.
            String intrinsicIssue=actionIssue(action,state.get("active_id")!=null);
            String issue=entryIssue!=null?entryIssue:intrinsicIssue;
            if(issue!=null) {
                sql("INSERT INTO component_commands(id,action,parameters,status,result) VALUES(?,?,?,'rejected','{}')",id,action,encode(parameters));
                return updateResult(id,issue);
            }
            String event=model.requestEvent(action);
            var step=engine.dispatch(event);
            if(step.transitions().isEmpty()) throw new IllegalStateException("SCXML 未执行已启用的动作请求迁移："+event);
            sql("INSERT INTO component_commands(id,action,parameters,status,result) VALUES(?,?,?,'accepted','{}')",id,action,encode(parameters));
            setLeaf();ObjectNode context=(ObjectNode)parse((String)state.get("context"));
            if (action.equals("parameter_dispatch")) {
                JsonNode version=context.path("configuration").path("version").deepCopy();
                context.putObject("configuration").put("status","pending").set("version",version);
            }
            saveContext(context);
            sql("UPDATE component_state SET active_id=?,last_id=? WHERE id=1",id,id);
            var detail=JSON.createObjectNode().put("task_accepted",true);detail.set("parameters",parameters);
            log(event,step,detail,id,null);return updateResult(id,null);
        });
    }
    /** Checked in the same monitor and transaction as command acceptance.
     * Existing command IDs are resolved before this check, preserving replay. */
    private String checkEntryCondition(JsonNode parameters,Map<String,Object> state) {
        if(!parameters.has("entry_condition")) return null;
        JsonNode entry=parameters.get("entry_condition");
        if(!entry.isObject() || !entry.path("idle").isBoolean() || !entry.path("idle").asBoolean())
            throw new IllegalArgumentException("entry_condition 必须要求 idle=true");
        var names=entry.fieldNames();
        while(names.hasNext()) if(!Set.of("states","outer_states","idle").contains(names.next()))
            throw new IllegalArgumentException("entry_condition 包含不支持的条件");
        for(String field:List.of("states","outer_states")) if(entry.has(field) && !entry.get(field).isTextual())
            throw new IllegalArgumentException("entry_condition 的状态集合必须是空格分隔的字符串");
        String states=entry.path("states").asText(""),outer=entry.path("outer_states").asText("");
        if(states.isBlank() && outer.isBlank()) throw new IllegalArgumentException("entry_condition 缺少状态条件");
        String leaf=(String)state.get("leaf");
        if(state.get("active_id")!=null) return "动作进入条件不满足：设备已有执行中的任务";
        if(!Arrays.asList(states.split("\\s+")).contains(leaf) && !Arrays.asList(outer.split("\\s+")).contains(model.outer(leaf)))
            return "动作进入条件不满足：当前状态为 "+leaf;
        return null;
    }
    public synchronized JsonNode complete(JsonNode request) {
        requireObject(request);String id=identifier(request,"command_id"),outcome=text(request,"outcome");
        if (!Set.of("success","failure").contains(outcome)) throw new IllegalArgumentException("outcome 必须是 success 或 failure");
        String qualification=request.path("data_qualification").asText("unknown");
        if (!Set.of("unknown","valid","invalid").contains(qualification)) throw new IllegalArgumentException("无效的数据资格");
        var payload=JSON.createObjectNode().put("outcome",outcome).put("data_qualification",qualification);
        payload.set("observed_outer_state",request.path("observed_outer_state").isMissingNode()?NullNode.instance:request.get("observed_outer_state"));
        return transaction(()->{
            var command=command(id);String status=(String)command.get("status"),action=(String)command.get("action");
            if (status.equals("interrupted")) return parse((String)command.get("result"));
            if (!status.equals("accepted")) {
                if (!payload.equals(parse((String)command.get("completion")))) throw new IllegalArgumentException("已完成任务不能改写反馈");
                return parse((String)command.get("result"));
            }
            var state=state();
            if (!id.equals(state.get("active_id"))) throw new IllegalArgumentException("反馈不是当前任务");
            JsonNode spec=model.action(action),event=spec.path(outcome.equals("success")?"success_events":"failure_events").get(engine.leaf());
            if (event==null) throw new IllegalArgumentException("反馈不对应当前状态");
            JsonNode observation=spec.path(outcome+"_observation");
            if (observation.isObject()) observation=observation.path(engine.leaf());
            if (!observation.isMissingNode() && !observation.isNull() && !observation.equals(payload.get("observed_outer_state")))
                throw new IllegalArgumentException("反馈缺少要求的设备状态确认");
            var step=event.isNull()?new ScxmlEngine.Step(engine.leaf(),engine.leaf(),List.of(),List.of(),List.of()):engine.dispatch(event.asText());
            if (!event.isNull() && step.transitions().isEmpty()) throw new IllegalArgumentException("当前状态未启用反馈事件");
            sql("UPDATE component_commands SET status=?,key_state=?,completion=? WHERE id=?",outcome.equals("success")?"succeeded":"failed",
                spec.path(outcome+"_key").asText(),encode(payload),id);
            setLeaf();ObjectNode context=(ObjectNode)parse((String)state.get("context"));
            if (action.equals("power_on_check")) context.put("power_status",outcome.equals("success")?"on":"unknown");
            if (action.equals("shutdown") && outcome.equals("success")) context.put("power_status","off");
            if (action.equals("abort_reset") && outcome.equals("success")) { context.putArray("faults");context.putNull("fault_origin"); }
            if (action.equals("parameter_dispatch")) {
                var config=context.putObject("configuration").put("status",outcome.equals("success")?"valid":"invalid");
                config.put("version",outcome.equals("success")?id:null);
            }
            invalidateConfiguration(context);saveContext(context);
            sql("UPDATE component_state SET active_id=NULL,last_result_id=? WHERE id=1",id);
            log(event.isNull()?action+"."+outcome:event.asText(),step,payload,id,null);return updateResult(id,null);
        });
    }
    public synchronized JsonNode simulate(JsonNode request) {
        requireObject(request);String id=identifier(request,"command_id"),outcome=text(request,"outcome");
        JsonNode normalized=request.deepCopy();
        if (!normalized.has("observed_outer_state")) {
            try {
                JsonNode observation=model.action((String)command(id).get("action")).path(outcome+"_observation");
                if (observation.isObject()) observation=observation.path(engine.leaf());
                ((ObjectNode)normalized).set("observed_outer_state",observation.isMissingNode()?NullNode.instance:observation);
            } catch (SQLException ex) { throw new IllegalStateException(ex); }
        }
        return complete(normalized);
    }
    public synchronized JsonNode reportEvent(JsonNode request) {
        requireObject(request);String id=identifier(request,"event_id"),event=text(request,"event");
        var payload=JSON.createObjectNode().put("event",event);
        payload.set("observed_outer_state",request.path("observed_outer_state"));
        return transaction(()->{
            var previous=first("SELECT * FROM component_receipts WHERE id=?",id);
            if (previous!=null) {
                if (!payload.equals(parse((String)previous.get("payload")))) throw new IllegalArgumentException("同一事件编号不能用于不同事件");
                return parse((String)previous.get("result"));
            }
            var state=state();JsonNode spec=model.confirmation(event);boolean allowed=false;
            for (JsonNode outer:spec.path("source_outer")) if (outer.asText().equals(model.outer(engine.leaf()))) allowed=true;
            if (!allowed || !spec.path("observation").equals(payload.path("observed_outer_state"))) throw new IllegalArgumentException("确认事件与设备状态不匹配");
            var step=engine.dispatch(spec.path("machine_event").asText());
            if (step.transitions().isEmpty()) throw new IllegalArgumentException("当前状态未启用确认事件");
            setLeaf();ObjectNode context=(ObjectNode)parse((String)state.get("context"));
            JsonNode fault=model.metadata.path("fault_codes").get(event);
            if (fault!=null) {
                Set<String> faults=new TreeSet<>();for (JsonNode value:context.path("faults")) faults.add(value.asText());faults.add(fault.asText());
                context.set("faults",JSON.valueToTree(faults));
            } else if (event.equals("recovery_confirmed")) context.putArray("faults");
            if (event.equals("qualification_confirmed")) context.put("power_status","on");
            if (Set.of("shutdown_confirmed","shutdown_while_fault_confirmed").contains(event)) context.put("power_status","off");
            invalidateConfiguration(context);saveContext(context);String active=(String)state.get("active_id");
            if (active!=null) {
                sql("UPDATE component_commands SET status='interrupted',key_state='任务中断' WHERE id=?",active);
                sql("UPDATE component_state SET active_id=NULL,last_result_id=? WHERE id=1",active);
                updateResult(active,spec.path("label").asText());
            }
            log(spec.path("machine_event").asText(),step,payload,active,id);
            var result=JSON.createObjectNode().put("event_id",id).put("event",event).put("interrupted_command_id",active);
            result.set("snapshot",snapshotValue());
            sql("INSERT INTO component_receipts VALUES(?,?,?)",id,encode(payload),encode(result));return result;
        });
    }
    public synchronized JsonNode history(int limit) {
        if (limit<1 || limit>1000) throw new IllegalArgumentException("limit 必须为 1–1000");
        return transaction(()->{
            var records=rows("SELECT * FROM component_events ORDER BY sequence DESC LIMIT ?",limit);Collections.reverse(records);
            var result=JSON.createArrayNode();
            for (var row:records) {var value=(ObjectNode)JSON.valueToTree(row);value.set("detail",parse((String)row.get("detail")));result.add(value);}
            return result;
        });
    }
    @Override public synchronized void close() {
        try { db.close(); } catch (SQLException ex) { throw new IllegalStateException(ex); }
    }
}
