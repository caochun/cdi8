package cn.cdi8.workbench.trace;

import cn.cdi8.workbench.integration.tango.TangoGateway;
import cn.cdi8.workbench.model.ModelStore;

import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.flowable.engine.RuntimeService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/** Durable device snapshots for each run; historical displays never borrow live states. */
@Service
public class DebugTrace {
    private final RuntimeService runtime;private final ModelStore store;private final ObjectMapper json;
    public DebugTrace(RuntimeService runtime,ModelStore store,ObjectMapper json){this.runtime=runtime;this.store=store;this.json=json;}
    @EventListener public synchronized void notice(TangoGateway.Notice notice) {
        if(!notice.payload().has("device_id"))return;
        for(var process:runtime.createProcessInstanceQuery().list()) {
            if(process.getSuperExecutionId()!=null)continue;
            try {
                var event=json.createObjectNode().put("time",Instant.now().toString());event.set("device",notice.payload());
                Path directory=store.directory().resolve("traces");Files.createDirectories(directory);
                Files.writeString(directory.resolve(process.getId()+".jsonl"),json.writeValueAsString(event)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
            }catch(java.io.IOException ex){throw new IllegalStateException(ex);}
        }
    }
    public synchronized List<JsonNode> events(String id) {
        if(!id.matches("[A-Za-z0-9_-]+"))throw new IllegalArgumentException("非法运行编号");
        try {
            Path path=store.directory().resolve("traces").resolve(id+".jsonl");
            if(!Files.exists(path))return List.of();
            var lines=Files.readAllLines(path);List<JsonNode> events=new ArrayList<>();
            for(String line:lines.subList(Math.max(0,lines.size()-1000),lines.size()))events.add(json.readTree(line));
            return events;
        }catch(java.io.IOException ex){throw new IllegalStateException(ex);}
    }
}
