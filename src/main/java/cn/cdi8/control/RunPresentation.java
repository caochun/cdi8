package cn.cdi8.control;

import java.util.*;
import org.flowable.bpmn.model.*;
import org.flowable.engine.history.HistoricActivityInstance;

/** Read-only presentation: labels and steps come from the deployed BPMN, not a second flow definition. */
public final class RunPresentation {
    private RunPresentation() {}
    public static List<Map<String,Object>> steps(BpmnModel model,List<HistoricActivityInstance> history,String outcome) {
        if(!(model.getFlowElement("normal_cycle") instanceof SubProcess cycle))return List.of();
        Map<String,FlowElement> elements=new HashMap<>();
        for(var element:cycle.getFlowElements())elements.put(element.getId(),element);
        List<Map<String,Object>> result=new ArrayList<>();Set<String> visited=new HashSet<>();String cursor="normal_start";
        while(cursor!=null && visited.add(cursor)) {
            var element=elements.get(cursor);if(element==null)break;
            if(element instanceof SubProcess || element instanceof ExclusiveGateway) {
                String id=element.getId();var occurrences=history.stream().filter(a->a.getActivityId().equals(id)).toList();
                String status="waiting";
                if(!occurrences.isEmpty()) {
                    boolean completed;
                    if(element instanceof SubProcess sub) {
                        Set<String> normalEnds=new HashSet<>();
                        for(var child:sub.getFlowElements())if(child instanceof EndEvent end && end.getEventDefinitions().isEmpty())normalEnds.add(end.getId());
                        completed=history.stream().anyMatch(a->normalEnds.contains(a.getActivityId())&&a.getEndTime()!=null);
                    }else {
                        var gate=(ExclusiveGateway)element;
                        // Flowable activity history need not contain sequence-flow records.
                        var fallback=elements.get(gate.getDefaultFlow());
                        String fallbackTarget=fallback instanceof SequenceFlow flow?flow.getTargetRef():null;
                        boolean defaultTaken=history.stream().anyMatch(a->a.getActivityId().equals(gate.getDefaultFlow())||a.getActivityId().equals(fallbackTarget));
                        completed=!defaultTaken && occurrences.stream().anyMatch(a->a.getEndTime()!=null);
                    }
                    status=completed?"succeeded":("FAILED".equals(outcome)?"stopped":"running");
                }
                result.add(Map.of("id",id,"name",element.getName()==null?id:element.getName(),"status",status));
            }
            String current=cursor;String defaultFlow=element instanceof ExclusiveGateway g?g.getDefaultFlow():null;
            var outgoing=cycle.getFlowElements().stream().filter(e->e instanceof SequenceFlow)
                .map(e->(SequenceFlow)e).filter(f->f.getSourceRef().equals(current)&&!f.getId().equals(defaultFlow)).toList();
            cursor=outgoing.size()==1?outgoing.get(0).getTargetRef():null;
        }
        return result;
    }
}
