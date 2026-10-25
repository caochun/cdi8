package cn.cdi8.workbench.facility;

import java.io.StringReader;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

/** Runtime binding template for logical facility instances and beamline rules. */
public final class FacilityBindingModel {
    public static final String NS = "https://cdi8.cn/facility-binding/v1";
    private final String id;
    private final String definitionRef;
    private final int instanceBindingCount;
    private final int beamlineBindingCount;
    private final int externalBindingCount;

    private FacilityBindingModel(String id, String definitionRef, int instanceBindingCount,
                                 int beamlineBindingCount, int externalBindingCount) {
        this.id = id;
        this.definitionRef = definitionRef;
        this.instanceBindingCount = instanceBindingCount;
        this.beamlineBindingCount = beamlineBindingCount;
        this.externalBindingCount = externalBindingCount;
    }

    public String id() { return id; }
    public String definitionRef() { return definitionRef; }
    public int instanceBindingCount() { return instanceBindingCount; }
    public int beamlineBindingCount() { return beamlineBindingCount; }
    public int externalBindingCount() { return externalBindingCount; }

    public static FacilityBindingModel parse(String xml) {
        try {
            var root = root(xml);
            if (!"facility-binding".equals(root.getLocalName()) || !NS.equals(root.getNamespaceURI()))
                throw new IllegalArgumentException("设施绑定根元素或命名空间错误");
            String id = required(root, "id"), definitionRef = required(root, "definitionRef");
            var instanceSection = section(root, "instanceBindings");
            var beamlineSection = section(root, "beamlineBindings");
            var externalSection = section(root, "externalBindings");
            if (instanceSection == null || children(instanceSection).isEmpty())
                throw new IllegalArgumentException("设施绑定缺少 instanceBindings");
            if (beamlineSection == null || children(beamlineSection).isEmpty())
                throw new IllegalArgumentException("设施绑定缺少 beamlineBindings");
            uniqueIds(instanceSection, "binding", "instanceSet");
            for (var binding : children(instanceSection)) {
                requireElement(binding, "binding");
                required(binding, "instanceSet"); required(binding, "logicalPattern"); required(binding, "runtimeKey");
            }
            for (var beamline : children(beamlineSection)) {
                requireElement(beamline, "beamlineBinding");
                required(beamline, "id"); required(beamline, "resourceRef");
                if (children(beamline).isEmpty()) throw new IllegalArgumentException("束线绑定规则不能为空：" + beamline.getAttribute("id"));
                for (var bind : children(beamline)) {
                    requireElement(bind, "bind"); required(bind, "role");
                    if (!bind.hasAttribute("instancePattern") && !bind.hasAttribute("allowedInstances"))
                        throw new IllegalArgumentException("束线绑定缺少实例规则：" + beamline.getAttribute("id") + "/" + bind.getAttribute("role"));
                }
            }
            int externalCount = 0;
            if (externalSection != null) for (var binding : children(externalSection)) {
                requireElement(binding, "bind"); required(binding, "ref"); required(binding, "runtimeKey");
                externalCount++;
            }
            var assumptions = section(root, "assumptions");
            if (assumptions != null) for (var assumption : children(assumptions)) {
                requireElement(assumption, "assumption"); required(assumption, "id"); required(assumption, "value"); required(assumption, "reason");
            }
            return new FacilityBindingModel(id, definitionRef, children(instanceSection).size(), children(beamlineSection).size(), externalCount);
        } catch (IllegalArgumentException ex) { throw ex; }
        catch (Exception ex) { throw new IllegalArgumentException("设施绑定 XML 无效：" + ex.getMessage(), ex); }
    }

    private static Element root(String xml) throws Exception {
        if (xml == null || xml.isBlank()) throw new IllegalArgumentException("设施绑定 XML 为空");
        var factory = DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml))).getDocumentElement();
    }
    private static Element section(Element root, String name) { for (var child : children(root)) if (name.equals(child.getLocalName())) return child; return null; }
    private static void uniqueIds(Element parent, String elementName, String uniqueAttribute) {
        var values = new HashSet<String>();
        for (var child : children(parent)) if (elementName.equals(child.getLocalName())) {
            String value = required(child, uniqueAttribute); if (!values.add(value)) throw new IllegalArgumentException("绑定重复：" + value);
        }
    }
    private static void requireElement(Element element, String name) { if (!name.equals(element.getLocalName())) throw new IllegalArgumentException("此处需要 " + name + "，实际为 " + element.getLocalName()); }
    private static String required(Element element, String attribute) { String value = element.getAttribute(attribute).trim(); if (value.isEmpty()) throw new IllegalArgumentException("缺少属性 " + attribute + "（" + element.getLocalName() + "）"); return value; }
    private static List<Element> children(Element parent) {
        if (parent == null) return List.of(); var result = new ArrayList<Element>();
        for (var node=parent.getFirstChild(); node!=null; node=node.getNextSibling()) if (node instanceof Element child) {
            if (!NS.equals(child.getNamespaceURI())) throw new IllegalArgumentException("设施绑定子元素命名空间错误：" + child.getNodeName()); result.add(child);
        }
        return result;
    }
}
