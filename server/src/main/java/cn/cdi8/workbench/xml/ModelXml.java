package cn.cdi8.workbench.xml;

import java.io.StringReader;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.*;
import org.w3c.dom.Element;
import org.xml.sax.*;

/** The bundled grammar is authoritative; models cannot select or download their own schema. */
public final class ModelXml {
    private ModelXml() {}
    private static final Map<String, Schema> SCHEMAS = new ConcurrentHashMap<>();
    public static Element parse(String xml, String schema) {
        try {
            if (xml == null || xml.isBlank() || xml.length() > 2_000_000)
                throw new IllegalArgumentException("模型 XML 为空或过大");
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setSchema(SCHEMAS.computeIfAbsent(schema, ModelXml::schema));
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new ErrorHandler() {
                public void warning(SAXParseException ex) throws SAXException { throw ex; }
                public void error(SAXParseException ex) throws SAXException { throw ex; }
                public void fatalError(SAXParseException ex) throws SAXException { throw ex; }
            });
            var root = builder.parse(new InputSource(new StringReader(xml))).getDocumentElement();
            uniqueChildIds(root);
            return root;
        } catch (IllegalArgumentException ex) { throw ex; }
        catch (Exception ex) { throw new IllegalArgumentException("模型 XML 校验失败：" + ex.getMessage(), ex); }
    }
    private static Schema schema(String name) {
        try (var input = ModelXml.class.getResourceAsStream("/schemas/" + name + ".xsd")) {
            if (input == null) throw new IllegalStateException("缺少模型 XSD：" + name);
            var factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return factory.newSchema(new StreamSource(input));
        } catch (Exception ex) { throw new IllegalStateException("无法加载模型 XSD：" + name, ex); }
    }
    private static void uniqueChildIds(Element parent) {
        Set<String> ids = new HashSet<>();
        for (var child : children(parent)) {
            if (child.hasAttribute("id") && !ids.add(child.getAttribute("id")))
                throw new IllegalArgumentException("集合内 id 重复：" + parent.getLocalName() + "/" + child.getAttribute("id"));
            uniqueChildIds(child);
        }
    }
    public static List<Element> children(Element parent) {
        var result = new ArrayList<Element>();
        if (parent != null) for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling())
            if (node instanceof Element child) result.add(child);
        return result;
    }
    public static Element child(Element parent, String name) {
        return children(parent).stream().filter(e -> name.equals(e.getLocalName())).findFirst().orElse(null);
    }
    public static List<Element> entries(Element parent, String container) { return children(child(parent, container)); }
    public static String required(Element e, String name) {
        String value = e.getAttribute(name).trim();
        if (value.isEmpty()) throw new IllegalArgumentException("缺少属性 " + name + "（" + e.getLocalName() + "）");
        return value;
    }
    public static Set<String> words(String value) {
        return value.isBlank() ? Set.of() : new LinkedHashSet<>(Arrays.asList(value.trim().split("\\s+")));
    }
}
