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
    private final RepositoryService repository;
    private final ManagementService management;
    private final DeviceAdapter adapter;
    private final boolean simulation;
    private final ModelRepository models;
    private final ObjectMapper json;
    private final StartupChecks startup;
    public RunService(JdbcTemplate db,RuntimeService runtime,TaskService tasks,HistoryService history,RepositoryService repository,ManagementService management,
                      DeviceAdapter adapter,ModelRepository models,ObjectMapper json,StartupChecks startup,@Value("${control.simulation-controls:true}") boolean simulation) {
        this.db=db;this.runtime=runtime;this.tasks=tasks;this.history=history;this.repository=repository;this.management=management;this.adapter=adapter;this.models=models;this.json=json;this.simulation=simulation;
        this.startup=startup;
    }
    @Transactional
    public Map<String,Object> start(String requestId) { return start(requestId,Map.of()); }
    @Transactional
    public Map<String,Object> start(String requestId,Map<String,Map<String,Object>> parameters) {
        return create(requestId,parameters,false);
    }
    @Transactional
    public Map<String,Object> prepareRestart(String requestId) {
        if(!simulation)throw new IllegalStateException("simulation controls disabled");
        return create(requestId,Map.of(),true);
    }
    private Map<String,Object> create(String requestId,Map<String,Map<String,Object>> parameters,boolean preparation) {
        if(requestId==null||!requestId.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("requestId must be 1..64 safe characters");
        String previous=db.queryForObject("SELECT run_id FROM device_lease WHERE id=1 FOR UPDATE",String.class);
        var exists=db.queryForList("SELECT id FROM experiment_run WHERE id=?",requestId);
        if(!exists.isEmpty()) {
            String kind=db.queryForObject("SELECT kind FROM experiment_run WHERE id=?",String.class,requestId);
            if(!kind.equals(preparation?"PREPARATION":"EXPERIMENT"))throw new IllegalArgumentException("requestId reused for another operation");
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
        var model=models.current();var overrides=json.valueToTree(parameters);if(!preparation)model.validateParameters(overrides);
        String processKey=preparation?"laser_prepare_restart":model.processId();
        var issues=startup.issues(model,processKey);
        if(!issues.isEmpty())throw new IllegalStateException((preparation?"尚不能重新准备：":"尚不能开始新实验：")+String.join("；",issues)+"。请先恢复设备的启动条件，再重试。");
        ObjectNode frozen=(ObjectNode)model.json();frozen.set("run_parameters",overrides);
        String snapshot=models.serialize(new ControlModel(frozen));
        db.update("INSERT INTO experiment_run(id,outcome,model_snapshot,model_hash,kind,source_run_id) VALUES(?,'RUNNING',?,?,?,?)",requestId,snapshot,models.hash(snapshot),preparation?"PREPARATION":"EXPERIMENT",preparation?previous:null);
        db.update("UPDATE device_lease SET run_id=? WHERE id=1",requestId);
        Map<String,Object> vars=model.variables();vars.put("compensationRequired",false);
        var process=runtime.startProcessInstanceByKey(processKey,requestId,vars);
        db.update("UPDATE experiment_run SET process_id=? WHERE id=?",process.getId(),requestId);
        return view(requestId);
    }
    public Map<String,Object> view(String id) {
        var rows=db.queryForList("SELECT id,process_id,outcome,reason,failure_detail,created_at,model_hash,kind,source_run_id FROM experiment_run WHERE id=?",id);
        if(rows.isEmpty()) throw new IllegalArgumentException("unknown run");
        Map<String,Object> view=new LinkedHashMap<>(rows.get(0));String pid=(String)view.get("PROCESS_ID");
        var commands=db.queryForList("SELECT id,node_id,device_id,action,status,error,contract_ref,CAST(params_json AS VARCHAR) AS parameters,CAST(result_json AS VARCHAR) AS result_json FROM device_command WHERE run_id=? ORDER BY created_at,id",id);
        for(var command:commands) {
            Object payload=command.remove("RESULT_JSON");
            if(payload!=null)try { command.put("RESULT_STATUS",json.readTree(payload.toString()).path("status").asText()); }
            catch(java.io.IOException ignored) { command.put("RESULT_STATUS","invalid"); }
        }
        view.put("commands",commands);
        view.put("simulationEnabled",simulation);
        if(pid!=null) {
            view.put("deadlines",management.createTimerJobQuery().processInstanceId(pid).list().stream()
                .filter(job->job.getDuedate()!=null).map(job->Map.of("activity",job.getElementId(),"due",job.getDuedate())).toList());
            view.put("activeActivities",runtime.createProcessInstanceQuery().processInstanceId(pid).count()==0?List.of():runtime.getActiveActivityIds(pid));
            view.put("userTasks",tasks.createTaskQuery().processInstanceId(pid).list().stream().map(t->Map.of("id",t.getId(),"name",t.getName())).toList());
            var activities=history.createHistoricActivityInstanceQuery().processInstanceId(pid).orderByHistoricActivityInstanceStartTime().asc().list();
            var historicRun=history.createHistoricProcessInstanceQuery().processInstanceId(pid).singleResult();
            if(historicRun!=null)view.put("steps",RunPresentation.steps(repository.getBpmnModel(historicRun.getProcessDefinitionId()),activities,(String)view.get("OUTCOME")));
            view.put("history",activities.stream()
                .map(a->{Map<String,Object> m=new LinkedHashMap<>();m.put("activity",a.getActivityId());m.put("name",a.getActivityName());m.put("type",a.getActivityType());m.put("started",a.getStartTime());m.put("ended",a.getEndTime());return m;}).toList());
        }
        return view;
    }
    public List<Map<String,Object>> recent() {
        return db.queryForList("SELECT id,outcome,created_at,kind FROM experiment_run ORDER BY created_at DESC,id DESC LIMIT 20");
    }
    public Map<String,Object> startupChecks() {
        String previous=db.queryForObject("SELECT run_id FROM device_lease WHERE id=1",String.class);
        if(previous!=null) {
            String pid=db.queryForObject("SELECT process_id FROM experiment_run WHERE id=?",String.class,previous);
            if(pid!=null&&runtime.createProcessInstanceQuery().processInstanceId(pid).count()>0)
                return Map.of("ready",false,"issues",List.of("设备正由实验 "+previous+" 使用，请先查看或完成该实验"));
            if(db.queryForObject("SELECT COUNT(*) FROM device_command WHERE run_id=? AND status IN ('QUEUED','SENT','RESULT','CANCEL_REQUESTED')",Integer.class,previous)>0)
                return Map.of("ready",false,"issues",List.of("设备任务尚在处理，请等待任务或取消确认完成"));
        }
        var model=models.current();var issues=startup.issues(model);
        boolean preparationAvailable=simulation&&!issues.isEmpty()&&startup.issues(model,"laser_prepare_restart").isEmpty();
        return Map.of("ready",issues.isEmpty(),"issues",issues,"preparationAvailable",preparationAvailable);
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
