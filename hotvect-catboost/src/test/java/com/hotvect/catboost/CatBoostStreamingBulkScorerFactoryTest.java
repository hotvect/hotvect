package com.hotvect.catboost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.hotvect.api.algorithms.BulkScorer;
import com.hotvect.api.data.AvailableAction;
import com.hotvect.api.data.FeatureStoreResponseContainer;
import com.hotvect.api.data.Namespace;
import com.hotvect.api.data.ValueType;
import com.hotvect.api.data.common.NamespacedRecordImpl;
import com.hotvect.api.data.featurestore.FeatureStoreResponse;
import com.hotvect.api.data.featurestore.SimpleFeatureStoreResponse;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.ranking.TransformedAction;
import com.hotvect.api.data.scoring.BulkScoreResponse;
import com.hotvect.core.transform.ranking.StreamingRankingTransformer;
import com.hotvect.core.transform.ranking.PreparedBatchStream;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatBoostStreamingBulkScorerFactoryTest {
    private enum TestNamespace implements Namespace {
        CAT {
            @Override
            public ValueType getFeatureValueType() {
                return CatBoostFeatureType.CATEGORICAL;
            }
        },
        NUM {
            @Override
            public ValueType getFeatureValueType() {
                return CatBoostFeatureType.NUMERICAL;
            }
        }
    }

    @Test
    void missingModelParameterFailsWithExpectedKey() {
        CatBoostStreamingBulkScorerFactory<String, String> factory = new CatBoostStreamingBulkScorerFactory<>();
        StreamingRankingTransformer<String, String> transformer = new StreamingRankingTransformer<>() {
            @Override
            public java.util.stream.Stream<com.hotvect.api.data.ranking.TransformedAction<String>> transformStream(
                    com.hotvect.api.data.ranking.RankingRequest<String, String> request
            ) {
                return java.util.stream.Stream.empty();
            }

            @Override
            public com.hotvect.core.transform.ranking.PreparedBatchStream<String> prepareBatchStream(
                    com.hotvect.api.data.ranking.RankingRequest<String, String> request
            ) {
                return new com.hotvect.core.transform.ranking.PreparedBatchStream<>(java.util.stream.Stream.empty(), java.util.Map.of());
            }

            @Override
            public java.util.SortedSet<? extends com.hotvect.api.data.Namespace> getUsedFeatures() {
                return new java.util.TreeSet<>(com.hotvect.api.data.Namespace.alphabetical());
            }
        };

        IllegalStateException e = assertThrows(
                IllegalStateException.class,
                () -> factory.apply(transformer, Map.<String, InputStream>of(), Optional.<JsonNode>empty())
        );
        assertTrue(e.getMessage().contains("model_parameter/model.parameter"));
    }

    @Test
    void defaultFactoryReturnsEmptyResponseContainerForFetchedResponses() throws Exception {
        FeatureStoreResponse fetchedResponse = SimpleFeatureStoreResponse.success(Map.of());
        RankingRequest<String, String> request = request();

        try (BulkScorer<String, String> scorer = new CatBoostStreamingBulkScorerFactory<String, String>().apply(
                transformer(Map.of("orders", fetchedResponse)),
                modelParameters(CatBoostFactoryUtils.MODEL_PARAMETER_KEY_V10),
                Optional.empty()
        )) {
            FeatureStoreResponseContainer responseContainer = scorer.score(request).featureStoreResponseContainer();

            assertSame(FeatureStoreResponseContainer.empty(), responseContainer);
            assertTrue(responseContainer.featureStoreResponses().isEmpty());
        }
    }

    @Test
    void constructorProviderReceivesFetchedResponsesThroughFactoryAndScorer() throws Exception {
        FeatureStoreResponse fetchedResponse = SimpleFeatureStoreResponse.success(Map.of());
        RankingRequest<String, String> request = request();

        try (BulkScorer<String, String> scorer = new ResponseContainerFactory().apply(
                transformer(Map.of("orders", fetchedResponse)),
                modelParameters(CatBoostFactoryUtils.MODEL_PARAMETER_KEY_V10),
                Optional.empty()
        )) {
            FeatureStoreResponseContainer responseContainer = scorer.score(request).featureStoreResponseContainer();

            assertEquals(Map.of("orders", fetchedResponse), responseContainer.featureStoreResponses());
            assertSame(fetchedResponse, responseContainer.featureStoreResponses().get("orders"));
        }
    }

    @Test
    void factoryRetainsModelLookupTaskTypeAndParallelBatchScoring() throws Exception {
        RankingRequest<String, String> request = request();

        var regressionResponse = score(
                new CatBoostStreamingBulkScorerFactory<>(),
                CatBoostFactoryUtils.MODEL_PARAMETER_KEY_V10,
                hyperparameters("regression", false),
                request
        );
        var classificationResponse = score(
                new CatBoostStreamingBulkScorerFactory<>(),
                CatBoostFactoryUtils.MODEL_PARAMETER_KEY_V10,
                hyperparameters("classification", true),
                request
        );
        var legacyModelKeyResponse = score(
                new CatBoostStreamingBulkScorerFactory<>(),
                CatBoostFactoryUtils.MODEL_PARAMETER_KEY_V9,
                hyperparameters("regression", false),
                request
        );

        assertEquals(
                regressionResponse.decisions().stream().map(decision -> decision.score()).toList(),
                legacyModelKeyResponse.decisions().stream().map(decision -> decision.score()).toList()
        );
        for (int i = 0; i < regressionResponse.decisions().size(); i++) {
            double regressionScore = regressionResponse.decisions().get(i).score();
            double expectedClassificationScore = 1.0 / (1.0 + Math.exp(-regressionScore));
            assertEquals(
                    expectedClassificationScore,
                    classificationResponse.decisions().get(i).score(),
                    1.0e-6
            );
        }
    }

    private static BulkScoreResponse<String> score(
            CatBoostStreamingBulkScorerFactory<String, String> factory,
            String modelParameterKey,
            JsonNode hyperparameters,
            RankingRequest<String, String> request
    ) throws Exception {
        try (BulkScorer<String, String> scorer = factory.apply(
                transformer(Map.of()),
                modelParameters(modelParameterKey),
                Optional.of(hyperparameters)
        )) {
            return scorer.score(request);
        }
    }

    private static Map<String, InputStream> modelParameters(String modelParameterKey) {
        return Map.of(
                modelParameterKey,
                CatBoostStreamingBulkScorerFactoryTest.class.getResourceAsStream("categorical_example_model.bin")
        );
    }

    private static JsonNode hyperparameters(String taskType, boolean parallelBatchScoring) {
        return JsonNodeFactory.instance.objectNode()
                .put("task_type", taskType)
                .put("parallel_batch_scoring", parallelBatchScoring);
    }

    private static RankingRequest<String, String> request() {
        return RankingRequest.ofAvailableActions(
                "example",
                "shared",
                List.of(
                        AvailableAction.of("a0", "a0"),
                        AvailableAction.of("a1", "a1")
                )
        );
    }

    private static StreamingRankingTransformer<String, String> transformer(
            Map<String, FeatureStoreResponse> fetchedResponses
    ) {
        return new StreamingRankingTransformer<>() {
            private final SortedSet<Namespace> usedFeatures = new TreeSet<>(Namespace.alphabetical());

            {
                usedFeatures.add(TestNamespace.CAT);
                usedFeatures.add(TestNamespace.NUM);
            }

            @Override
            public Stream<TransformedAction<String>> transformStream(RankingRequest<String, String> request) {
                return request.actions().stream().map(this::toTransformedAction);
            }

            @Override
            public PreparedBatchStream<String> prepareBatchStream(RankingRequest<String, String> request) {
                return new PreparedBatchStream<>(
                        Stream.of(request.actions().stream().map(this::toTransformedAction).toList()),
                        fetchedResponses
                );
            }

            @Override
            public SortedSet<? extends Namespace> getUsedFeatures() {
                return usedFeatures;
            }

            private TransformedAction<String> toTransformedAction(AvailableAction<String> availableAction) {
                NamespacedRecordImpl<Namespace, Object> record = new NamespacedRecordImpl<>();
                record.put(TestNamespace.CAT, availableAction.action());
                record.put(TestNamespace.NUM, 1.0d);
                return TransformedAction.of(
                        availableAction.actionId(),
                        availableAction.action(),
                        record,
                        Map.of()
                );
            }
        };
    }

    private static final class ResponseContainerFactory
            extends CatBoostStreamingBulkScorerFactory<String, String> {
        private ResponseContainerFactory() {
            super(fetchedResponses -> new FeatureStoreResponseContainer(Map.copyOf(fetchedResponses)));
        }
    }
}
