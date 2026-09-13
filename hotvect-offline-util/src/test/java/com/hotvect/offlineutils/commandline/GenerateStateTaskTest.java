package com.hotvect.offlineutils.commandline;

import java.io.File;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GenerateStateTaskTest {

    @Test
    void addsTaskMetadataWhenGeneratorReturnsNull() {
        Map<String, Object> metadata = GenerateStateTask.withTaskMetadata(
                null,
                "example.StateGenerator",
                options(),
                GenerateStateTask.class.getSimpleName());

        assertEquals("GenerateStateTask", metadata.get("task_type"));
        assertEquals("example.StateGenerator", metadata.get("state_generator"));
        assertEquals("state.json", metadata.get("destination_file"));
    }

    @Test
    void copiesImmutableGeneratorMetadataBeforeAddingTaskFields() {
        Map<String, Object> generatorMetadata = Map.of("records", 3, "task_type", "generator");

        Map<String, Object> metadata = GenerateStateTask.withTaskMetadata(
                generatorMetadata,
                "example.StateGenerator",
                options(),
                GenerateStateTask.class.getSimpleName());

        assertEquals(3, metadata.get("records"));
        assertEquals("GenerateStateTask", metadata.get("task_type"));
        assertEquals("generator", generatorMetadata.get("task_type"));
    }

    private static Options options() {
        Options options = new Options();
        options.destinationFile = new File("state.json");
        options.metadataLocation = new File("metadata");
        options.sourceFiles = Map.of("state", List.of(new File("source.jsonl")));
        return options;
    }
}
