package cn.cdi8.facility.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Atomic local journal; corrupt/unwritable evidence fails closed instead of silently resetting duties. */
@Component
public final class FacilityRuntimeStore {
    private final ObjectMapper json;
    private final Path directory;
    public FacilityRuntimeStore(ObjectMapper json,@Value("${control.facility-store:./data/supervision}") String directory) {
        this.json=json;this.directory=Path.of(directory);
    }
    public synchronized JsonNode read(String name) {
        try {
            Path file=directory.resolve(name+".json");
            if(!Files.exists(file))return json.createObjectNode();
            JsonNode value=json.readTree(Files.readAllBytes(file));
            if(!(value instanceof ObjectNode))throw new IllegalStateException("监督记录不是对象");
            return value;
        }catch(Exception ex){throw new IllegalStateException("无法读取监督记录："+name,ex);}
    }
    public synchronized void write(String name,JsonNode value) {
        try {
            Files.createDirectories(directory);
            Path temp=Files.createTempFile(directory,name,".tmp");
            try {
                Files.write(temp,json.writeValueAsBytes(value));
                try(var channel=java.nio.channels.FileChannel.open(temp,StandardOpenOption.WRITE)){channel.force(true);}
                Files.move(temp,directory.resolve(name+".json"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
            } finally { Files.deleteIfExists(temp); }
        }catch(Exception ex){throw new IllegalStateException("无法保存监督记录，禁止继续放行："+name,ex);}
    }
}
