package cn.cdi8.control;

import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.w3c.dom.*;

/** Execution-only XML is insufficient: every navigable scope must have BPMN DI. */
class BpmnDiagramTest {
    private static final String BPMN="http://www.omg.org/spec/BPMN/20100524/MODEL";
    private static final String DI="http://www.omg.org/spec/BPMN/20100524/DI";
    @Test void everyScopeHasShapesAndConnections() throws Exception {
        var factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        try(var xml=getClass().getResourceAsStream("/processes/laser-joint.bpmn20.xml")) {
            assertNotNull(xml);var document=factory.newDocumentBuilder().parse(xml);
            Map<String,Element> planes=new HashMap<>();
            var list=document.getElementsByTagNameNS(DI,"BPMNPlane");
            for(int i=0;i<list.getLength();i++) {
                var plane=(Element)list.item(i);
                assertNull(planes.put(plane.getAttribute("bpmnElement"),plane),"duplicate plane");
            }
            Set<String> shapes=Set.of("startEvent","endEvent","serviceTask","receiveTask","userTask","subProcess","exclusiveGateway","boundaryEvent");
            for(String tag:List.of("process","subProcess")) {
                var scopes=document.getElementsByTagNameNS(BPMN,tag);
                for(int i=0;i<scopes.getLength();i++) {
                    var scope=(Element)scopes.item(i);String id=scope.getAttribute("id");
                    var plane=planes.get(id);assertNotNull(plane,"missing diagram for "+id);
                    Set<String> drawnShapes=new HashSet<>(),drawnEdges=new HashSet<>();
                    for(Node n=plane.getFirstChild();n!=null;n=n.getNextSibling()) {
                        if(n instanceof Element e) {
                            if(e.getLocalName().equals("BPMNShape"))drawnShapes.add(e.getAttribute("bpmnElement"));
                            if(e.getLocalName().equals("BPMNEdge")) {
                                drawnEdges.add(e.getAttribute("bpmnElement"));
                                assertTrue(e.getElementsByTagNameNS("http://www.omg.org/spec/DD/20100524/DI","waypoint").getLength()>=2);
                            }
                        }
                    }
                    for(Node n=scope.getFirstChild();n!=null;n=n.getNextSibling()) {
                        if(n instanceof Element e && BPMN.equals(e.getNamespaceURI())) {
                            if(shapes.contains(e.getLocalName()))assertTrue(drawnShapes.contains(e.getAttribute("id")),"missing shape: "+e.getAttribute("id"));
                            if(e.getLocalName().equals("sequenceFlow"))assertTrue(drawnEdges.contains(e.getAttribute("id")),"missing edge: "+e.getAttribute("id"));
                        }
                    }
                }
            }
        }
    }
}
