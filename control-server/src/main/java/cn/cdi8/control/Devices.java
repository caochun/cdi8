package cn.cdi8.control;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.flowable.engine.delegate.BpmnError;
import org.flowable.engine.delegate.DelegateExecution;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component("devices")
public class Devices {
    public static final List<String> SEEDS = List.of("seed_01","seed_02","seed_03");
    public static final List<String> ALL = List.of("seed_01","seed_02","seed_03","shg_01");
    private final JdbcTemplate db;
    private final DeviceAdapter adapter;
    private final ObjectMapper json;
    public Devices(JdbcTemplate db,DeviceAdapter adapter,ObjectMapper json) { this.db=db;this.adapter=adapter;this.json=json; }

    public static String expected(String action) {
        return switch(action) {
            case "power_on_self_test" -> "自检完成";
            case "function_check" -> "功能检查完成";
            case "parameter_dispatch" -> "参数下发完成";
            case "seed_source_emit", "frequency_doubled_emit" -> "出光完成";
            case "laser_parameter_collect" -> "采集完成";
            case "standby_reset", "abort_reset" -> "复位/待机完成";
            case "shutdown" -> "关机完成";
            default -> throw new IllegalArgumentException("Unknown action: "+action);
        };
    }
    public void prepare(DelegateExecution e,String group,String action) {
        try {
            for (String d : group.equals("seed")?SEEDS:List.of("shg_01")) {
                var s=adapter.snapshot(d);
                if (s.get("active_action")!=null) throw new IllegalStateException(d+" busy");
                String current=String.valueOf(s.get("current_state"));
                String main=String.valueOf(s.get("main_state"));
                boolean ok=switch(action) {
                    case "power_on_self_test" -> main.equals("未就绪") && Set.of("未上电/离线","关机完成").contains(current);
                    case "function_check" -> current.equals("自检完成") && Set.of("未就绪","正常","异常").contains(main);
                    case "parameter_dispatch" -> Set.of("功能检查完成","采集完成").contains(current) && Set.of("就绪","正常").contains(main);
                    case "seed_source_emit", "frequency_doubled_emit" -> current.equals("参数下发完成") && main.equals("正常");
                    case "laser_parameter_collect" -> current.equals("出光完成") && main.equals("正常");
                    case "standby_reset" -> current.equals("采集完成") && main.equals("正常");
                    case "shutdown", "abort_reset" -> true;
                    default -> false;
                };
                if(!ok) throw new IllegalStateException(d+" precondition: "+main+"/"+current);
            }
            // Re-check cross-system validity at each emission dispatch, not only historical gate success.
            if(action.equals("seed_source_emit")) checkConfigured(ALL);
            if(action.equals("frequency_doubled_emit")) {
                checkConfigured(List.of("shg_01"));
                for(String d:SEEDS) {
                    var s=adapter.snapshot(d);
                    if(!"正常".equals(s.get("main_state"))||!"出光".equals(s.get("business_state")))
                        throw new IllegalStateException(d+" emission is no longer valid");
                }
            }
        } catch(Exception ex) { throw new BpmnError("DEVICE_ERROR",ex.getMessage()); }
    }
    private void checkConfigured(List<String> targets) {
        for(String d:targets) {
            var s=adapter.snapshot(d);
            if(!"正常".equals(s.get("main_state"))||!"参数下发完成".equals(s.get("current_state"))||!"就绪".equals(s.get("business_state"))||s.get("active_action")!=null)
                throw new IllegalStateException(d+" joint gate blocked");
        }
    }
    public void gate(DelegateExecution e) {
        try { checkConfigured(ALL); e.setVariable("jointReady",true); }
        catch(Exception ex) { e.setVariable("jointReady",false);e.setVariable("failureReason",ex.getMessage()); }
    }
    public void enqueue(DelegateExecution e,String node,String action,String device) {
        String id=UUID.randomUUID().toString();
        db.update("INSERT INTO device_command(id,run_id,execution_id,node_id,device_id,action,status) VALUES(?,?,?,?,?,?,?)",
            id,e.getProcessInstanceBusinessKey(),e.getId(),node,device,action,"QUEUED");
        e.setVariableLocal("commandId",id);
    }
    public void verify(DelegateExecution e,String action) {
        String id=(String)e.getVariable("commandId");
        try {
            String payload=db.queryForObject("SELECT result_json FROM device_command WHERE id=?",String.class,id);
            var result=json.readValue(payload,new TypeReference<Map<String,Object>>(){});
            @SuppressWarnings("unchecked") var s=(Map<String,Object>)result.get("snapshot");
            boolean ok=id.equals(result.get("command_id")) && "succeeded".equals(result.get("status"))
                && "succeeded".equals(s.get("task_state")) && expected(action).equals(s.get("current_state"))
                && !"异常".equals(s.get("main_state")) && s.get("active_action")==null;
            String main=switch(action) {
                case "power_on_self_test","shutdown" -> "未就绪";
                case "function_check","standby_reset","abort_reset" -> "就绪";
                default -> "正常";
            };
            String business=switch(action) {
                case "power_on_self_test","shutdown","abort_reset" -> "未就绪";
                case "seed_source_emit","frequency_doubled_emit","laser_parameter_collect" -> "出光";
                default -> "就绪";
            };
            ok=ok && action.equals(result.get("action")) && main.equals(s.get("main_state")) && business.equals(s.get("business_state"));
            if(!ok) throw new IllegalStateException("device result failed: "+id);
        } catch(Exception ex) { throw new BpmnError("DEVICE_ERROR",ex.getMessage()); }
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
    public void success(DelegateExecution e) {
        try {
            for(String d:ALL) {
                var s=adapter.snapshot(d);
                if(!"关机完成".equals(s.get("current_state")) || !"未就绪".equals(s.get("business_state")))
                    throw new IllegalStateException(d+" is not shutdown");
            }
        } catch(Exception ex) { throw new BpmnError("DEVICE_ERROR",ex.getMessage()); }
        db.update("UPDATE experiment_run SET outcome='SUCCEEDED' WHERE id=?",e.getProcessInstanceBusinessKey());
    }
}
