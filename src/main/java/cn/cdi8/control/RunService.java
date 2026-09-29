package cn.cdi8.control;

import java.util.*;
import cn.cdi8.control.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.flowable.engine.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RunService {
    private final JdbcTemplate db;
    private final RuntimeService runtime;
    private final TaskService tasks;
    private final HistoryService history;
    private final DeviceAdapter adapter;
    private final boolean simulation;
    private final ModelRepository models;
    private final ObjectMapper json;
    public RunService(JdbcTemplate db,RuntimeService runtime,TaskService tasks,HistoryService history,
                      DeviceAdapter adapter,ModelRepository models,ObjectMapper json,@Value("${control.simulation-controls:true}") boolean simulation) {
        this.db=db;this.runtime=runtime;this.tasks=tasks;this.history=history;this.adapter=adapter;this.models=models;this.json=json;this.simulation=simulation;
    }
    @Transactional
    public Map<String,Object> start(String requestId) { return start(requestId,Map.of()); }
    @Transactional
    public Map<String,Object> start(String requestId,Map<String,Map<String,Object>> parameters) {
        if(requestId==null||!requestId.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("requestId must be 1..64 safe characters");
        String previous=db.queryForObject("SELECT run_id FROM device_lease WHERE id=1 FOR UPDATE",String.class);
        var exists=db.queryForList("SELECT id FROM experiment_run WHERE id=?",requestId);
        if(!exists.isEmpty()) {
            var saved=models.forRun(requestId).json().path("run_parameters");
            if(!saved.equals(json.valueToTree(parameters)))throw new IllegalArgumentException("requestId reused with different parameters");
            return view(requestId);
        }
        if(previous!=null) {
            var prev=db.queryForMap("SELECT id,process_id,outcome,reason,created_at,model_hash FROM experiment_run WHERE id=?",previous);
            if(runtime.createProcessInstanceQuery().processInstanceId((String)prev.get("PROCESS_ID")).count()>0)
                throw new IllegalStateException("devices are reserved by "+previous);
            if(db.queryForObject("SELECT COUNT(*) FROM device_command WHERE run_id=? AND status IN ('QUEUED','SENT','RESULT','CANCEL_REQUESTED')",Integer.class,previous)>0)
                throw new IllegalStateException("device commands still settling");
        }
        var model=models.current();var overrides=json.valueToTree(parameters);model.validateParameters(overrides);
        ObjectNode frozen=(ObjectNode)model.json();frozen.set("run_parameters",overrides);
        String snapshot=models.serialize(new ControlModel(frozen));
        db.update("INSERT INTO experiment_run(id,outcome,model_snapshot,model_hash) VALUES(?,'RUNNING',?,?)",requestId,snapshot,models.hash(snapshot));
        db.update("UPDATE device_lease SET run_id=? WHERE id=1",requestId);
        Map<String,Object> vars=model.variables();vars.put("compensationRequired",false);
        var process=runtime.startProcessInstanceByKey(model.processId(),requestId,vars);
        db.update("UPDATE experiment_run SET process_id=? WHERE id=?",process.getId(),requestId);
        return view(requestId);
    }
    public Map<String,Object> view(String id) {
        var rows=db.queryForList("SELECT id,process_id,outcome,reason,created_at,model_hash FROM experiment_run WHERE id=?",id);
        if(rows.isEmpty()) throw new IllegalArgumentException("unknown run");
        Map<String,Object> view=new LinkedHashMap<>(rows.get(0));String pid=(String)view.get("PROCESS_ID");
        view.put("commands",db.queryForList("SELECT id,node_id,device_id,action,status,error,contract_ref,CAST(params_json AS VARCHAR) AS parameters FROM device_command WHERE run_id=? ORDER BY created_at,id",id));
        if(pid!=null) {
            view.put("activeActivities",runtime.createProcessInstanceQuery().processInstanceId(pid).count()==0?List.of():runtime.getActiveActivityIds(pid));
            view.put("userTasks",tasks.createTaskQuery().processInstanceId(pid).list().stream().map(t->Map.of("id",t.getId(),"name",t.getName())).toList());
            view.put("history",history.createHistoricActivityInstanceQuery().processInstanceId(pid).orderByHistoricActivityInstanceStartTime().asc().list().stream()
                .map(a->{Map<String,Object> m=new LinkedHashMap<>();m.put("activity",a.getActivityId());m.put("type",a.getActivityType());m.put("started",a.getStartTime());m.put("ended",a.getEndTime());return m;}).toList());
        }
        return view;
    }
    @Transactional
    public void interlock(String id) {
        String pid=(String)view(id).get("PROCESS_ID");
        var subscriptions=runtime.createEventSubscriptionQuery().processInstanceId(pid).eventType("message").eventName("Interlock").list();
        if(subscriptions.size()!=1) throw new IllegalStateException("run is ended or already interlocked");
        runtime.messageEventReceived("Interlock",subscriptions.get(0).getExecutionId());
    }
    @Transactional
    public void compensate(String id) {
        String pid=(String)view(id).get("PROCESS_ID");
        if(db.queryForObject("SELECT COUNT(*) FROM device_command WHERE run_id=? AND status='CANCEL_REQUESTED'",Integer.class,id)>0)
            throw new IllegalStateException("wait for device cancellation acknowledgements");
        var list=tasks.createTaskQuery().processInstanceId(pid).taskDefinitionKey("approve_reset").list();
        if(list.size()!=1) throw new IllegalStateException("no compensation approval task");
        tasks.complete(list.get(0).getId());
    }
    public void simulate(String run,String task,String outcome) {
        if(!simulation) throw new IllegalStateException("simulation controls disabled");
        var rows=db.queryForList("SELECT COALESCE(adapter_id,device_id) AS device_id,status FROM device_command WHERE id=? AND run_id=?",task,run);
        if(rows.isEmpty()||!Set.of("SENT","RESULT","DELIVERED").contains(rows.get(0).get("STATUS")))
            throw new IllegalArgumentException("command not accepted or cancelled");
        if(!Set.of("success","failure","communication_error","fault_lock").contains(outcome)) throw new IllegalArgumentException("invalid simulation outcome");
        adapter.simulate((String)rows.get(0).get("DEVICE_ID"),task,outcome);
    }
}
