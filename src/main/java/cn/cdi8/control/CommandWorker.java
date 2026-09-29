package cn.cdi8.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.flowable.engine.RuntimeService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable outbox + polling inbox. An HTTP retry reuses the same command ID. */
@Component
public class CommandWorker {
    private final JdbcTemplate db;
    private final DeviceAdapter adapter;
    private final RuntimeService runtime;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final boolean enabled;
    public CommandWorker(JdbcTemplate db,DeviceAdapter adapter,RuntimeService runtime,ObjectMapper json,
                         TransactionTemplate tx,@Value("${control.worker-enabled:true}") boolean enabled) {
        this.db=db;this.adapter=adapter;this.runtime=runtime;this.json=json;this.tx=tx;this.enabled=enabled;
    }
    @Scheduled(fixedDelayString="${control.poll-ms:500}")
    public void scheduled() { if(enabled) tick(); }
    public synchronized void tick() {
        for(var row:db.queryForList("SELECT * FROM device_command WHERE status IN ('QUEUED','SENT','RESULT','CANCEL_REQUESTED') ORDER BY created_at,id")) {
            String id=(String)row.get("ID"),device=(String)(row.get("ADAPTER_ID")==null?row.get("DEVICE_ID"):row.get("ADAPTER_ID"));
            try {
                String status=db.queryForObject("SELECT status FROM device_command WHERE id=?",String.class,id);
                if(status.equals("CANCEL_REQUESTED")) {
                    adapter.cancel(device,id);
                    db.update("UPDATE device_command SET status='CANCELLED',error=NULL WHERE id=? AND status='CANCEL_REQUESTED'",id);
                    continue;
                }
                if(status.equals("QUEUED")) {
                    String parameterJson=db.queryForObject("SELECT params_json FROM device_command WHERE id=?",String.class,id);
                    Map<String,Object> parameters=parameterJson==null?Map.of():json.readValue(parameterJson,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
                    adapter.send(device,id,(String)row.get("ACTION"),parameters);
                    db.update("UPDATE device_command SET status='SENT',error=NULL WHERE id=? AND status='QUEUED'",id);
                    status="SENT";
                }
                if(status.equals("SENT")) {
                    var result=adapter.result(device,id);
                    if(!id.equals(result.get("command_id"))) throw new IllegalStateException("wrong command correlation");
                    if(Set.of("succeeded","failed","cancelled","rejected").contains(result.get("status"))) {
                        db.update("UPDATE device_command SET status='RESULT',result_json=?,error=NULL WHERE id=? AND status='SENT'",json.writeValueAsString(result),id);
                    }
                }
                tx.executeWithoutResult(ignored->{
                    String locked=db.queryForObject("SELECT status FROM device_command WHERE id=? FOR UPDATE",String.class,id);
                    if(!locked.equals("RESULT")) return;
                    String execution=(String)row.get("EXECUTION_ID");
                    var waiting=runtime.createExecutionQuery().executionId(execution).singleResult();
                    if(waiting==null) {
                        db.update("UPDATE device_command SET status='IGNORED' WHERE id=?",id);return;
                    }
                    if(!waiting.getActivityId().endsWith("_wait")) return;
                    db.update("UPDATE device_command SET status='DELIVERED' WHERE id=?",id);
                    runtime.trigger(execution); // Result verification and BPMN continuation share this transaction.
                });
            } catch(Exception ex) {
                db.update("UPDATE device_command SET error=? WHERE id=?",ex.getMessage()==null?ex.toString():ex.getMessage().substring(0,Math.min(1900,ex.getMessage().length())),id);
            }
        }
    }
}
