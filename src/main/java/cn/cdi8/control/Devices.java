package cn.cdi8.control;

import cn.cdi8.control.model.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.flowable.engine.delegate.BpmnError;
import org.flowable.engine.delegate.DelegateExecution;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Generic BPMN bridge: business conditions live in the frozen control model. */
@Component("devices")
public class Devices {
    private final JdbcTemplate db;
    private final DeviceAdapter adapter;
    private final ObjectMapper json;
    private final ModelRepository models;
    public Devices(JdbcTemplate db,DeviceAdapter adapter,ObjectMapper json,ModelRepository models) {
        this.db=db;this.adapter=adapter;this.json=json;this.models=models;
    }
    private Map<String,Object> snapshot(ControlModel model,String id) {
        return adapter.snapshot(model.device(id).get("adapter_id").asText());
    }
    public void prepare(DelegateExecution e,String nodeId) {
        try {
            var model=models.forRun(e.getProcessInstanceBusinessKey());var node=model.node(nodeId);
            for(String id:model.group(node.get("group").asText())) {
                var contract=model.contract(model.contractRef(nodeId,id));
                String mismatch=Conditions.mismatch(contract.get("precondition"),snapshot(model,id));
                if(!mismatch.isEmpty())
                    throw new IllegalStateException(id+" 启动动作的条件不满足："+mismatch);
            }
            if(node.has("guard")&&!model.condition(node.get("guard").asText(),id->snapshot(model,id)))
                throw new IllegalStateException(nodeId+" guard failed: "+node.get("guard").asText());
        }catch(Exception ex){throw failure(e,ex);}
    }
    public boolean gate(DelegateExecution e,String conditionRef) {
        try {
            var model=models.forRun(e.getProcessInstanceBusinessKey());
            boolean ready=model.condition(conditionRef,id->snapshot(model,id));
            if(!ready)e.setVariable("failureReason","condition failed: "+conditionRef);
            return ready;
        }catch(Exception ex){e.setVariable("failureReason",ex.getMessage());return false;}
    }
    public void enqueue(DelegateExecution e,String nodeId,String device) {
        try {
            var model=models.forRun(e.getProcessInstanceBusinessKey());String ref=model.contractRef(nodeId,device);
            var contract=model.contract(ref);var params=model.parameters(nodeId,device,model.json().path("run_parameters"));
            String id=UUID.randomUUID().toString();
            db.update("INSERT INTO device_command(id,run_id,execution_id,node_id,device_id,action,status,contract_ref,contract_json,params_json,adapter_id) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                id,e.getProcessInstanceBusinessKey(),e.getId(),nodeId,device,contract.get("action").asText(),"QUEUED",ref,
                json.writeValueAsString(contract),json.writeValueAsString(params),model.device(device).get("adapter_id").asText());
            e.setVariableLocal("commandId",id);
        }catch(Exception ex){throw failure(e,ex);}
    }
    public void verify(DelegateExecution e,String nodeId) {
        try {
            String id=(String)e.getVariable("commandId");
            var row=db.queryForMap("SELECT run_id,node_id,device_id,action,contract_ref,CAST(contract_json AS VARCHAR) AS contract_json,CAST(result_json AS VARCHAR) AS result_json FROM device_command WHERE id=?",id);
            var result=json.readValue((String)row.get("RESULT_JSON"),new TypeReference<Map<String,Object>>(){});
            var contract=json.readTree((String)row.get("CONTRACT_JSON"));
            Object raw=result.get("snapshot");
            if(!e.getProcessInstanceBusinessKey().equals(row.get("RUN_ID"))||!nodeId.equals(row.get("NODE_ID"))
                ||!id.equals(result.get("command_id"))||!row.get("ACTION").equals(result.get("action"))
                ||!"succeeded".equals(result.get("status"))||!(raw instanceof Map))
                throw new IllegalStateException("result correlation or status failed: "+id);
            @SuppressWarnings("unchecked") var state=(Map<String,Object>)raw;
            if(!Conditions.matches(contract.get("completion"),state))throw new IllegalStateException("completion contract failed: "+row.get("CONTRACT_REF"));
        }catch(Exception ex){throw failure(e,ex);}
    }
    public void terminate(DelegateExecution e,String reason) {
        String run=e.getProcessInstanceBusinessKey();
        db.update("UPDATE experiment_run SET outcome='FAILED',reason=? WHERE id=?",reason,run);
        db.update("UPDATE device_command SET status='CANCEL_REQUESTED' WHERE run_id=? AND status IN ('QUEUED','SENT','RESULT')",run);
        e.setVariable("failureReason",reason);
    }
    public void interlock(DelegateExecution e) {
        terminate(e,"INTERLOCK"); e.setVariable("compensationRequired",true);
    }
    public void compensated(DelegateExecution e) { e.setVariable("compensationRequired",false); }
    public void compensationFailed(DelegateExecution e) {
        terminate(e,"COMPENSATION_FAILED");e.setVariable("compensationRequired",true);
    }
    public void success(DelegateExecution e,String conditionRef) {
        try {
            var model=models.forRun(e.getProcessInstanceBusinessKey());
            if(!model.condition(conditionRef,id->snapshot(model,id)))throw new IllegalStateException("final condition failed: "+conditionRef);
        }catch(Exception ex){throw failure(e,ex);}
        db.update("UPDATE experiment_run SET outcome='SUCCEEDED' WHERE id=?",e.getProcessInstanceBusinessKey());
    }
    private BpmnError failure(DelegateExecution e,Exception ex) {
        String detail=Objects.toString(ex.getMessage(),ex.getClass().getSimpleName());
        db.update("UPDATE experiment_run SET failure_detail=? WHERE id=?",detail.substring(0,Math.min(2000,detail.length())),e.getProcessInstanceBusinessKey());
        return new BpmnError("DEVICE_ERROR",detail);
    }
}
