package cn.cdi8.workbench.systemdefinition;

import java.io.StringReader;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

/** Static definition of a facility: composition, capabilities, topology and invariants. */
public final class SystemDefinitionModel {
    public static final String NS = "https://cdi8.cn/system-definition/v1";
    private final String id;
    private final String name;
    private final String version;
    private final int componentTypeCount;
    private final int instanceSetCount;
    private final int invariantCount;
    private final int connectionCount;
    private final int openIssueCount;
    private final Map<String, Set<String>> capabilities;

    private SystemDefinitionModel(String id, String name, String version,
                                  int componentTypeCount, int instanceSetCount, int invariantCount,
                                  int connectionCount, int openIssueCount, Map<String, Set<String>> capabilities) {
        this.id = id;
        this.name = name;
        this.version = version;
        this.componentTypeCount = componentTypeCount;
        this.instanceSetCount = instanceSetCount;
        this.invariantCount = invariantCount;
        this.connectionCount = connectionCount;
        this.openIssueCount = openIssueCount;
        this.capabilities = Collections.unmodifiableMap(new LinkedHashMap<>(capabilities));
    }

    public String id() { return id; }
    public String name() { return name; }
    public String version() { return version; }
    public int componentTypeCount() { return componentTypeCount; }
    public int instanceSetCount() { return instanceSetCount; }
    public int invariantCount() { return invariantCount; }
    public int connectionCount() { return connectionCount; }
    public int openIssueCount() { return openIssueCount; }
    public boolean hasCapability(String component, String capability) {
        return capabilities.getOrDefault(component, Set.of()).contains(capability);
    }

    public static SystemDefinitionModel parse(String xml) {
        try {
            if (xml == null || xml.isBlank()) throw new IllegalArgumentException("系统定义 XML 为空");
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            var root = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml))).getDocumentElement();
            if (!"system-definition".equals(root.getLocalName()) || !NS.equals(root.getNamespaceURI()))
                throw new IllegalArgumentException("系统定义根元素或命名空间错误");
            String id = required(root, "id"), name = required(root, "name"), version = required(root, "version");
            var sections = children(root);
            Set<String> allowed = Set.of("scope", "resources", "interfaces", "controlNodes", "componentTypes", "instanceSets",
                "externalInterfaces", "topology", "mappings", "invariants", "openIssues");
            for (var section : sections) if (!allowed.contains(section.getLocalName()))
                throw new IllegalArgumentException("不支持的系统定义章节：" + section.getLocalName());
            var componentTypes = section(root, "componentTypes");
            var instanceSets = section(root, "instanceSets");
            var invariants = section(root, "invariants");
            var controlNodes = section(root, "controlNodes");
            var openIssues = section(root, "openIssues");
            if (componentTypes == null || children(componentTypes).isEmpty())
                throw new IllegalArgumentException("系统定义至少需要一个 componentType");
            if (instanceSets == null || children(instanceSets).isEmpty())
                throw new IllegalArgumentException("系统定义至少需要一个 instanceSet");
            validateScopedIds(componentTypes, "componentType");
            validateScopedIds(instanceSets, "instanceSet");
            validateScopedIds(controlNodes, "controlNode");
            validateScopedIds(section(root, "resources"), "resource");
            validateScopedIds(section(root, "interfaces"), "interface");
            validateScopedIds(section(root, "externalInterfaces"), "externalInterface");
            validateScopedIds(invariants, "invariant");
            var typeIds = ids(componentTypes, "componentType");
            var interfaceIds = ids(section(root, "interfaces"), "interface");
            var externalIds = ids(section(root, "externalInterfaces"), "externalInterface");
            var controlNodeIds = ids(controlNodes, "controlNode");
            Map<String, Set<String>> capabilitiesByType = new LinkedHashMap<>();
            for (var type : children(componentTypes)) {
                requireElement(type, "componentType");
                required(type, "id"); required(type, "label"); positive(type, "quantity");
                if (!interfaceIds.contains(required(type, "interfaceRef")))
                    throw new IllegalArgumentException("设备类型引用未定义接口：" + type.getAttribute("id"));
                required(type, "stateModelRef");
                var capabilities = children(type).stream().filter(child -> "capability".equals(child.getLocalName())).toList();
                if (capabilities.isEmpty()) throw new IllegalArgumentException("设备类型没有能力：" + type.getAttribute("id"));
                for (var port : children(type).stream().filter(child -> "port".equals(child.getLocalName())).toList()) {
                    required(port, "id"); required(port, "direction");
                    if (!interfaceIds.contains(required(port, "interfaceRef")))
                        throw new IllegalArgumentException("设备端口引用未定义接口：" + type.getAttribute("id") + "/" + port.getAttribute("id"));
                }
                validateScopedIds(type, "port");
                for (var child : children(type)) if (!Set.of("port", "capability").contains(child.getLocalName()))
                    throw new IllegalArgumentException("设备类型包含不支持的子元素：" + child.getLocalName());
                validateScopedIds(type, "capability");
                var typeCapabilities = new LinkedHashSet<String>();
                for (var capability : capabilities) {
                    requireElement(capability, "capability");
                    required(capability, "id"); required(capability, "label"); required(capability, "kind");
                    typeCapabilities.add(capability.getAttribute("id"));
                }
                capabilitiesByType.put(type.getAttribute("id"), Set.copyOf(typeCapabilities));
            }
            if (controlNodes != null) for (var node : children(controlNodes)) {
                requireElement(node, "controlNode"); required(node, "id"); required(node, "label"); required(node, "role");
                validateScopedIds(node, "port"); validateScopedIds(node, "capability");
                var controlCapabilities = new LinkedHashSet<String>();
                for (var capability : children(node).stream().filter(child -> "capability".equals(child.getLocalName())).toList()) {
                    required(capability, "id"); required(capability, "label"); required(capability, "kind");
                    controlCapabilities.add(capability.getAttribute("id"));
                }
                capabilitiesByType.put(node.getAttribute("id"), Set.copyOf(controlCapabilities));
                for (var port : children(node).stream().filter(child -> "port".equals(child.getLocalName())).toList()) {
                    required(port, "id"); required(port, "direction");
                    if (!interfaceIds.contains(required(port, "interfaceRef")))
                        throw new IllegalArgumentException("控制节点端口引用未定义接口：" + node.getAttribute("id") + "/" + port.getAttribute("id"));
                }
            }
            for (var set : children(instanceSets)) {
                requireElement(set, "instanceSet");
                required(set, "id");
                if (!typeIds.contains(required(set, "type")))
                    throw new IllegalArgumentException("实例集合引用未定义设备类型：" + set.getAttribute("id"));
                required(set, "pattern"); positive(set, "quantity");
            }
            var externalInterfaces = section(root, "externalInterfaces");
            if (externalInterfaces != null) for (var external : children(externalInterfaces)) {
                requireElement(external, "externalInterface");
                String ref = required(external, "interfaceRef");
                if (!interfaceIds.contains(ref)) throw new IllegalArgumentException("外部接口引用未定义接口：" + external.getAttribute("id"));
            }
            var topology = section(root, "topology");
            int connectionCount = 0;
            if (topology != null) for (var path : children(topology)) {
                requireElement(path, "path"); required(path, "id"); required(path, "label");
                validateScopedIds(path, "connection");
                for (var endpoint : children(path)) {
                    if ("endpoint".equals(endpoint.getLocalName())) {
                        String ref = required(endpoint, "ref"); required(endpoint, "role");
                        for (String candidate : ref.split("\\s+"))
                            if (!typeIds.contains(candidate) && !externalIds.contains(candidate)
                                    && !controlNodeIds.contains(candidate) && !"all-components".equals(candidate))
                                throw new IllegalArgumentException("拓扑端点引用未定义对象：" + candidate);
                    } else if ("connection".equals(endpoint.getLocalName())) {
                        required(endpoint, "id"); required(endpoint, "source"); required(endpoint, "target"); required(endpoint, "relation");
                        connectionCount++;
                    } else throw new IllegalArgumentException("拓扑路径包含不支持的子元素：" + endpoint.getLocalName());
                }
            }
            var mappings = section(root, "mappings");
            if (mappings != null) for (var mapping : children(mappings)) {
                requireElement(mapping, "mapping"); required(mapping, "id"); required(mapping, "source"); required(mapping, "target");
            }
            if (invariants != null) for (var invariant : children(invariants)) {
                requireElement(invariant, "invariant"); required(invariant, "id"); required(invariant, "severity");
                if (children(invariant).isEmpty()) throw new IllegalArgumentException("系统不变量不能为空：" + invariant.getAttribute("id"));
            }
            int openIssueCount = 0;
            if (openIssues != null) for (var issue : children(openIssues)) {
                requireElement(issue, "issue"); required(issue, "id"); required(issue, "status"); required(issue, "impact"); required(issue, "summary");
                openIssueCount++;
            }
            return new SystemDefinitionModel(id, name, version, children(componentTypes).size(), children(instanceSets).size(),
                invariants == null ? 0 : children(invariants).size(), connectionCount, openIssueCount, capabilitiesByType);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("系统定义 XML 无效：" + ex.getMessage(), ex);
        }
    }

    private static Element section(Element root, String name) {
        for (var child : children(root)) if (name.equals(child.getLocalName())) return child;
        return null;
    }

    private static void validateScopedIds(Element parent, String name) {
        if (parent == null) return;
        var seen = new HashSet<String>();
        for (var child : children(parent)) if (name.equals(child.getLocalName())) {
            String id = required(child, "id");
            if (!seen.add(id)) throw new IllegalArgumentException(name + " 编号重复：" + id);
        }
    }

    private static Set<String> ids(Element parent, String name) {
        if (parent == null) return Set.of();
        var result = new LinkedHashSet<String>();
        for (var child : children(parent)) if (name.equals(child.getLocalName())) result.add(required(child, "id"));
        return result;
    }

    private static void requireElement(Element element, String name) {
        if (!name.equals(element.getLocalName())) throw new IllegalArgumentException("此处需要 " + name + "，实际为 " + element.getLocalName());
    }

    private static int positive(Element element, String attribute) {
        String value = required(element, attribute);
        try {
            int result = Integer.parseInt(value);
            if (result < 1) throw new IllegalArgumentException(attribute + " 必须为正整数：" + value);
            return result;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(attribute + " 必须为正整数：" + value);
        }
    }

    private static String required(Element element, String attribute) {
        String value = element.getAttribute(attribute).trim();
        if (value.isEmpty()) throw new IllegalArgumentException("缺少属性 " + attribute + "（" + element.getLocalName() + "）");
        return value;
    }

    private static List<Element> children(Element parent) {
        if (parent == null) return List.of();
        var result = new ArrayList<Element>();
        for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) if (node instanceof Element child) {
            if (!NS.equals(child.getNamespaceURI())) throw new IllegalArgumentException("系统定义子元素命名空间错误：" + child.getNodeName());
            result.add(child);
        }
        return result;
    }
}
