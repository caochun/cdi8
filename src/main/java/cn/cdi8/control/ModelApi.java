package cn.cdi8.control;

import cn.cdi8.control.model.ModelRepository;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ModelApi {
    private final ModelRepository models;
    public ModelApi(ModelRepository models){this.models=models;}
    @GetMapping("/api/model/checks") public Map<String,Object> checks() {
        return Map.of("modelHash",models.hash(models.serialize(models.current())),"findings",models.findings());
    }
}
