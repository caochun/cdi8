package cn.cdi8.workbench.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.*;
import org.flowable.engine.delegate.DelegateExecution;
import org.springframework.stereotype.Component;

/** Runtime holder for one shot's context, lifecycle stage and evidence. */
@Component("shotRuns")
public final class ShotRunRuntime {
    private static final Set<String> STAGES=Set.of("created","resources_reserved","initializing","initialized",
        "parameters_receiving","parameters_received","preparing","prepared","charging","triggering",
        "emission_confirmed","postprocessing","completed","failed","cancelled");
    private final ObjectMapper json;
    private final cn.cdi8.workbench.facility.FacilityStateRuntime facilityStates;
    public ShotRunRuntime(ObjectMapper json,cn.cdi8.workbench.facility.FacilityStateRuntime facilityStates){this.json=json;this.facilityStates=facilityStates;}

    public synchronized void open(DelegateExecution execution){
        if(execution.getVariable("shotRun")!=null)return;
        String shotId=Objects.toString(execution.getVariable("shotId"),"");
        if(!shotId.matches("[A-Za-z0-9_.:-]{1,128}"))throw new IllegalArgumentException("shotId 格式错误");
        ObjectNode run=json.createObjectNode().put("schema_version","1")
            .put("run_id",execution.getProcessInstanceId()).put("shot_id",shotId)
            .put("facility_definition",facilityStates.definition().id()).put("facility_binding",facilityStates.binding().id())
            .put("state_projection",facilityStates.projection().id()).put("stage","created");
        ObjectNode recipe=run.putObject("recipe");recipe.put("ref",Objects.toString(execution.getVariable("recipeRef"),"unassigned"));recipe.put("version",Objects.toString(execution.getVariable("recipeVersion"),"unassigned"));
        ObjectNode selection=run.putObject("resource_selection");ArrayNode beamlines=selection.putArray("beamlines");
        Object value=execution.getVariable("beamlines");
        if(!(value instanceof Collection<?> values)||values.isEmpty())throw new IllegalArgumentException("beamlines 至少包含一条束线");
        Set<Integer> seen=new HashSet<>();
        for(Object item:values) {
            int index=Integer.parseInt(item.toString());
            if(index<1||index>facilityStates.binding().topology().beamlineCount()||!seen.add(index))throw new IllegalArgumentException("beamlines 必须是 1–"+facilityStates.binding().topology().beamlineCount()+" 的不重复整数");
            beamlines.add(index);
        }
        String defaultSource=Objects.toString(execution.getVariable("defaultSource"),facilityStates.binding().topology().defaultSource());
        Object rawSelections=execution.getVariable("sourceSelections");
        if(!(rawSelections instanceof Map<?,?> selections))throw new IllegalArgumentException("sourceSelections 必须是束线编号到光源类型的对象");
        for(var entry:selections.entrySet()) {
            String key=entry.getKey().toString();int beam=Integer.parseInt(key);
            if(!Integer.toString(beam).equals(key)||!seen.contains(beam))throw new IllegalArgumentException("选源束线必须是本发束线的十进制编号（如 1）："+key);
            if(!(entry.getValue() instanceof String))throw new IllegalArgumentException("选源类型必须是字符串："+key);
        }
        for(int beam:seen) {
            String selected=Objects.toString(selections.get(Integer.toString(beam)),defaultSource);
            if(!facilityStates.definition().dependencies().sourceAllowed(selected,beam))throw new IllegalArgumentException("束线不允许该光源："+beam+" / "+selected);
        }
        if(recipe.path("ref").asText().isBlank()||recipe.path("version").asText().isBlank())
            throw new IllegalArgumentException("recipeRef 和 recipeVersion 不能为空");
        ObjectNode source=selection.putObject("source_selection");source.put("default_source",defaultSource);source.set("by_beamline",json.valueToTree(selections));
        var shg=source.putArray("shg_beamlines");for(int beam:seen)if("shg".equals(Objects.toString(selections.get(Integer.toString(beam)),defaultSource)))shg.add(beam);
        run.putArray("evidence");execution.setVariable("shotRun",run.toString());
    }

    public synchronized void stage(DelegateExecution execution,String stage){
        if(!STAGES.contains(stage))throw new IllegalArgumentException("未知发次阶段："+stage);
        if(Set.of("failed","cancelled").contains(stage))throw new IllegalArgumentException("失败和取消阶段必须使用 fail/cancel 写入原因");
        ObjectNode run=current(execution);run.put("stage",stage);execution.setVariable("shotRun",run.toString());
    }

    public synchronized void complete(DelegateExecution execution){
        stage(execution,"completed");
        facilityStates.refresh();
        execution.setVariable("finalFacilityState",facilityStates.current().toString());
        execution.setVariable("outcome","SUCCEEDED");
    }

    public synchronized void fail(DelegateExecution execution,String reason){
        ObjectNode run=current(execution);run.put("stage","failed");
        ObjectNode failure=run.putObject("failure").put("code","RUN_FAILED").put("reason",Objects.toString(reason,"流程失败"))
            .put("detected_at",Instant.now().toString()).put("source","workbench");
        execution.setVariable("shotRun",run.toString());execution.setVariable("outcome","FAILED");
        execution.setVariable("failureReason",reason);
        execution.setVariable("finalFacilityState",facilityStates.current().toString());
    }

    public synchronized void cancel(DelegateExecution execution,String reason){
        ObjectNode run=current(execution);run.put("stage","cancelled");
        run.putObject("failure").put("code","RUN_CANCELLED").put("reason",Objects.toString(reason,"流程已取消"))
            .put("detected_at",Instant.now().toString()).put("source","operator");
        execution.setVariable("shotRun",run.toString());execution.setVariable("outcome","CANCELLED");
    }

    public String dependencyEvidence(Object value,ObjectNode assessment) {
        ObjectNode run=current(value);ArrayNode evidence=run.withArray("evidence");
        String id="dependency-"+assessment.path("id").asText();
        for(int i=0;i<evidence.size();i++)if(id.equals(evidence.get(i).path("id").asText())){evidence.remove(i);break;}
        evidence.addObject().put("id",id).put("kind","capability-dependency")
            .put("status","unknown".equals(assessment.path("status").asText())?"unknown":assessment.path("outcome").asText())
            .put("observed_at",Instant.now().toString()).set("details",assessment.deepCopy());
        return run.toString();
    }

    public String holdEvidence(Object value,ObjectNode assessment) {
        ObjectNode run=current(value);ArrayNode evidence=run.withArray("evidence");
        String id="hold-"+assessment.path("id").asText();
        for(int i=0;i<evidence.size();i++)if(id.equals(evidence.get(i).path("id").asText())){evidence.remove(i);break;}
        evidence.addObject().put("id",id).put("kind","stage-hold")
            .put("status",assessment.path("outcome").asText()).put("observed_at",Instant.now().toString()).set("details",assessment.deepCopy());
        return run.toString();
    }

    public synchronized void record(DelegateExecution execution,String kind,String id,String status){
        if(!Set.of("facility-fact","guard","command-accepted","command-result","device-observation","service-health","interlock","timing","manual-confirmation").contains(kind)
            || !Set.of("accepted","succeeded","failed","unknown","stale","invalidated").contains(status)
            || !id.matches("[A-Za-z0-9_-]{1,128}"))throw new IllegalArgumentException("发次证据字段不合法");
        ObjectNode run=current(execution);ArrayNode evidence=(ArrayNode)run.withArray("evidence");
        ObjectNode item=json.createObjectNode().put("id",id).put("kind",kind).put("status",status).put("observed_at",Instant.now().toString());
        for(int i=0;i<evidence.size();i++)if(id.equals(evidence.get(i).path("id").asText())){evidence.remove(i);break;}
        evidence.add(item);execution.setVariable("shotRun",run.toString());
    }

    /** Called outside the Flowable trigger transaction so a failed transition cannot erase evidence. */
    public String commandEvidence(Object value,Map<String,Object> command,String status,String reason) {
        ObjectNode run=current(value);
        String id=Objects.toString(command.get("commandId"),"");
        ArrayNode evidence=run.withArray("evidence");
        for(int i=0;i<evidence.size();i++)if(id.equals(evidence.get(i).path("id").asText())){evidence.remove(i);break;}
        ObjectNode item=evidence.addObject().put("id",id).put("kind","command-result")
            .put("status",status).put("observed_at",Instant.now().toString())
            .put("device_id",Objects.toString(command.get("commandDevice"),""))
            .put("operation",Objects.toString(command.get("commandAction"),""));
        ObjectNode details=item.putObject("details");details.setAll((com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(command));
        if(reason!=null&&!reason.isBlank())details.put("reason",reason);
        try {
            JsonNode result=json.readTree(Objects.toString(command.get("commandResult"),"{}"));
            JsonNode snapshot=result.path("snapshot");
            if(snapshot.hasNonNull("source_id"))item.put("source_id",snapshot.path("source_id").asText());
            if(snapshot.has("state_revision"))item.put("state_revision",snapshot.path("state_revision").asLong());
        }catch(Exception ex){throw new IllegalArgumentException("动作结果 JSON 无效",ex);}
        return run.toString();
    }

    public String completionEvidence(Object value,ObjectNode wait,String status,String reason) {
        ObjectNode run=current(value);
        ArrayNode evidence=run.withArray("evidence");
        String id=wait.path("id").asText();
        for(int i=0;i<evidence.size();i++)if(id.equals(evidence.get(i).path("id").asText())){evidence.remove(i);break;}
        ObjectNode item=evidence.addObject().put("id",id)
            .put("kind","fact".equals(wait.path("kind").asText())?"facility-fact":"guard")
            .put("status",status).put("observed_at",Instant.now().toString());
        ObjectNode details=wait.deepCopy().put("purpose","activity-completion");
        if(reason!=null&&!reason.isBlank())details.put("decisionReason",reason);
        item.set("details",details);
        return run.toString();
    }

    public synchronized ObjectNode current(DelegateExecution execution){return current(execution.getVariable("shotRun"));}
    private ObjectNode current(Object value){
        if(value==null)throw new IllegalStateException("流程没有发次运行记录");
        try{return (ObjectNode)json.readTree(value.toString());}catch(Exception ex){throw new IllegalStateException("发次运行记录损坏",ex);}
    }
}
