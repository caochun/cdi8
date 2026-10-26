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
            var contract = new cn.cdi8.model.ComponentContract(xml);
            metadata = contract.metadata();
            var root = ScxmlEngine.secureDocument(xml).getDocumentElement();
            var states = root.getElementsByTagNameNS(root.getNamespaceURI(), "*");
            for (int i=0; i<states.getLength(); i++) {
                Element state=(Element)states.item(i);
                if ("state".equals(state.getLocalName()) || "final".equals(state.getLocalName()))
                    outer.put(state.getAttribute("id"),contract.outer(state.getAttribute("id")));
            }
            // Exercise the same engine during validation, trials and device creation.
            if (root.getElementsByTagNameNS(root.getNamespaceURI(), "parallel").getLength() > 0)
                throw new IllegalArgumentException("组件快照协议要求单一叶子状态，不支持 parallel");
            outer(new ScxmlEngine(xml).leaf());
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
    public String requestEvent(String name) { return action(name).path("request_event").asText(); }
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
