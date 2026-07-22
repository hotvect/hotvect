package com.hotvect.algorithmserver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.algodefinition.AlgorithmId;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algodefinition.AlgorithmParameterMetadata;
import com.hotvect.api.algorithms.Algorithm;
import com.hotvect.api.codec.common.ExampleDecoder;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LocalRuntimeCatalogTest {
    @Test
    void selectsLocalRuntimeBySortedAlgorithmRuntimeId() throws Exception {
        try (AlgorithmRuntime runtimeB = testRuntime("b-params", "hp-b");
             AlgorithmRuntime runtimeA = testRuntime("a-params", "hp-a")) {
            LocalRuntimeCatalog catalog = LocalRuntimeCatalog.fromLoadedRuntimes(List.of(runtimeB, runtimeA));
            try (catalog) {
                assertEquals(runtimeA.identity().algorithmRuntimeId(), catalog.selectOrDefault(null).algorithmRuntimeId());
                assertEquals(runtimeB.identity().algorithmRuntimeId(), catalog.selectOrDefault(runtimeB.identity().algorithmRuntimeId()).algorithmRuntimeId());

                ObjectNode metadata = JsonNodeFactory.instance.objectNode();
                catalog.addMetadata(metadata);
                assertEquals(runtimeA.identity().algorithmRuntimeId(), metadata.get("runtimes").get(0).get("algorithm_runtime_id").asText());
                assertEquals(runtimeB.identity().algorithmRuntimeId(), metadata.get("runtimes").get(1).get("algorithm_runtime_id").asText());
            }
        }
    }

    @Test
    void rejectsUnknownAlgorithmRuntimeId() throws Exception {
        try (AlgorithmRuntime runtime = testRuntime("a-params", "hp-a")) {
            LocalRuntimeCatalog catalog = LocalRuntimeCatalog.fromLoadedRuntimes(List.of(runtime));
            try (catalog) {
                ContractViolationException error = assertThrows(
                        ContractViolationException.class,
                        () -> catalog.selectOrDefault("missing-runtime"));
                assertEquals("Unknown algorithm_runtime_id: missing-runtime", error.getMessage());
            }
        }
    }

    private static AlgorithmRuntime testRuntime(String parameterId, String hyperparameterVersion) throws Exception {
        AlgorithmId algorithmId = new AlgorithmId("test-algorithm", "1.2.3");
        AlgorithmDefinition definition = new AlgorithmDefinition(
                rawAlgorithmDefinition(algorithmId, hyperparameterVersion),
                algorithmId,
                Map.of(),
                Map.of(),
                null,
                TestDecoderFactory.class.getName(),
                null,
                null,
                null,
                null,
                null,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        AlgorithmParameterMetadata parameterMetadata = new AlgorithmParameterMetadata(
                algorithmId,
                parameterId,
                Instant.parse("2026-05-02T10:15:30Z"),
                Optional.of(Instant.parse("2026-05-01T00:00:00Z")));
        return new AlgorithmRuntime(new AlgorithmInstance<>(definition, parameterMetadata, new TestAlgorithm()));
    }

    private static ObjectNode rawAlgorithmDefinition(AlgorithmId algorithmId, String hyperparameterVersion) {
        ObjectNode raw = JsonNodeFactory.instance.objectNode();
        raw.put("algorithm_name", algorithmId.algorithmName());
        raw.put("algorithm_version", algorithmId.algorithmVersion());
        raw.put("hyperparameter_version", hyperparameterVersion);
        raw.put("git_describe", "v1.2.3-4-gabcdef");
        raw.put("decoder_factory_classname", TestDecoderFactory.class.getName());
        return raw;
    }

    public static final class TestAlgorithm implements Algorithm {
    }

    public static final class TestDecoderFactory implements Function<Optional<JsonNode>, ExampleDecoder<?>> {
        @Override
        public ExampleDecoder<?> apply(Optional<JsonNode> ignored) {
            return input -> List.of();
        }
    }
}
