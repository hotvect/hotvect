package com.hotvect.serve;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.reflect.TypeToken;
import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.algodefinition.AlgorithmId;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algodefinition.AlgorithmParameterMetadata;
import com.hotvect.api.algorithms.Algorithm;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServeApplicationMetadataTest {
    private static final AlgorithmId ALGORITHM_ID = new AlgorithmId("test-algorithm", "1.2.3");
    private static final String PARAMETER_ID = "parameter-2026-05-02";
    private static final Instant RAN_AT = Instant.parse("2026-05-02T10:15:30Z");
    private static final Instant LAST_TEST_TIME = Instant.parse("2026-05-01T00:00:00Z");

    @Test
    void metadataUsesSimplifiedRuntimeMetadataShape() throws Exception {
        AlgorithmRuntime runtime = testRuntime();
        try (ServeApplication app = new ServeApplication(
                options(),
                List.of(),
                false,
                LocalRuntimeCatalog.fromLoadedRuntimes(List.of(runtime)))) {
                ObjectNode metadata = app.buildMetadata();

                assertFalse(metadata.has("variant_id"));
                assertEquals(
                        "test-algorithm@1.2.3-hp-v4@" + PARAMETER_ID,
                        metadata.get("algorithm_runtime_id").asText());
                assertFalse(metadata.has("action_metadata"));
                assertTrue(metadata.has("runtimes"));

                JsonNode algorithm = metadata.get("algorithm");
                assertEquals("test-algorithm", algorithm.get("name").asText());
                assertEquals("1.2.3", algorithm.get("version").asText());
                assertEquals("hp-v4", algorithm.get("hyperparameter_version").asText());
                assertEquals("test-algorithm@1.2.3", algorithm.get("algorithm_id").asText());
                assertEquals("test-algorithm@1.2.3-hp-v4", algorithm.get("hyperparameter_id").asText());
                assertEquals("v1.2.3-4-gabcdef", algorithm.get("git_describe").asText());
                assertTrue(algorithm.get("hotvect_version").isNull());

                JsonNode parameters = metadata.get("parameters");
                assertEquals(PARAMETER_ID, parameters.get("parameter_id").asText());
                assertEquals(RAN_AT.toString(), parameters.get("ran_at").asText());
                assertEquals(LAST_TEST_TIME.toString(), parameters.get("last_test_time").asText());

                JsonNode runtimeNode = metadata.get("runtimes").get(0);
                assertEquals(
                        runtime.identity().value(),
                        runtimeNode.get("algorithm_runtime_id").asText());

                JsonNode runtimeDetails = app.buildRuntimeDetails(null);
                assertEquals(
                        PARAMETER_ID,
                        runtimeDetails.get("parameter_metadata").get("parameter_id").asText());
        }
    }

    @Test
    void exposesTheSelectedRuntimeWithoutTransferringLifecycleOwnership() throws Exception {
        AlgorithmRuntime runtime = testRuntime();
        try (ServeApplication app = new ServeApplication(
                options(),
                List.of(),
                false,
                LocalRuntimeCatalog.fromLoadedRuntimes(List.of(runtime)))) {
                SelectedRuntime selected = app.selectRuntime(runtime.identity().value());

                assertSame(runtime.algorithmInstance(), selected.algorithmInstance());
                assertEquals(runtime.identity(), selected.identity());
        }
    }

    @Test
    void runtimeDetailsRepresentMissingParameterMetadataAsNull() throws Exception {
        AlgorithmDefinition definition = definition();
        try (AlgorithmRuntime runtime = new AlgorithmRuntime(
                new AlgorithmInstance<>(definition, null, new TestAlgorithm(), TypeToken.of(TestAlgorithm.class)),
                TestAlgorithm.class.getClassLoader())) {
            assertTrue(runtime.getAlgorithmParameterMetadataJson().isNull());
        }
    }

    @Test
    void rejectsRequestSizeAboveBufferedCap() {
        ServerOptions opts = options();
        opts.maxRequestMiB = ServeApplication.MAX_BUFFERED_REQUEST_MIB + 1;

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new ServeApplication(opts, List.of(), false, null));
        assertEquals("--max-request-mib must be between 1 and 512, got 513", error.getMessage());
    }

    @Test
    void requestTooLargeDetailsIncludeMiBAndBytes() {
        RequestBodyTooLargeException error = assertThrows(
                RequestBodyTooLargeException.class,
                () -> ServeApplication.readRequestBodyBytes(
                        new ByteArrayInputStream(new byte[0]),
                        1_048_577L,
                        1_048_576L,
                        1L));
        assertEquals("max_mib=1, max_bytes=1048576", error.getDetails());
    }

    private static ServerOptions options() {
        ServerOptions opts = new ServerOptions();
        opts.host = "127.0.0.1";
        opts.port = 12000;
        opts.maxRequestMiB = 256;
        return opts;
    }

    private static AlgorithmRuntime testRuntime() throws Exception {
        AlgorithmParameterMetadata parameterMetadata = new AlgorithmParameterMetadata(
                ALGORITHM_ID,
                PARAMETER_ID,
                RAN_AT,
                Optional.of(LAST_TEST_TIME));
        return new AlgorithmRuntime(
                new AlgorithmInstance<>(
                        definition(),
                        parameterMetadata,
                        new TestAlgorithm(),
                        TypeToken.of(TestAlgorithm.class)),
                TestAlgorithm.class.getClassLoader());
    }

    private static AlgorithmDefinition definition() {
        ObjectNode raw = JsonNodeFactory.instance.objectNode();
        raw.put("algorithm_name", ALGORITHM_ID.algorithmName());
        raw.put("algorithm_version", ALGORITHM_ID.algorithmVersion());
        raw.put("hyperparameter_version", "hp-v4");
        raw.put("git_describe", "v1.2.3-4-gabcdef");
        return new AlgorithmDefinition(
                raw,
                ALGORITHM_ID,
                Map.of(),
                null,
                null,
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
    }

    private static final class TestAlgorithm implements Algorithm {
    }
}
