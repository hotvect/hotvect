package com.hotvect.algorithmdemo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.reflect.TypeToken;
import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.algodefinition.AlgorithmId;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algodefinition.AlgorithmParameterMetadata;
import com.hotvect.api.algodefinition.AlgorithmRuntimeId;
import com.hotvect.api.algodefinition.ParameterizedAlgorithmId;
import com.hotvect.api.algorithms.Algorithm;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.algorithms.TopK;
import com.hotvect.api.codec.common.ExampleDecoder;
import com.hotvect.api.data.AvailableAction;
import com.hotvect.api.data.ranking.OfflineRankingRequest;
import com.hotvect.api.data.ranking.RankingDecision;
import com.hotvect.api.data.ranking.RankingExample;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.ranking.RankingResponse;
import com.hotvect.api.data.topk.OfflineTopKRequest;
import com.hotvect.api.data.topk.TopKDecision;
import com.hotvect.api.data.topk.TopKExample;
import com.hotvect.api.data.topk.TopKRequest;
import com.hotvect.api.data.topk.TopKResponse;
import com.hotvect.api.transformation.Computing;
import com.hotvect.serve.SelectedRuntime;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OfflineExampleExecutorTest {
    private static final AlgorithmId ALGORITHM_ID = new AlgorithmId("test-algorithm", "1.2.3");
    private static final String PARAMETER_ID = "parameter-2026-05-02";
    private static final Instant RAN_AT = Instant.parse("2026-05-02T10:15:30Z");

    @Test
    void runsRankerExamplesAndProjectsActionMetadata() throws Exception {
        SelectedRuntime selected = selectedRuntime(
                new TestRankerAlgorithm(),
                TestRankerDecoderFactory.class);
        OfflineExampleExecutor executor = new OfflineExampleExecutor(
                _ignored -> selected,
                ActionMetadataLookup.empty());

        JsonNode root = executor.runExample(
                JsonNodeFactory.instance.objectNode(),
                null,
                null);
        JsonNode decision = root.get("decisions").get(0);

        assertEquals("ranked-1", decision.get("action_id").asText());
        assertEquals("Ranked candidate", decision.get("action_name").asText());
        assertEquals("https://example/ranked-1.jpg", decision.get("action_image_url").asText());
        assertEquals(1, decision.get("additional_properties").get("rerank_rank").asInt());
        assertEquals(selected.identity().value(), root.get("algorithm_runtime_id").asText());
    }

    @Test
    void runsTopKExamplesAndProjectsActionMetadata() throws Exception {
        SelectedRuntime selected = selectedRuntime(
                new TestTopKAlgorithm(),
                TestTopKDecoderFactory.class);
        OfflineExampleExecutor executor = new OfflineExampleExecutor(
                _ignored -> selected,
                ActionMetadataLookup.empty());

        JsonNode root = executor.runExample(
                JsonNodeFactory.instance.objectNode(),
                null,
                null);
        JsonNode decision = root.get("decisions").get(0);

        assertEquals("topk-1", decision.get("action_id").asText());
        assertEquals("TopK candidate", decision.get("action_name").asText());
        assertEquals("https://example/topk-1.jpg", decision.get("action_image_url").asText());
        assertEquals(1, decision.get("additional_properties").get("semantic_rank").asInt());
    }

    private static SelectedRuntime selectedRuntime(
            Algorithm algorithm,
            Class<? extends Function<Optional<JsonNode>, ExampleDecoder<?>>> decoderFactoryClass) {
        ObjectNode raw = JsonNodeFactory.instance.objectNode();
        raw.put("algorithm_name", ALGORITHM_ID.algorithmName());
        raw.put("algorithm_version", ALGORITHM_ID.algorithmVersion());
        raw.put("hyperparameter_version", "hp-v4");
        raw.put("decoder_factory_classname", decoderFactoryClass.getName());
        AlgorithmDefinition definition = new AlgorithmDefinition(
                raw,
                ALGORITHM_ID,
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
                Optional.of(RAN_AT));
        AlgorithmInstance<?> instance = new AlgorithmInstance<>(
                definition,
                parameterMetadata,
                algorithm,
                new TypeToken<Algorithm>() {});
        return new SelectedRuntime(
                instance,
                AlgorithmRuntimeId.leaf(ParameterizedAlgorithmId.from(definition, parameterMetadata)),
                decoderFactoryClass.getClassLoader());
    }

    public static final class TestRankerDecoderFactory
            implements Function<Optional<JsonNode>, ExampleDecoder<?>> {
        @Override
        public ExampleDecoder<?> apply(Optional<JsonNode> ignored) {
            return input -> List.of(new RankingExample<>(
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

    public static final class TestTopKDecoderFactory
            implements Function<Optional<JsonNode>, ExampleDecoder<?>> {
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
        public TopKResponse<com.hotvect.api.data.topk.AvailableAction<String>> apply(
                TopKRequest<String> request) {
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
