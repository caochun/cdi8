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
    public ShotRunRuntime(ObjectMapper json){this.json=json;}

    public synchronized void open(DelegateExecution execution){
        if(execution.getVariable("shotRun")!=null)return;
        String shotId=Objects.toString(execution.getVariable("shotId"),"");
        if(shotId.isBlank())throw new IllegalStateException("发次运行缺少 shotId");
        ObjectNode run=json.createObjectNode().put("schema_version","1")
            .put("run_id",execution.getProcessInstanceId()).put("shot_id",shotId)
            .put("facility_definition","gxlf-facility").put("facility_binding","gxlf-facility-binding")
            .put("state_projection","gxlf-facility-state").put("stage","created");
        ObjectNode recipe=run.putObject("recipe");recipe.put("ref",Objects.toString(execution.getVariable("recipeRef"),"unassigned"));recipe.put("version",Objects.toString(execution.getVariable("recipeVersion"),"unassigned"));
        ObjectNode selection=run.putObject("resource_selection");ArrayNode beamlines=selection.putArray("beamlines");
        Object value=execution.getVariable("beamlines");if(value instanceof Collection<?> values)for(Object item:values)beamlines.add(Integer.parseInt(item.toString()));
        ObjectNode source=selection.putObject("source_selection");source.put("default_source",Objects.toString(execution.getVariable("defaultSource"),"seed"));source.putArray("shg_beamlines");
        run.putArray("evidence");execution.setVariable("shotRun",run.toString());
    }

    public synchronized void stage(DelegateExecution execution,String stage){
        if(!STAGES.contains(stage))throw new IllegalArgumentException("未知发次阶段："+stage);
        ObjectNode run=current(execution);run.put("stage",stage);execution.setVariable("shotRun",run.toString());
    }

    public synchronized void record(DelegateExecution execution,String kind,String id,String status){
        ObjectNode run=current(execution);ArrayNode evidence=(ArrayNode)run.withArray("evidence");
        ObjectNode item=json.createObjectNode().put("id",id).put("kind",kind).put("status",status).put("observed_at",Instant.now().toString());
        for(int i=0;i<evidence.size();i++)if(id.equals(evidence.get(i).path("id").asText())){evidence.remove(i);break;}
        evidence.add(item);execution.setVariable("shotRun",run.toString());
    }

    public synchronized ObjectNode current(DelegateExecution execution){return current(execution.getVariable("shotRun"));}
    private ObjectNode current(Object value){
        if(value==null)throw new IllegalStateException("流程没有发次运行记录");
        try{return (ObjectNode)json.readTree(value.toString());}catch(Exception ex){throw new IllegalStateException("发次运行记录损坏",ex);}
    }
}
