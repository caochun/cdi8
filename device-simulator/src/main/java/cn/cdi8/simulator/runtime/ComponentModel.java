package cn.cdi8.simulator.runtime;

import cn.cdi8.simulator.engine.ScxmlEngine;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.w3c.dom.*;

/** Project component metadata is an adapter contract, separate from engine semantics. */
public final class ComponentModel {
    public static final ObjectMapper JSON = new ObjectMapper();
    public final String xml, digest;
    public final JsonNode metadata;
    private final Map<String,String> outer = new HashMap<>();

    public ComponentModel(String xml) {
        this.xml = xml;
        try {
            var document = ScxmlEngine.secureDocument(xml);
            var root = document.getDocumentElement();
            if (!"http://www.w3.org/2005/07/scxml".equals(root.getNamespaceURI()) || !"1.0".equals(root.getAttribute("version")))
                throw new IllegalArgumentException("要求 SCXML 1.0 模型");
            JsonNode value = null;
            var data = root.getElementsByTagNameNS(root.getNamespaceURI(), "data");
            for (int i=0; i<data.getLength(); i++) if ("componentMetadata".equals(((Element)data.item(i)).getAttribute("id")))
                value = JSON.readTree(data.item(i).getTextContent());
            if (value == null || !value.isObject()) throw new IllegalArgumentException("缺少 componentMetadata JSON");
            metadata = value;
            if (!metadata.path("model_id").isTextual() || metadata.path("model_id").asText().isBlank())
                throw new IllegalArgumentException("componentMetadata.model_id 必须为非空字符串");
            if (!metadata.path("model_version").canConvertToInt())
                throw new IllegalArgumentException("componentMetadata.model_version 必须为整数");
            if (!metadata.path("actions").isObject() || metadata.path("actions").isEmpty())
                throw new IllegalArgumentException("componentMetadata.actions 不能为空");
            if (!metadata.path("context_defaults").isObject())
                throw new IllegalArgumentException("componentMetadata.context_defaults 必须为对象");
            var states = root.getElementsByTagNameNS(root.getNamespaceURI(), "state");
            for (int i=0; i<states.getLength(); i++) {
                Element state=(Element)states.item(i), parent=state;
                while (parent.getParentNode() instanceof Element element && "state".equals(element.getLocalName())) parent=element;
                outer.put(state.getAttribute("id"),parent.getAttribute("id"));
            }
            digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) { throw new IllegalArgumentException("组件模型无效：" + ex.getMessage(), ex); }
    }
    public String outer(String leaf) {
        String value=outer.get(leaf);
        if (value==null) throw new IllegalArgumentException("模型中没有状态："+leaf);
        return value;
    }
    public JsonNode action(String name) {
        JsonNode value=metadata.path("actions").get(name);
        if (value==null) throw new IllegalArgumentException("未知动作："+name);
        return value;
    }
    public JsonNode confirmation(String name) {
        JsonNode value=metadata.path("confirmations").get(name);
        if (value==null) throw new IllegalArgumentException("未知确认事件："+name);
        return value;
    }
    public static String encode(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (Exception ex) { throw new IllegalArgumentException("无效 JSON",ex); }
    }
    public static JsonNode parse(String text) {
        try { return JSON.readTree(text); }
        catch (Exception ex) { throw new IllegalArgumentException("无效 JSON",ex); }
    }
    public static String text(JsonNode value,String key) {
        JsonNode field=value.get(key);
        if (field==null || !field.isTextual()) throw new IllegalArgumentException(key+" 必须是字符串");
        return field.asText();
    }
    public static String identifier(JsonNode value,String key) {
        String result=text(value,key);
        if (!result.matches("[A-Za-z0-9_-]{1,128}")) throw new IllegalArgumentException(key+" 必须为 1–128 个字母、数字、下划线或连字符");
        return result;
    }
}
