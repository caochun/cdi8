package cn.cdi8.workbench.model;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import jakarta.annotation.PostConstruct;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

/** Editable drafts and immutable content snapshots; source files are never overwritten implicitly. */
@Service
public class ModelStore {
    private final Path directory;
    private final ObjectMapper json;
    public ModelStore(ObjectMapper json, @Value("${control.workbench-directory:./data/workbench}") String directory) {
        this.json=json; this.directory=Path.of(directory);
    }
    @PostConstruct public synchronized void initialize() throws Exception {
        Files.createDirectories(directory.resolve("models"));
        var resolver=new PathMatchingResourcePatternResolver();
        for(String pattern:List.of("classpath*:processes/*.bpmn20.xml","classpath*:component-models/*.scxml","classpath*:system-models/*.system.xml"))
            for(var resource:resolver.getResources(pattern)) {
                String name=resource.getFilename(), id=name.replace(".bpmn20.xml", "").replace(".scxml", "").replace(".system.xml", "");
                try(var input=resource.getInputStream()) {
                    String xml=new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
                    if(Files.exists(path(id))) {
                        var previous=get(id);var revisions=previous.path("revisions");
                        if(revisions.isEmpty())continue;
                        var last=revisions.get(revisions.size()-1);
                        // Refresh untouched built-in examples without overwriting user drafts or versions.
                        if(!last.path("validation").path("builtin").asBoolean() || !previous.path("draft").asText().equals(last.path("xml").asText()) || xml.equals(last.path("xml").asText()))continue;
                    }
                    save(id,name,name.endsWith("scxml")?"scxml":name.endsWith(".system.xml")?"system":"bpmn",xml);
                    addRevision(id,json.createObjectNode().put("valid",true).put("builtin",true));
                }
            }
    }
    private Path path(String id) {
        if(id==null || !id.matches("[A-Za-z0-9_-]{1,128}"))throw new IllegalArgumentException("非法模型编号");
        return directory.resolve("models").resolve(id+".json");
    }
    public synchronized List<JsonNode> list() {
        try(var files=Files.list(directory.resolve("models"))) {
            return files.filter(p->p.toString().endsWith(".json")).sorted().map(p->{try{return json.readTree(p.toFile());}catch(Exception ex){throw new IllegalStateException(ex);}}).toList();
        }catch(java.io.IOException ex){throw new IllegalStateException(ex);}
    }
    public synchronized ObjectNode get(String id) {
        try{return (ObjectNode)json.readTree(path(id).toFile());}catch(java.io.IOException ex){throw new IllegalArgumentException("模型不存在："+id);}
    }
    private void write(String id,JsonNode value) {
        try {
            Path temp=Files.createTempFile(directory.resolve("models"),"save-",".tmp");
            json.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(),value);
            Files.move(temp,path(id),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        }catch(java.io.IOException ex){throw new IllegalStateException(ex);}
    }
    public synchronized ObjectNode save(String id,String name,String kind,String xml) {
        if(!Set.of("bpmn","scxml","system").contains(kind))throw new IllegalArgumentException("模型类型必须为 bpmn、scxml 或 system");
        if(xml==null || xml.length()>1_000_000 || name==null || name.isBlank())throw new IllegalArgumentException("请提供名称及不超过 1 MB 的 XML");
        ObjectNode model=Files.exists(path(id))?get(id):json.createObjectNode();
        if(model.has("kind") && !kind.equals(model.path("kind").asText()))throw new IllegalArgumentException("不能更改模型类型");
        model.put("id",id).put("name",name).put("kind",kind).put("draft",xml).put("updated",Instant.now().toString());
        if(!model.has("revisions"))model.putArray("revisions");
        write(id,model);return model;
    }
    public synchronized ObjectNode addRevision(String id,JsonNode validation) {
        if(!validation.path("valid").asBoolean())throw new IllegalArgumentException("模型校验未通过");
        ObjectNode model=get(id),revision=json.createObjectNode();
        revision.put("id",UUID.randomUUID().toString()).put("created",Instant.now().toString()).put("xml",model.path("draft").asText());
        revision.set("validation",validation);
        ((ArrayNode)model.get("revisions")).add(revision);write(id,model);return revision;
    }
    public JsonNode revision(String modelId,String revisionId) {
        for(JsonNode revision:get(modelId).path("revisions"))if(revision.path("id").asText().equals(revisionId))return revision;
        throw new IllegalArgumentException("模型版本不存在："+revisionId);
    }
    public Path directory(){return directory;}
}
