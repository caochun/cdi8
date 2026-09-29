package cn.cdi8.control.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.*;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import static cn.cdi8.control.model.Conditions.require;

/** Static checks for the BPMN vocabulary used by this application, not a complete model checker. */
public final class BpmnChecks {
    private static final String B="http://www.omg.org/spec/BPMN/20100524/MODEL",F="http://flowable.org/bpmn";
    private static final Pattern CALL=Pattern.compile("\\$\\{devices\\.(prepare|enqueue|verify|gate|success)\\(execution, '([^']+)'(?:, ([A-Za-z_][A-Za-z0-9_]*))?\\)\\}");
    public record Finding(String status,String node,String detail) {}
    private BpmnChecks() {}
    public static List<Finding> check(ControlModel model,InputStream xml) throws Exception {
        var factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        var doc=factory.newDocumentBuilder().parse(xml);Map<String,Element> elements=new LinkedHashMap<>();
        var all=doc.getElementsByTagNameNS(B,"*");
        Set<String> used=new HashSet<>();
        for(int i=0;i<all.getLength();i++){var e=(Element)all.item(i);if(e.hasAttribute("id"))require(elements.put(e.getAttribute("id"),e)==null,"duplicate BPMN id");}
        require(elements.containsKey(model.processId()),"BPMN process id mismatch");
        for(var e:elements.values()) {
            String expr=e.getAttributeNS(F,"expression");var m=CALL.matcher(expr);
            if(m.matches()) {
                String method=m.group(1),ref=m.group(2);
                if(Set.of("prepare","enqueue","verify").contains(method)) {
                    var binding=model.node(ref);used.add(ref);
                    String scopeId=binding.path("activity_id").asText(ref);boolean belongs=false;
                    for(Node parent=e.getParentNode();parent instanceof Element;parent=parent.getParentNode())
                        if(((Element)parent).getAttribute("id").equals(scopeId))belongs=true;
                    require(belongs,"BPMN action references another node's binding: "+e.getAttribute("id")+" -> "+ref);
                }
                else require(model.workflow().path("conditions").has(ref),"BPMN unknown condition: "+ref);
                if(method.equals("enqueue")) {
                    String variable=m.group(3);require(variable!=null,"enqueue missing instance binding");
                    String expectedGroup=model.node(ref).path("group").asText();String actualGroup=null;
                    if(variable.equals("deviceId")) {
                        for(Node parent=e.getParentNode();parent instanceof Element;parent=parent.getParentNode()) {
                            for(Node child=parent.getFirstChild();child!=null;child=child.getNextSibling()) {
                                if(child instanceof Element loop && "multiInstanceLoopCharacteristics".equals(loop.getLocalName())) {
                                    String collection=loop.getAttributeNS(F,"collection");
                                    require(collection.startsWith("${")&&collection.endsWith("}"),"invalid collection");
                                    var mapping=model.workflow().path("variables").path(collection.substring(2,collection.length()-1));
                                    require(mapping.path("mode").asText().equals("list"),"MI needs list variable");actualGroup=mapping.path("group").asText();break;
                                }
                            }
                            if(actualGroup!=null)break;
                        }
                    } else {
                        var mapping=model.workflow().path("variables").path(variable);
                        require(mapping.path("mode").asText().equals("single"),"single action needs single target variable");actualGroup=mapping.path("group").asText();
                    }
                    require(expectedGroup.equals(actualGroup),"BPMN target group differs from binding: "+ref);
                }
            } else if(expr.matches(".*devices\\.(prepare|enqueue|verify|gate|success)\\(.*")) {
                throw new IllegalArgumentException("unsupported BPMN binding expression: "+expr);
            }
        }
        model.workflow().path("nodes").fieldNames().forEachRemaining(n->require(used.contains(n),"binding not used in BPMN: "+n));
        List<Finding> findings=new ArrayList<>();
        findings.add(new Finding("PASS",model.processId(),"contract/condition/instance references resolved"));
        // Explore the deterministic normal path. Error, timer and interlock branches are not proved here.
        var scope=elements.get("normal_cycle");require(scope!=null,"normal scope not found");
        Map<String,List<Element>> outgoing=new HashMap<>();
        for(Node n=scope.getFirstChild();n!=null;n=n.getNextSibling())if(n instanceof Element e&&e.getLocalName().equals("sequenceFlow"))outgoing.computeIfAbsent(e.getAttribute("sourceRef"),k->new ArrayList<>()).add(e);
        Map<String,Map<String,Object>> states=new LinkedHashMap<>();ObjectMapper json=new ObjectMapper();
        model.catalog().get("devices").fieldNames().forEachRemaining(d->{String type=model.device(d).get("system_type").asText();states.put(d,json.convertValue(model.actions().get("initial_states").get(type),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}));});
        String current="normal_start";Set<String> visited=new HashSet<>();
        while(current!=null) {
            if(!visited.add(current)){findings.add(new Finding("UNDEFINED",current,"cycle requires state-space exploration"));break;}
            if(model.workflow().path("nodes").has(current)) {
                var node=model.node(current);
                if(node.has("guard"))require(model.condition(node.get("guard").asText(),states::get),"normal path guard conflict at "+current);
                for(String d:model.group(node.get("group").asText())) {
                    var contract=model.contract(model.contractRef(current,d));
                    require(Conditions.matches(contract.get("precondition"),states.get(d)),"normal path precondition conflict at "+current+" / "+d);
                    var fixed=Conditions.fixedState(contract.get("completion"));
                    if(fixed.isEmpty()) {findings.add(new Finding("UNDEFINED",current,"non-deterministic postcondition"));return findings;}
                    states.get(d).putAll(fixed.get());
                }
                findings.add(new Finding("PASS",current,"normal path post/preconditions compatible"));
            }
            Element element=elements.get(current);
            var call=CALL.matcher(element.getAttributeNS(F,"expression"));
            if(call.matches()&&call.group(1).equals("gate"))require(model.condition(call.group(2),states::get),"normal path gate conflict at "+current);
            var edges=outgoing.getOrDefault(current,List.of());
            String next=null;
            for(var edge:edges)if(!edge.getAttribute("id").equals(element.getAttribute("default"))){require(next==null,"multiple normal successors need explicit exploration at "+current);next=edge.getAttribute("targetRef");}
            current=next;
        }
        for(var e:elements.values()) {
            var call=CALL.matcher(e.getAttributeNS(F,"expression"));
            if(call.matches()&&call.group(1).equals("success"))
                require(model.condition(call.group(2),states::get),"normal path final condition conflict: "+call.group(2));
        }
        findings.add(new Finding("UNDEFINED",model.processId(),"not a completeness/soundness proof: external events, timer races and compensation paths need further exploration"));
        return findings;
    }
}
