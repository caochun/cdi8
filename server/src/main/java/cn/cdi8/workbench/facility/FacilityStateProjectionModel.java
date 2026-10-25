package cn.cdi8.workbench.facility;

import java.io.StringReader;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

/** Orthogonal facility observations, derived facts and reusable guards. */
public final class FacilityStateProjectionModel {
    public static final String NS = "https://cdi8.cn/facility-state/v1";
    private final String id;
    private final String definitionRef;
    private final String bindingRef;
    private final int observationCount;
    private final int factCount;
    private final int guardCount;
    private final int invalidatorCount;

    private FacilityStateProjectionModel(String id, String definitionRef, String bindingRef,
                                         int observationCount, int factCount, int guardCount, int invalidatorCount) {
        this.id=id; this.definitionRef=definitionRef; this.bindingRef=bindingRef;
        this.observationCount=observationCount; this.factCount=factCount; this.guardCount=guardCount; this.invalidatorCount=invalidatorCount;
    }
    public String id() { return id; }
    public String definitionRef() { return definitionRef; }
    public String bindingRef() { return bindingRef; }
    public int observationCount() { return observationCount; }
    public int factCount() { return factCount; }
    public int guardCount() { return guardCount; }
    public int invalidatorCount() { return invalidatorCount; }

    public static FacilityStateProjectionModel parse(String xml) {
        try {
            var root=root(xml);
            if (!"facility-state-projection".equals(root.getLocalName()) || !NS.equals(root.getNamespaceURI()))
                throw new IllegalArgumentException("设施状态投影根元素或命名空间错误");
            String id=required(root,"id"), definitionRef=required(root,"definitionRef"), bindingRef=required(root,"bindingRef");
            positive(root,"evidenceMaxAgeMs");
            var observations=section(root,"observations");
            var facts=section(root,"facts");
            var guards=section(root,"guards");
            var invalidators=section(root,"invalidators");
            if(observations==null||children(observations).isEmpty())throw new IllegalArgumentException("设施状态投影缺少 observations");
            if(facts==null||children(facts).isEmpty())throw new IllegalArgumentException("设施状态投影缺少 facts");
            uniqueIds(observations,"observation");uniqueIds(facts,"fact");uniqueIds(guards,"guard");
            for(var item:children(observations)){requireElement(item,"observation");required(item,"id");if(!item.hasAttribute("sourcePort")&&!item.hasAttribute("sourceRef"))throw new IllegalArgumentException("观测缺少来源："+item.getAttribute("id"));}
            var factIds=ids(facts,"fact");var observationIds=ids(observations,"observation");
            for(var fact:children(facts)){
                requireElement(fact,"fact");required(fact,"id");required(fact,"label");
                if(children(fact).isEmpty()&&!fact.hasAttribute("observation"))throw new IllegalArgumentException("事实没有来源："+fact.getAttribute("id"));
                for(var req:children(fact)){
                    requireElement(req,"require");
                    if(req.hasAttribute("fact")&&!factIds.contains(req.getAttribute("fact")))throw new IllegalArgumentException("事实引用未定义："+req.getAttribute("fact"));
                    if(req.hasAttribute("observation")&&!observationIds.contains(req.getAttribute("observation")))throw new IllegalArgumentException("观测引用未定义："+req.getAttribute("observation"));
                    if(!req.hasAttribute("fact")&&!req.hasAttribute("observation")&&!req.hasAttribute("instanceSet"))throw new IllegalArgumentException("事实 require 缺少来源："+fact.getAttribute("id"));
                }
            }
            int guardCount=0;if(guards!=null)for(var guard:children(guards)){requireElement(guard,"guard");required(guard,"id");required(guard,"label");for(var req:children(guard)){requireElement(req,"require");String ref=required(req,"fact");if(!factIds.contains(ref))throw new IllegalArgumentException("guard 引用未定义事实："+ref);}guardCount++;}
            int invalidatorCount=0;if(invalidators!=null)for(var item:children(invalidators)){requireElement(item,"invalidate");required(item,"event");required(item,"result");required(item,"reason");invalidatorCount++;}
            return new FacilityStateProjectionModel(id,definitionRef,bindingRef,children(observations).size(),children(facts).size(),guardCount,invalidatorCount);
        }catch(IllegalArgumentException ex){throw ex;}catch(Exception ex){throw new IllegalArgumentException("设施状态投影 XML 无效："+ex.getMessage(),ex);}
    }
    private static Element root(String xml)throws Exception{if(xml==null||xml.isBlank())throw new IllegalArgumentException("设施状态投影 XML 为空");var f=DocumentBuilderFactory.newInstance();f.setNamespaceAware(true);f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);f.setFeature("http://xml.org/sax/features/external-general-entities",false);f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);return f.newDocumentBuilder().parse(new InputSource(new StringReader(xml))).getDocumentElement();}
    private static Element section(Element root,String name){for(var child:children(root))if(name.equals(child.getLocalName()))return child;return null;}
    private static Set<String> ids(Element parent,String name){if(parent==null)return Set.of();var result=new LinkedHashSet<String>();for(var child:children(parent))if(name.equals(child.getLocalName()))result.add(required(child,"id"));return result;}
    private static void uniqueIds(Element parent,String name){if(parent==null)return;var seen=new HashSet<String>();for(var child:children(parent))if(name.equals(child.getLocalName())){String id=required(child,"id");if(!seen.add(id))throw new IllegalArgumentException(name+" 编号重复："+id);}}
    private static void requireElement(Element element,String name){if(!name.equals(element.getLocalName()))throw new IllegalArgumentException("此处需要 "+name+"，实际为 "+element.getLocalName());}
    private static String required(Element element,String attribute){String value=element.getAttribute(attribute).trim();if(value.isEmpty())throw new IllegalArgumentException("缺少属性 "+attribute+"（"+element.getLocalName()+"）");return value;}
    private static void positive(Element element,String attribute){try{if(Long.parseLong(required(element,attribute))<1)throw new IllegalArgumentException(attribute+" 必须为正数");}catch(NumberFormatException ex){throw new IllegalArgumentException(attribute+" 必须为正数");}}
    private static List<Element> children(Element parent){if(parent==null)return List.of();var result=new ArrayList<Element>();for(var node=parent.getFirstChild();node!=null;node=node.getNextSibling())if(node instanceof Element child){if(!NS.equals(child.getNamespaceURI()))throw new IllegalArgumentException("设施状态投影子元素命名空间错误："+child.getNodeName());result.add(child);}return result;}
}
