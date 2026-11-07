package cn.cdi8.workbench.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Draft editing and immutable model publication, independent of an environment. */
@Service
public class ModelService {
    private final ModelStore store;
    private final ModelValidation validation;

    public ModelService(ModelStore store, ModelValidation validation) {
        this.store = store;
        this.validation = validation;
    }

    public JsonNode validate(JsonNode input){return validation.validate(input.path("kind").asText(),input.path("xml").asText());}
    public synchronized JsonNode save(JsonNode input) {
        String id=input.path("id").asText(UUID.randomUUID().toString());
        return store.save(id,input.path("name").asText(),input.path("kind").asText(),input.path("xml").asText());
    }
    public synchronized JsonNode publish(String id) {
        var model=store.get(id);
        return store.addRevision(id,validation.validate(model.path("kind").asText(),model.path("draft").asText()));
    }
}
