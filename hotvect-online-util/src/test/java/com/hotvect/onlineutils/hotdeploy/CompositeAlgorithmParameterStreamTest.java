package com.hotvect.onlineutils.hotdeploy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.google.common.collect.ImmutableMap;
import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.algodefinition.AlgorithmId;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algodefinition.common.CompositeAlgorithmFactory;
import com.hotvect.api.algorithms.Algorithm;
import com.hotvect.api.execution.ExecutionContext;
import com.hotvect.api.execution.InputSemantic;
import com.hotvect.api.execution.WorkloadMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompositeAlgorithmParameterStreamTest {
    @TempDir
    Path tempDir;

    @Test
    void compositeAlgorithmFactoryCanReadParameterStream() throws Exception {
        Path parameterZip = tempDir.resolve("parameters.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(parameterZip))) {
            writeEntry(out, "composite-repro/algorithm-parameters.json", """
                    {
                      "algorithm_name": "composite-repro",
                      "algorithm_version": "1.0.0",
                      "parameter_id": "parameter-id",
                      "ran_at": "2026-06-10T09:00:00Z"
                    }
                    """);
            writeEntry(out, "composite-repro/model.parameter", "expected-parameter");
        }

        AlgorithmDefinition definition = new AlgorithmDefinition(
                JsonNodeFactory.instance.objectNode(),
                new AlgorithmId("composite-repro", "1.0.0"),
                ImmutableMap.of(),
                ImmutableMap.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                StreamReadingCompositeFactory.class.getName(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()
        );
        AlgorithmInstance<ParameterEchoAlgorithm> instance = new AlgorithmInstanceFactory(
                Thread.currentThread().getContextClassLoader(),
                ExecutionContext.of(WorkloadMode.BATCH, InputSemantic.OFFLINE),
                true
        ).load(definition, parameterZip.toFile(), ImmutableMap.of());

        assertEquals("expected-parameter", instance.algorithm().parameter);
    }

    private static void writeEntry(ZipOutputStream out, String name, String value) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }

    public static final class StreamReadingCompositeFactory implements CompositeAlgorithmFactory<ParameterEchoAlgorithm> {
        @Override
        public ParameterEchoAlgorithm apply(
                Optional<JsonNode> hyperparameters,
                Map<String, InputStream> parameters,
                Map<String, AlgorithmInstance<?>> algorithmDependencies) {
            try {
                String parameter = new String(
                        parameters.get("model.parameter").readAllBytes(),
                        StandardCharsets.UTF_8);
                return new ParameterEchoAlgorithm(parameter);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public record ParameterEchoAlgorithm(String parameter) implements Algorithm {
    }
}
