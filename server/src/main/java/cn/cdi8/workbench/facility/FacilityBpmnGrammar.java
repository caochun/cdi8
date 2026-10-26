package cn.cdi8.workbench.facility;

import cn.cdi8.model.ComponentContract;
import java.util.*;
import org.flowable.bpmn.model.*;
import org.w3c.dom.Element;
import static cn.cdi8.workbench.facility.FacilityBpmnModel.*;
import static cn.cdi8.workbench.xml.ModelXml.children;

/** Check raw XML before Flowable can discard misplaced or misspelled safety declarations. */
public final class FacilityBpmnGrammar {
    private static final String BPMN="http://www.omg.org/spec/BPMN/20100524/MODEL";
    private record Rule(Set<String> attributes,Set<String> required) {}
    private static final Map<String,Rule> RULES=Map.of(
        "actions",rule("",""),
        "action",rule("typeRef capabilityRef collectionVar expectedStateRef","typeRef capabilityRef collectionVar"),
        "requires",rule("guardRef","guardRef"),
        "completion",rule("factRef guardRef timeout","timeout"),
        "capability",rule("ownerRef capabilityRef","ownerRef capabilityRef"),
        "stage",rule("value","value"),
        "records",rule("kind factRef operation","kind"));
    private FacilityBpmnGrammar() {}
    private static Set<String> words(String value){return value.isBlank()?Set.of():Set.of(value.split(" "));}
    private static Rule rule(String attributes,String required){return new Rule(words(attributes),words(required));}
    private static boolean reserved(String ns){return FACILITY_NS.equals(ns)||RUN_NS.equals(ns);}
    private static boolean projectNamespace(String ns){return Objects.toString(ns,"").startsWith("https://cdi8.cn/");}
    private static Rule lookup(String ns,String name) {
        if(!RULES.containsKey(name) || (Set.of("stage","records").contains(name)!=RUN_NS.equals(ns)))
            throw new IllegalArgumentException("未知设施/发次扩展："+name);
        return RULES.get(name);
    }
    private static void attributes(String ns,String name,Map<String,String> attrs) {
        Rule rule=lookup(ns,name);
        for(String key:attrs.keySet())if(!rule.attributes.contains(key))throw new IllegalArgumentException(name+" 不支持属性："+key);
        for(String key:rule.required)if(attrs.getOrDefault(key,"").isBlank())throw new IllegalArgumentException(name+" 缺少属性："+key);
        for(var entry:attrs.entrySet())if(entry.getValue().isBlank())throw new IllegalArgumentException(name+" 属性不能为空："+entry.getKey());
        for(var entry:attrs.entrySet())
            if((entry.getKey().endsWith("Ref")||"collectionVar".equals(entry.getKey()))&&entry.getValue().matches(".*\\s.*"))
                throw new IllegalArgumentException(name+" 必须引用单个标识："+entry.getKey());
    }
    public static void validateXml(String xml) {
        var document=ComponentContract.document(xml);var nodes=document.getElementsByTagNameNS("*","*");
        for(int i=0;i<nodes.getLength();i++) {
            Element e=(Element)nodes.item(i);String ns=e.getNamespaceURI(),name=e.getLocalName();
            // A typo in the namespace must not turn a known safety prefix into an ignored annotation.
            if((Set.of("fac","run").contains(Objects.toString(e.getPrefix(),"")) || projectNamespace(ns))&&!reserved(ns))
                throw new IllegalArgumentException("不支持的设施/发次扩展命名空间："+ns);
            for(int j=0;j<e.getAttributes().getLength();j++) {
                var attribute=e.getAttributes().item(j);
                if(projectNamespace(attribute.getNamespaceURI()))throw new IllegalArgumentException("设施/发次扩展必须使用元素，不能作为属性："+attribute.getNodeName());
            }
            if(!reserved(ns))continue;
            Map<String,String> attrs=new LinkedHashMap<>();
            for(int j=0;j<e.getAttributes().getLength();j++) {
                var a=e.getAttributes().item(j);
                if(!javax.xml.XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(a.getNamespaceURI()))attrs.put(a.getNodeName(),a.getNodeValue());
            }
            attributes(ns,name,attrs);
            if(!(e.getParentNode() instanceof Element parent))throw new IllegalArgumentException("扩展位置无效："+name);
            if("action".equals(name)) {
                if(!FACILITY_NS.equals(parent.getNamespaceURI())||!"actions".equals(parent.getLocalName()))throw new IllegalArgumentException("fac:action 必须放在 fac:actions 集合内");
            } else {
                if(!BPMN.equals(parent.getNamespaceURI())||!"extensionElements".equals(parent.getLocalName())
                    || !(parent.getParentNode() instanceof Element owner)||!BPMN.equals(owner.getNamespaceURI())||!"serviceTask".equals(owner.getLocalName()))
                    throw new IllegalArgumentException(name+" 必须放在 serviceTask/extensionElements 内");
                if(children(parent).stream().filter(c->ns.equals(c.getNamespaceURI())&&name.equals(c.getLocalName())).count()!=1)
                    throw new IllegalArgumentException("重复扩展："+name);
            }
            if("actions".equals(name)) {
                if(children(e).isEmpty())throw new IllegalArgumentException("fac:actions 不能为空");
                for(Element child:children(e))if(!FACILITY_NS.equals(child.getNamespaceURI())||!"action".equals(child.getLocalName()))throw new IllegalArgumentException("fac:actions 只允许 fac:action 子元素");
            } else if(!children(e).isEmpty())throw new IllegalArgumentException(name+" 不支持子元素");
            if(!e.getTextContent().isBlank())throw new IllegalArgumentException(name+" 不支持文本内容");
        }
    }
    /** Also guard programmatic/direct Flowable deployment, which may bypass the workbench parser. */
    static void validate(BaseElement owner) {
        Set<String> seen=new HashSet<>();
        for(var group:owner.getExtensionElements().values())for(var ext:group)if(projectNamespace(ext.getNamespace())) {
            if(!reserved(ext.getNamespace()))throw new IllegalArgumentException("不支持的设施/发次扩展命名空间："+ext.getNamespace());
            if(!(owner instanceof ServiceTask)||"action".equals(ext.getName()))throw new IllegalArgumentException("扩展位置无效："+ext.getName());
            if(!seen.add(ext.getNamespace()+ext.getName()))throw new IllegalArgumentException("重复扩展："+ext.getName());
            validate(ext);
        }
    }
    private static void validate(ExtensionElement ext) {
        Map<String,String> attrs=new LinkedHashMap<>();
        for(var group:ext.getAttributes().values())for(var a:group) {
            if(a.getNamespace()!=null&&!a.getNamespace().isBlank())throw new IllegalArgumentException("扩展属性不能使用命名空间："+a.getName());
            if(attrs.put(a.getName(),a.getValue())!=null)throw new IllegalArgumentException("重复属性："+a.getName());
        }
        attributes(ext.getNamespace(),ext.getName(),attrs);
        var children=ext.getChildElements().values().stream().flatMap(Collection::stream).toList();
        if("actions".equals(ext.getName())) {
            if(children.isEmpty())throw new IllegalArgumentException("fac:actions 不能为空");
            for(var child:children){if(!FACILITY_NS.equals(child.getNamespace())||!"action".equals(child.getName()))throw new IllegalArgumentException("fac:actions 只允许 fac:action");validate(child);}
        }else if(!children.isEmpty())throw new IllegalArgumentException(ext.getName()+" 不支持子元素");
        if(ext.getElementText()!=null&&!ext.getElementText().isBlank())throw new IllegalArgumentException(ext.getName()+" 不支持文本内容");
    }
}
