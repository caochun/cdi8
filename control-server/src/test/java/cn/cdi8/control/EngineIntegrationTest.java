package cn.cdi8.control;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.flowable.engine.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:engine_test;DB_CLOSE_DELAY=-1","flowable.async-executor-activate=false","control.worker-enabled=false","logging.level.root=WARN","logging.level.org.springframework=WARN"})
class EngineIntegrationTest {
    @Autowired RunService runs;
    @Autowired RuntimeService runtime;
    @Autowired ManagementService management;
    @Autowired CommandWorker worker;
    @Autowired JdbcTemplate db;
    @Autowired FakeAdapter adapter;

    @TestConfiguration static class Config {
        @Bean @Primary FakeAdapter fakeAdapter(){return new FakeAdapter();}
    }
    static class FakeAdapter implements DeviceAdapter {
        Map<String,Map<String,Object>> states=new ConcurrentHashMap<>();
        Map<String,Map<String,Object>> results=new ConcurrentHashMap<>();
        int sends;
        FakeAdapter(){reset();}
        void reset(){states.clear();results.clear();sends=0;for(String d:Devices.ALL)states.put(d,new LinkedHashMap<>(Map.of("main_state","未就绪","current_state","未上电/离线","business_state","未就绪","task_state","idle")));}
        public Map<String,Object> snapshot(String d){return new LinkedHashMap<>(states.get(d));}
        public Map<String,Object> send(String d,String id,String action){
            if(results.containsKey(id))return results.get(id);
            sends++;var state=states.get(d);state.put("active_action",action);state.put("task_id",id);state.put("task_state","executing");
            var result=new LinkedHashMap<String,Object>();result.put("command_id",id);result.put("action",action);result.put("status","accepted");result.put("snapshot",snapshot(d));results.put(id,result);return result;
        }
        public Map<String,Object> result(String d,String id){return results.get(id);}
        public void cancel(String d,String id){if(results.containsKey(id))results.get(id).put("status","cancelled");states.get(d).remove("active_action");states.get(d).put("task_state","cancelled");}
        public void simulate(String d,String id,String outcome){
            var result=results.get(id);if(!result.get("status").equals("accepted"))return;
            String action=(String)result.get("action");var state=states.get(d);state.remove("active_action");
            if(outcome.equals("success")){
                state.put("current_state",Devices.expected(action));state.put("task_state","succeeded");
                state.put("main_state",switch(action){case "power_on_self_test","shutdown"->"未就绪";case "function_check","standby_reset","abort_reset"->"就绪";default->"正常";});
                state.put("business_state",switch(action){case "seed_source_emit","frequency_doubled_emit","laser_parameter_collect"->"出光";case "power_on_self_test","shutdown","abort_reset"->"未就绪";default->"就绪";});
                result.put("status","succeeded");
            }else {state.put("main_state","异常");state.put("business_state","异常");state.put("task_state","failed");result.put("status","failed");}
            result.put("snapshot",snapshot(d));
        }
    }
    @BeforeEach void clean(){
        for(var p:runtime.createProcessInstanceQuery().list())runtime.deleteProcessInstance(p.getId(),"test cleanup");
        db.update("DELETE FROM device_command");db.update("DELETE FROM experiment_run");db.update("UPDATE device_lease SET run_id=NULL");adapter.reset();
    }
    List<Map<String,Object>> pending(){return db.queryForList("SELECT * FROM device_command WHERE status='SENT' ORDER BY id");}
    void successBatch(){worker.tick();var pending=pending();assertFalse(pending.isEmpty());for(var c:pending)runs.simulate((String)c.get("RUN_ID"),(String)c.get("ID"),"success");worker.tick();}
    @Test void realFlowableWaitsForAllThreeAndCompletesFullCycle(){
        var start=runs.start("golden");assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM device_command",Integer.class));
        assertEquals(0,adapter.sends);worker.tick();assertEquals(3,adapter.sends);
        var one=pending().get(0);runs.simulate("golden",(String)one.get("ID"),"success");worker.tick();
        assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM device_command",Integer.class));
        for(var c:pending())runs.simulate("golden",(String)c.get("ID"),"success");worker.tick();
        for(int i=0;i<13;i++)successBatch();
        assertEquals("SUCCEEDED",runs.view("golden").get("OUTCOME"));assertEquals(28,adapter.sends);
        assertEquals(0,runtime.createProcessInstanceQuery().count());
        for(String d:Devices.ALL)assertEquals("关机完成",adapter.snapshot(d).get("current_state"));
        runs.start("next");successBatch();assertEquals("RUNNING",runs.view("next").get("OUTCOME"));
    }
    @Test void duplicateStartDoesNotDispatchTwiceAndAnotherRunIsLocked(){
        var first=runs.start("same");assertEquals(first.get("PROCESS_ID"),runs.start("same").get("PROCESS_ID"));
        assertThrows(IllegalStateException.class,()->runs.start("other"));worker.tick();worker.tick();assertEquals(3,adapter.sends);
    }
    @Test void failurePropagatesFromOneMultiInstanceAndCancelsPeers(){
        runs.start("bad");worker.tick();var row=pending().get(0);runs.simulate("bad",(String)row.get("ID"),"failure");worker.tick();worker.tick();
        assertEquals("FAILED",runs.view("bad").get("OUTCOME"));assertEquals(0,runtime.createProcessInstanceQuery().count());
        assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM device_command",Integer.class));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM device_command WHERE status='CANCELLED'",Integer.class));
    }
    @Test void flowableBoundaryTimerPreventsLateSuccess(){
        var run=runs.start("timeout");worker.tick();
        var timer=management.createTimerJobQuery().processInstanceId((String)run.get("PROCESS_ID")).singleResult();assertNotNull(timer);
        management.executeJob(management.moveTimerToExecutableJob(timer.getId()).getId());worker.tick();
        assertEquals("FAILED",runs.view("timeout").get("OUTCOME"));
        var id=db.queryForObject("SELECT id FROM device_command LIMIT 1",String.class);
        assertThrows(IllegalArgumentException.class,()->runs.simulate("timeout",id,"success"));
    }
    @Test void interlockCreatesUserTaskAndSerialCompensationKeepsFailure(){
        runs.start("interlock");worker.tick();runs.interlock("interlock");
        assertEquals("FAILED",runs.view("interlock").get("OUTCOME"));
        assertThrows(IllegalStateException.class,()->runs.compensate("interlock"));worker.tick();runs.compensate("interlock");
        for(int i=0;i<4;i++){worker.tick();assertEquals(1,pending().size());successBatch();}
        assertEquals("FAILED",runs.view("interlock").get("OUTCOME"));assertEquals(0,runtime.createProcessInstanceQuery().count());
    }
    @Test void jointConditionsAreReadAgainBeforeEmission(){
        runs.start("stale");for(int i=0;i<5;i++)successBatch();worker.tick();
        adapter.states.get("seed_03").put("main_state","异常");
        for(var c:pending())runs.simulate("stale",(String)c.get("ID"),"success");worker.tick();
        assertEquals("FAILED",runs.view("stale").get("OUTCOME"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM device_command WHERE action='seed_source_emit'",Integer.class));
    }
}
