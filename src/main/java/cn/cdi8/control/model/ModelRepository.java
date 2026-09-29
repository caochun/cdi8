package cn.cdi8.control.model;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class ModelRepository {
    private final ObjectMapper json;
    private final JdbcTemplate db;
    private final ControlModel current;
    private final List<BpmnChecks.Finding> findings;
    public ModelRepository(ObjectMapper json,JdbcTemplate db,ResourceLoader resources,
                           @Value("${control.model-directory:classpath:control-model/}") String directory) throws Exception {
        this.json=json;this.db=db;
        var yaml=new ObjectMapper(new YAMLFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION));
        ObjectNode bundle=json.createObjectNode();
        for(var part:Map.of("actions","action-contracts.yaml","catalog","device-catalog.yaml","workflow","workflow-bindings.yaml").entrySet()) {
            try(var input=resources.getResource(directory+(directory.endsWith("/")?"":"/")+part.getValue()).getInputStream()) {bundle.set(part.getKey(),yaml.readTree(input));}
        }
        current=new ControlModel(bundle);current.validate();
        try(var xml=resources.getResource("classpath:processes/laser-joint.bpmn20.xml").getInputStream()) {findings=List.copyOf(BpmnChecks.check(current,xml));}
    }
    public ControlModel current(){return new ControlModel(current.json());}
    public List<BpmnChecks.Finding> findings(){return findings;}
    public ControlModel forRun(String run) {
        try {
            String text=db.queryForObject("SELECT model_snapshot FROM experiment_run WHERE id=?",String.class,run);
            if(text==null)throw new IllegalStateException("run predates contract snapshots; finish/migrate it with the prior application version");
            return new ControlModel(json.readTree(text));
        }catch(java.io.IOException ex){throw new IllegalStateException("invalid run model snapshot",ex);}
    }
    public String serialize(ControlModel model) {
        try{return json.writeValueAsString(model.json());}catch(Exception ex){throw new IllegalStateException(ex);}
    }
    public String hash(String text) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(canonical(json.readTree(text)))));}
        catch(Exception ex){throw new IllegalStateException(ex);}
    }
    private com.fasterxml.jackson.databind.JsonNode canonical(com.fasterxml.jackson.databind.JsonNode node) {
        if(node.isObject()) {
            ObjectNode value=json.createObjectNode();List<String> keys=new ArrayList<>();node.fieldNames().forEachRemaining(keys::add);
            Collections.sort(keys);keys.forEach(key->value.set(key,canonical(node.get(key))));return value;
        }
        if(node.isArray()) {var value=json.createArrayNode();node.forEach(child->value.add(canonical(child)));return value;}
        return node;
    }
}
