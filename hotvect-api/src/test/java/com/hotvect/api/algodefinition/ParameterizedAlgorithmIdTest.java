package com.hotvect.api.algodefinition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ParameterizedAlgorithmIdTest {
    private static final ObjectMapper OM = new ObjectMapper();

    @Test
    void rendersCodeHyperparametersAndParameters() {
        ParameterizedAlgorithmId identity = ParameterizedAlgorithmId.from(
                definition("example", "82", "2day"),
                parameters("example", "82", "p1"));

        assertEquals(new AlgorithmId("example", "82"), identity.algorithmId());
        assertEquals("example@82-2day", identity.hyperparameterizedAlgorithmId().value());
        assertEquals("example@82-2day@p1", identity.value());
    }

    @Test
    void representsParameterlessAlgorithmsExplicitly() {
        ParameterizedAlgorithmId identity = ParameterizedAlgorithmId.from(
                definition("example", "82", null),
                null);

        assertNull(identity.hyperparameterizedAlgorithmId().hyperparameterVersion());
        assertEquals(ParameterizedAlgorithmId.NO_PARAMETER_ID, identity.parameterId());
        assertEquals("example@82@NA", identity.value());
    }

    @Test
    void rejectsParametersForAnotherAlgorithm() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ParameterizedAlgorithmId.from(
                        definition("example", "82", null),
                        parameters("other", "82", "p1")));
    }

    private static AlgorithmParameterMetadata parameters(String name, String version, String parameterId) {
        return new AlgorithmParameterMetadata(
                new AlgorithmId(name, version),
                parameterId,
                Instant.parse("2026-04-15T10:15:30Z"),
                Optional.empty());
    }

    private static AlgorithmDefinition definition(String name, String version, String hyperparameterVersion) {
        return new AlgorithmDefinition(
                OM.valueToTree(Map.of(
                        "algorithm_name", name,
                        "algorithm_version", version,
                        "hyperparameter_version", hyperparameterVersion == null ? "" : hyperparameterVersion)),
                new AlgorithmId(name, version),
                Map.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                "factory",
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }
}
