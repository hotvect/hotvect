package com.hotvect.offlineutils.commandline;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.algodefinition.state.StateGenerator;
import com.hotvect.api.algodefinition.state.StateGeneratorFactory;
import com.hotvect.utils.AlgorithmDefinitionReader;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GenerateStateTaskTest {

    @Test
    void generatesStateWithoutAnAlgorithmFactory(@TempDir Path tempDir) throws Exception {
        AlgorithmDefinition definition = new AlgorithmDefinitionReader().parse("""
                {"algorithm_name":"generated-state", "algorithm_version":"1",
                 "generator_factory_classname":"%s"}
                """.formatted(GeneratorFactory.class.getName()));
        Options options = OfflineTaskTestOptions.direct();
        options.destinationFile = tempDir.resolve("state.txt").toFile();
        try (var context = new OfflineTaskContext(
                new URLClassLoader(new URL[0], getClass().getClassLoader()),
                new SimpleMeterRegistry(), options, definition)) {
            assertEquals(Map.of("records", 1), new GenerateStateTask(context).perform());
        }
        assertEquals("generated lookup table", Files.readString(options.destinationFile.toPath()));
    }

    public static final class GeneratorFactory implements StateGeneratorFactory {
        @Override
        public StateGenerator getGenerator(AlgorithmDefinition definition, ClassLoader loader) {
            return (sources, destination) -> {
                try {
                    Files.writeString(destination.toPath(), "generated lookup table");
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
                return Map.of("records", 1);
            };
        }
    }

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
