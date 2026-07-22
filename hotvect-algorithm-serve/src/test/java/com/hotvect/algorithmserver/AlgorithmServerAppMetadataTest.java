package com.hotvect.algorithmserver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.algodefinition.AlgorithmId;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algodefinition.AlgorithmParameterMetadata;
import com.hotvect.api.algorithms.Algorithm;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.algorithms.TopK;
import com.hotvect.api.codec.common.ExampleDecoder;
import com.hotvect.api.data.AvailableAction;
import com.hotvect.api.data.ranking.OfflineRankingRequest;
import com.hotvect.api.data.ranking.RankingDecision;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.ranking.RankingResponse;
import com.hotvect.api.data.topk.OfflineTopKRequest;
import com.hotvect.api.data.topk.TopKDecision;
import com.hotvect.api.data.topk.TopKExample;
import com.hotvect.api.data.topk.TopKRequest;
import com.hotvect.api.data.topk.TopKResponse;
import com.hotvect.api.transformation.Computing;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlgorithmServerAppMetadataTest {
    private static final AlgorithmId ALGORITHM_ID = new AlgorithmId("test-algorithm", "1.2.3");
    private static final String PARAMETER_ID = "parameter-2026-05-02";
    private static final Instant RAN_AT = Instant.parse("2026-05-02T10:15:30Z");
    private static final Instant LAST_TEST_TIME = Instant.parse("2026-05-01T00:00:00Z");

    @Test
    void metadataUsesSimplifiedRuntimeMetadataShape() throws Exception {
        try (AlgorithmRuntime runtime = testRuntime()) {
            RuntimeSelection selection = new RuntimeSelection(
                    runtime,
                    Optional.empty());
            ServerOptions opts = new ServerOptions();
            opts.host = "127.0.0.1";
            opts.port = 12000;
            opts.maxRequestMiB = 256;

            try (AlgorithmServerApp app = new AlgorithmServerApp(
                    opts,
                    ActionMetadataLookup.empty(),
                    List.of(),
                    new MetadataRuntimeProvider(selection))) {
                ObjectNode metadata = app.buildMetadata();

                assertFalse(metadata.has("variant_id"));
                assertEquals("test-algorithm@1.2.3-hp-v4@" + PARAMETER_ID, metadata.get("algorithm_runtime_id").asText());
                assertFalse(metadata.has("parameter_id"));
                assertFalse(metadata.has("algorithm_jar"));
                assertFalse(metadata.has("parameter_path"));
                assertFalse(metadata.has("assignment_key"));
                assertFalse(metadata.has("algorithm_source"));
                assertFalse(metadata.has("selected_algorithm_runtime_id"));
                assertFalse(metadata.has("local"));
                assertFalse(metadata.has("ems"));
                assertFalse(metadata.has("local_algorithm_source"));
                assertTrue(metadata.has("runtimes"));

                JsonNode algorithm = metadata.get("algorithm");
                assertEquals("test-algorithm", algorithm.get("name").asText());
                assertEquals("1.2.3", algorithm.get("version").asText());
                assertEquals("hp-v4", algorithm.get("hyperparameter_version").asText());
                assertEquals("test-algorithm@1.2.3", algorithm.get("algorithm_id").asText());
                assertEquals("test-algorithm@1.2.3-hp-v4", algorithm.get("hyperparameter_id").asText());
                assertEquals("v1.2.3-4-gabcdef", algorithm.get("git_describe").asText());
                assertTrue(algorithm.get("hotvect_version").isNull());
                assertFalse(algorithm.has("parameter_id"));
                assertFalse(algorithm.has("algorithm_runtime_id"));

                JsonNode parameters = metadata.get("parameters");
                assertEquals(PARAMETER_ID, parameters.get("parameter_id").asText());
                assertEquals(RAN_AT.toString(), parameters.get("ran_at").asText());
                assertEquals(LAST_TEST_TIME.toString(), parameters.get("last_test_time").asText());
                assertFalse(parameters.has("algorithm_name"));
                assertFalse(parameters.has("algorithm_version"));
                assertFalse(parameters.has("hyperparameter_version"));
                assertFalse(parameters.has("algorithm_runtime_id"));

                JsonNode runtimes = metadata.get("runtimes");
                assertEquals(1, runtimes.size());
                JsonNode runtimeNode = runtimes.get(0);
                assertEquals(
                        selection.runtime().identity().algorithmRuntimeId(),
                        runtimeNode.get("algorithm_runtime_id").asText());
                assertEquals("test-algorithm", runtimeNode.get("algorithm").get("name").asText());
                assertEquals(PARAMETER_ID, runtimeNode.get("parameters").get("parameter_id").asText());
                assertFalse(runtimeNode.has("algorithm_jar"));
                assertFalse(runtimeNode.has("parameter_path"));
                assertFalse(runtimeNode.has("algorithm_override"));

                JsonNode actionMetadata = metadata.get("action_metadata");
                assertFalse(actionMetadata.get("enabled").asBoolean());
                assertEquals(0, actionMetadata.get("count").asInt());

                JsonNode runtimeDetails = app.buildRuntimeDetails(null);
                assertEquals(
                        selection.runtime().identity().algorithmRuntimeId(),
                        runtimeDetails.get("algorithm_runtime_id").asText());
                assertEquals(
                        "test-algorithm",
                        runtimeDetails.get("effective_algorithm_definition").get("algorithm_name").asText());
                assertEquals(
                        PARAMETER_ID,
                        runtimeDetails.get("parameter_metadata").get("parameter_id").asText());
                assertEquals(
                        "1.2.3",
                        runtimeDetails.get("parameter_metadata").get("algorithm_version").asText());
            }
        }
    }

    @Test
    void rejectsRequestSizeAboveBufferedCap() {
        ServerOptions opts = new ServerOptions();
        opts.host = "127.0.0.1";
        opts.port = 12000;
        opts.maxRequestMiB = AlgorithmServerApp.MAX_BUFFERED_REQUEST_MIB + 1;

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new AlgorithmServerApp(opts, ActionMetadataLookup.empty(), List.of(), null));
        assertEquals("--max-request-mib must be between 1 and 512, got 513", error.getMessage());
    }

    @Test
    void runtimeDetailsRepresentMissingParameterMetadataAsNull() throws Exception {
        AlgorithmDefinition definition = new AlgorithmDefinition(
                rawAlgorithmDefinition(TestDecoderFactory.class),
                ALGORITHM_ID,
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
        try (AlgorithmRuntime runtime = new AlgorithmRuntime(
                new AlgorithmInstance<>(definition, null, new TestAlgorithm()))) {
            assertTrue(runtime.getAlgorithmParameterMetadataJson().isNull());
        }
    }

    @Test
    void requestTooLargeDetailsIncludeMiBAndBytes() {
        RequestBodyTooLargeException error = assertThrows(
                RequestBodyTooLargeException.class,
                () -> AlgorithmServerApp.readRequestBodyBytes(
                        new ByteArrayInputStream(new byte[0]),
                        1_048_577L,
                        1_048_576L,
                        1L));
        assertEquals("max_mib=1, max_bytes=1048576", error.getDetails());
    }

    @Test
    void tryDecodeExampleIdUsesRequestedRuntimeSelection() throws Exception {
        try (AlgorithmRuntime runtime = testRuntime()) {
            AtomicReference<String> requestedRuntimeId = new AtomicReference<>();
            ServerOptions opts = new ServerOptions();
            opts.host = "127.0.0.1";
            opts.port = 12000;
            opts.maxRequestMiB = 256;

            try (AlgorithmServerApp app = new AlgorithmServerApp(
                    opts,
                    ActionMetadataLookup.empty(),
                    List.of(),
                    new RecordingRuntimeProvider(runtime, requestedRuntimeId))) {
                AlgorithmServerApp.DecodedExampleId decoded = app.tryDecodeExampleIdOrNull("{}", "requested-runtime");
                assertEquals("requested-runtime", requestedRuntimeId.get());
                assertEquals(runtime.identity().algorithmRuntimeId(), decoded.algorithmRuntimeId());
            }
        }
    }

    @Test
    void runRankerUsesRequestAvailableActionMetadata() throws Exception {
        try (AlgorithmRuntime runtime = testRuntime(new TestRankerAlgorithm(), TestRankerDecoderFactory.class)) {
            JsonNode root = runtime.runRawExampleJson(JsonNodeFactory.instance.objectNode(), ActionMetadataLookup.empty());
            JsonNode decision = root.get("decisions").get(0);

            assertEquals("ranked-1", decision.get("action_id").asText());
            assertEquals("Ranked candidate", decision.get("action_name").asText());
            assertEquals("https://example/ranked-1.jpg", decision.get("action_image_url").asText());
            assertEquals("Ranked candidate", decision.get("additional_properties").get("action_name").asText());
            assertEquals(1, decision.get("additional_properties").get("rerank_rank").asInt());
        }
    }

    @Test
    void runTopKUsesAvailableActionMetadata() throws Exception {
        try (AlgorithmRuntime runtime = testRuntime(new TestTopKAlgorithm(), TestTopKDecoderFactory.class)) {
            JsonNode root = runtime.runRawExampleJson(JsonNodeFactory.instance.objectNode(), ActionMetadataLookup.empty());
            JsonNode decision = root.get("decisions").get(0);

            assertEquals("topk-1", decision.get("action_id").asText());
            assertEquals("TopK candidate", decision.get("action_name").asText());
            assertEquals("https://example/topk-1.jpg", decision.get("action_image_url").asText());
            assertEquals("TopK candidate", decision.get("additional_properties").get("action_name").asText());
            assertEquals(1, decision.get("additional_properties").get("semantic_rank").asInt());
        }
    }

    private static AlgorithmRuntime testRuntime() throws Exception {
        return testRuntime(new TestAlgorithm(), TestDecoderFactory.class);
    }

    private static AlgorithmRuntime testRuntime(
            Algorithm algorithm,
            Class<? extends Function<Optional<JsonNode>, ExampleDecoder<?>>> decoderFactoryClass) throws Exception {
        AlgorithmDefinition definition = new AlgorithmDefinition(
                rawAlgorithmDefinition(decoderFactoryClass),
                ALGORITHM_ID,
                Map.of(),
                Map.of(),
                null,
                decoderFactoryClass.getName(),
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
                ALGORITHM_ID,
                PARAMETER_ID,
                RAN_AT,
                Optional.of(LAST_TEST_TIME));
        return new AlgorithmRuntime(new AlgorithmInstance<>(definition, parameterMetadata, algorithm));
    }

    private static ObjectNode rawAlgorithmDefinition(
            Class<? extends Function<Optional<JsonNode>, ExampleDecoder<?>>> decoderFactoryClass) {
        ObjectNode raw = JsonNodeFactory.instance.objectNode();
        raw.put("algorithm_name", ALGORITHM_ID.algorithmName());
        raw.put("algorithm_version", ALGORITHM_ID.algorithmVersion());
        raw.put("hyperparameter_version", "hp-v4");
        raw.put("git_describe", "v1.2.3-4-gabcdef");
        raw.put("decoder_factory_classname", decoderFactoryClass.getName());
        return raw;
    }

    private static final class MetadataRuntimeProvider implements AlgorithmRuntimeProvider {
        private final RuntimeSelection selection;

        private MetadataRuntimeProvider(RuntimeSelection selection) {
            this.selection = selection;
        }

        @Override
        public RuntimeSelection selectRuntime(String algorithmRuntimeIdOrNull) {
            return selection;
        }

        @Override
        public void addMetadata(ObjectNode root, RuntimeSelection selection) {
            AlgorithmServerApp.addRuntimesMetadata(root, List.of(selection.runtime()));
        }

        @Override
        public void close() {
        }
    }

    private static final class RecordingRuntimeProvider implements AlgorithmRuntimeProvider {
        private final AlgorithmRuntime runtime;
        private final AtomicReference<String> requestedRuntimeId;

        private RecordingRuntimeProvider(AlgorithmRuntime runtime, AtomicReference<String> requestedRuntimeId) {
            this.runtime = runtime;
            this.requestedRuntimeId = requestedRuntimeId;
        }

        @Override
        public RuntimeSelection selectRuntime(String algorithmRuntimeIdOrNull) {
            requestedRuntimeId.set(algorithmRuntimeIdOrNull);
            return new RuntimeSelection(runtime, Optional.empty());
        }

        @Override
        public void addMetadata(ObjectNode root, RuntimeSelection selection) {
            AlgorithmServerApp.addRuntimesMetadata(root, List.of(selection.runtime()));
        }

        @Override
        public void close() {
        }
    }

    public static final class TestAlgorithm implements Algorithm {
    }

    public static final class TestDecoderFactory implements Function<Optional<JsonNode>, ExampleDecoder<?>> {
        @Override
        public ExampleDecoder<?> apply(Optional<JsonNode> ignored) {
            return input -> List.of();
        }
    }

    public static final class TestRankerDecoderFactory implements Function<Optional<JsonNode>, ExampleDecoder<?>> {
        @Override
        public ExampleDecoder<?> apply(Optional<JsonNode> ignored) {
            return input -> List.of(new com.hotvect.api.data.ranking.RankingExample<>(
                    "rank-example-1",
                    OfflineRankingRequest.ofAvailableActions(
                            "rank-example-1",
                            "shared",
                            List.of(AvailableAction.of(
                                    "ranked-1",
                                    "raw-ranked-action",
                                    Map.of(
                                            "action_name", "Ranked candidate",
                                            "action_image_url", "https://example/ranked-1.jpg")))),
                    List.of()));
        }
    }

    public static final class TestRankerAlgorithm implements Algorithm, Ranker<String, String> {
        @Override
        public RankingResponse<String> rank(RankingRequest<String, String> request) {
            return RankingResponse.newResponse(
                    List.of(RankingDecision.builder(
                                    request.actions().getFirst().actionId(),
                                    0,
                                    request.actions().getFirst().action())
                            .withScore(1.0)
                            .withAdditionalProperties(Map.of("rerank_rank", 1))
                            .build()),
                    Map.of());
        }
    }

    public static final class TestTopKDecoderFactory implements Function<Optional<JsonNode>, ExampleDecoder<?>> {
        @Override
        public ExampleDecoder<?> apply(Optional<JsonNode> ignored) {
            return input -> List.of(new TopKExample<>(
                    "topk-example-1",
                    OfflineTopKRequest.newOfflineTopKRequest(
                            "topk-example-1",
                            RAN_AT,
                            "shared",
                            1),
                    List.of()));
        }
    }

    public static final class TestTopKAlgorithm implements Algorithm,
            TopK<String, com.hotvect.api.data.topk.AvailableAction<String>> {
        @Override
        public TopKResponse<com.hotvect.api.data.topk.AvailableAction<String>> apply(TopKRequest<String> request) {
            com.hotvect.api.data.topk.AvailableAction<String> action =
                    new com.hotvect.api.data.topk.AvailableAction<>(
                            "topk-1",
                            Computing.builder("raw-topk-action").build(),
                            Map.of(
                                    "action_name", "TopK candidate",
                                    "action_image_url", "https://example/topk-1.jpg"));
            return TopKResponse.newResponse(
                    List.of(TopKDecision.builder("topk-1", action)
                            .withScore(0.9)
                            .withAdditionalProperties(Map.of("semantic_rank", 1))
                            .build()),
                    Map.of());
        }
    }
}
