package com.hotvect.api.algodefinition;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AlgorithmRuntimeIdentityTest {
    private static final ObjectMapper OM = new ObjectMapper();

    @Test
    void rendersAlgorithmHyperparameterAndParameterIds() {
        AlgorithmDefinition definition = algorithmDefinition(
                "example-algorithm",
                "82.0.0",
                "2day");
        AlgorithmParameterMetadata parameterMetadata = new AlgorithmParameterMetadata(
                new AlgorithmId("example-algorithm", "82.0.0"),
                "last_test_date_2026-04-14",
                Instant.parse("2026-04-15T10:15:30Z"),
                Optional.empty());

        AlgorithmRuntimeIdentity identity = AlgorithmRuntimeIdentity.from(definition, parameterMetadata);

        assertEquals("example-algorithm@82.0.0", identity.algorithmId());
        assertEquals("example-algorithm@82.0.0-2day", identity.hyperparameterId());
        assertEquals(
                "example-algorithm@82.0.0-2day@last_test_date_2026-04-14",
                identity.algorithmRuntimeId());
    }

    @Test
    void omitsHyperparameterSegmentWhenAbsent() {
        AlgorithmDefinition definition = algorithmDefinition(
                "example-algorithm",
                "82.0.0",
                null);
        AlgorithmParameterMetadata parameterMetadata = new AlgorithmParameterMetadata(
                new AlgorithmId("example-algorithm", "82.0.0"),
                "last_test_date_2026-04-14",
                Instant.parse("2026-04-15T10:15:30Z"),
                Optional.empty());

        AlgorithmRuntimeIdentity identity = AlgorithmRuntimeIdentity.from(definition, parameterMetadata);

        assertNull(identity.hyperparameterVersion());
        assertEquals("example-algorithm@82.0.0", identity.hyperparameterId());
        assertEquals(
                "example-algorithm@82.0.0@last_test_date_2026-04-14",
                identity.algorithmRuntimeId());
    }

    private static AlgorithmDefinition algorithmDefinition(
            final String algorithmName,
            final String algorithmVersion,
            final String hyperparameterVersion) {
        var raw = OM.valueToTree(Map.of(
                "algorithm_name", algorithmName,
                "algorithm_version", algorithmVersion,
                "hyperparameter_version", hyperparameterVersion == null ? "" : hyperparameterVersion));
        return new AlgorithmDefinition(
                raw,
                new AlgorithmId(algorithmName, algorithmVersion),
                Map.of(),
                Map.of(),
                null,
                "example.DecoderFactory",
                null,
                null,
                null,
                null,
                "example.AlgorithmFactory",
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }
}
