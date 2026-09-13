package com.hotvect.api.algodefinition;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.reflect.TypeToken;
import com.hotvect.api.algorithms.Algorithm;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AlgorithmRuntimeIdTest {
    private static final ObjectMapper OM = new ObjectMapper();

    @Test
    void rendersAlgorithmHyperparameterAndParameterIds() {
        AlgorithmDefinition definition = algorithmDefinition("example-algorithm", "82.0.0", "2day");
        AlgorithmParameterMetadata parameterMetadata = parameters("example-algorithm", "82.0.0", "last-test");

        AlgorithmRuntimeId identity = AlgorithmRuntimeId.leaf(
                ParameterizedAlgorithmId.from(definition, parameterMetadata));

        assertEquals(new AlgorithmId("example-algorithm", "82.0.0"), identity.algorithm().algorithmId());
        assertEquals(
                "example-algorithm@82.0.0-2day",
                identity.algorithm().hyperparameterizedAlgorithmId().value());
        assertEquals("example-algorithm@82.0.0-2day@last-test", identity.value());
    }

    @Test
    void omitsHyperparameterSegmentWhenAbsent() {
        AlgorithmRuntimeId identity = AlgorithmRuntimeId.leaf(ParameterizedAlgorithmId.from(
                algorithmDefinition("example-algorithm", "82.0.0", null),
                parameters("example-algorithm", "82.0.0", "last-test")));

        assertNull(identity.algorithm().hyperparameterizedAlgorithmId().hyperparameterVersion());
        assertEquals("example-algorithm@82.0.0", identity.algorithm().hyperparameterizedAlgorithmId().value());
        assertEquals("example-algorithm@82.0.0@last-test", identity.value());
    }

    @Test
    void equalityIncludesEveryLogicalRuntimeIdentityComponent() {
        AlgorithmRuntimeId baseline = runtime(
                "root", "1", "hp-1", "parameter-1", child("child", "1", "child-parameter"));

        assertNotEquals(baseline, runtime(
                "root", "2", "hp-1", "parameter-1", child("child", "1", "child-parameter")));
        assertNotEquals(baseline, runtime(
                "root", "1", "hp-2", "parameter-1", child("child", "1", "child-parameter")));
        assertNotEquals(baseline, runtime(
                "root", "1", "hp-1", "parameter-2", child("child", "1", "child-parameter")));
        assertNotEquals(baseline, runtime(
                "root", "1", "hp-1", "parameter-1", child("child", "2", "child-parameter")));
    }

    @Test
    void buildsRecursiveIdentityFromExplicitChildIdentities() {
        AlgorithmInstance<Algorithm> firstRoot = instance("root", "1", "root-parameter");
        AlgorithmInstance<Algorithm> secondRoot = instance("root", "1", "root-parameter");

        AlgorithmRuntimeId firstIdentity = AlgorithmRuntimeId.from(
                firstRoot,
                Map.of("child", child("child", "1", "child-parameter")));
        AlgorithmRuntimeId secondIdentity = AlgorithmRuntimeId.from(
                secondRoot,
                Map.of("child", child("child", "2", "child-parameter")));

        assertNotEquals(firstIdentity, secondIdentity);
        assertEquals(
                "child@1@child-parameter",
                firstIdentity.dependencies().get("child").value());
        assertEquals(
                "root@1@root-parameter[child=child@1@child-parameter]",
                firstIdentity.value());
    }

    private static AlgorithmRuntimeId runtime(
            String name,
            String version,
            String hyperparameterVersion,
            String parameterId,
            AlgorithmRuntimeId child) {
        return new AlgorithmRuntimeId(
                ParameterizedAlgorithmId.from(
                        algorithmDefinition(name, version, hyperparameterVersion),
                        parameters(name, version, parameterId)),
                Map.of("child", child));
    }

    private static AlgorithmRuntimeId child(String name, String version, String parameterId) {
        return AlgorithmRuntimeId.leaf(ParameterizedAlgorithmId.from(
                algorithmDefinition(name, version, null),
                parameters(name, version, parameterId)));
    }

    private static AlgorithmInstance<Algorithm> instance(
            String name,
            String version,
            String parameterId) {
        return new AlgorithmInstance<>(
                algorithmDefinition(name, version, null),
                parameters(name, version, parameterId),
                new Algorithm() {},
                new TypeToken<Algorithm>() {});
    }

    private static AlgorithmParameterMetadata parameters(String name, String version, String parameterId) {
        return new AlgorithmParameterMetadata(
                new AlgorithmId(name, version),
                parameterId,
                Instant.parse("2026-04-15T10:15:30Z"),
                Optional.empty());
    }

    private static AlgorithmDefinition algorithmDefinition(
            String algorithmName,
            String algorithmVersion,
            String hyperparameterVersion) {
        var raw = OM.valueToTree(Map.of(
                "algorithm_name", algorithmName,
                "algorithm_version", algorithmVersion,
                "hyperparameter_version", hyperparameterVersion == null ? "" : hyperparameterVersion));
        return new AlgorithmDefinition(
                raw,
                new AlgorithmId(algorithmName, algorithmVersion),
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
