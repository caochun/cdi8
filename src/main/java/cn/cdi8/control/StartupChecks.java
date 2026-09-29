package cn.cdi8.control;

import java.util.*;
import cn.cdi8.control.model.*;
import org.flowable.engine.RepositoryService;
import org.springframework.stereotype.Component;

/** Preview each device's first normal-path action without dispatching commands. */
@Component
public class StartupChecks {
    private final RepositoryService repository;
    private final DeviceAdapter adapter;
    public StartupChecks(RepositoryService repository,DeviceAdapter adapter) {
        this.repository=repository;this.adapter=adapter;
    }
    public List<String> issues(ControlModel model) {
        return issues(model,model.processId());
    }
    public List<String> issues(ControlModel model,String processKey) {
        var definition=repository.createProcessDefinitionQuery().processDefinitionKey(processKey).latestVersion().singleResult();
        if(definition==null)return List.of("实验流程尚未部署");
        var steps=RunPresentation.steps(repository.getBpmnModel(definition.getId()),List.of(),"RUNNING");
        Set<String> checked=new HashSet<>();List<String> issues=new ArrayList<>();
        for(var step:steps) {
            String node=(String)step.get("id");
            if(!model.workflow().path("nodes").has(node))continue;
            for(String id:model.group(model.node(node).path("group").asText())) {
                if(!checked.add(id))continue;
                try {
                    var state=adapter.snapshot(model.device(id).path("adapter_id").asText());
                    String mismatch=Conditions.mismatch(model.contract(model.contractRef(node,id)).get("precondition"),state);
                    if(!mismatch.isEmpty())issues.add(id+"（"+step.get("name")+"）："+mismatch);
                }catch(Exception ex){issues.add(id+"：无法读取设备状态，请检查设备网关连接");}
            }
        }
        if(checked.isEmpty())issues.add("无法确定流程的启动动作");
        return issues;
    }
}
