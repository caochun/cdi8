package cn.cdi8.facility.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FacilityRuntimeStoreTest {
    static final ObjectMapper JSON=new ObjectMapper();
    @TempDir Path directory;
    @Test void missingJournalStartsEmptyButCorruptionNeverResetsEvidence()throws Exception {
        var store=new FacilityRuntimeStore(JSON,directory.toString());assertTrue(store.read("duties").isEmpty());
        var evidence=JSON.createObjectNode().putObject("energy").put("active",true);
        store.write("duties",evidence);assertEquals(evidence,new FacilityRuntimeStore(JSON,directory.toString()).read("duties"));
        Files.writeString(directory.resolve("duties.json"),"{truncated");
        assertThrows(IllegalStateException.class,()->store.read("duties"));
    }
    @Test void unwritableJournalFailsBeforeAnyPermissionCanBeGranted()throws Exception {
        Path file=directory.resolve("file");Files.writeString(file,"not a directory");
        var store=new FacilityRuntimeStore(JSON,file.toString());
        assertThrows(IllegalStateException.class,()->store.write("commands",JSON.createObjectNode()));
    }
}
