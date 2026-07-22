package com.hotvect.catboost;

import com.hotvect.api.data.FeatureStoreResponseContainer;
import com.hotvect.api.data.Namespace;
import com.hotvect.api.data.AvailableAction;
import com.hotvect.api.data.ValueType;
import com.hotvect.api.data.featurestore.FeatureStoreResponse;
import com.hotvect.api.data.common.NamespacedRecordImpl;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.ranking.TransformedAction;
import com.hotvect.api.data.scoring.ScoringDecision;
import com.hotvect.core.transform.Computable;
import com.hotvect.core.transform.Computing;
import com.hotvect.core.transform.TransformationMetadata;
import com.hotvect.core.transform.ranking.ComputingCandidate;
import com.hotvect.core.transform.ranking.ComputingRankingRequest;
import com.hotvect.core.transform.ranking.ComputingRankingTransformer;
import com.hotvect.onlineutils.nativelibraries.catboost.HotvectCatBoostModel;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CatBoostBulkScorerTest {
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

    private static class RecordingTransformer implements ComputingRankingTransformer<String, String> {
        private final SortedSet<Namespace> usedFeatures = new TreeSet<>(Namespace.alphabetical());
        private int prepareRankingRequestCalls;
        private int prepareComputingRankingRequestCalls;

        {
            usedFeatures.add(TestNamespace.CAT);
            usedFeatures.add(TestNamespace.NUM);
        }

        @Override
        public SortedSet<Namespace> getUsedFeatures() {
            return usedFeatures;
        }

        @Override
        public ComputingRankingRequest<String, String> prepare(
                String exampleId,
                String shared,
                List<Computable<String>> actions
        ) {
            return toComputingRankingRequest(RankingTestData.requestFromComputableActions(exampleId, shared, actions));
        }

        @Override
        public ComputingRankingRequest<String, String> prepare(RankingRequest<String, String> rankingRequest) {
            prepareRankingRequestCalls++;
            return toComputingRankingRequest(rankingRequest);
        }

        @Override
        public ComputingRankingRequest<String, String> prepare(ComputingRankingRequest<String, String> computingRankingRequest) {
            prepareComputingRankingRequestCalls++;
            return computingRankingRequest;
        }

        @Override
        public List<TransformedAction<String>> transform(ComputingRankingRequest<String, String> rankingRequest) {
            return rankingRequest.candidates().stream().map(candidate -> {
                String action = candidate.getOriginalInput().second();
                NamespacedRecordImpl<Namespace, Object> record = new NamespacedRecordImpl<>();
                record.put(TestNamespace.CAT, action);
                record.put(TestNamespace.NUM, 1.0d);
                Map<String, Object> additionalProperties = new HashMap<>(candidate.additionalProperties());
                additionalProperties.put("sourceAction", action);
                return TransformedAction.of(transformedActionId(candidate), action, record, additionalProperties);
            }).toList();
        }

        protected String transformedActionId(ComputingCandidate<String, String> candidate) {
            return candidate.actionId();
        }

        @Override
        public List<TransformationMetadata> getTransformationMetadata() {
            return List.of();
        }

        private ComputingRankingRequest<String, String> toComputingRankingRequest(RankingRequest<String, String> rankingRequest) {
            Computing<RankingRequest<String, String>> computingShared = Computing.builder(rankingRequest).build();
            List<ComputingCandidate<String, String>> candidates = rankingRequest.actions().stream()
                    .map(action -> new ComputingCandidate<String, String>(
                            action.actionId(),
                            computingShared,
                            Computing.builder(action.action()).build(),
                            null,
                            null,
                            null,
                            action.additionalProperties()
                    ))
                    .toList();
            return new ComputingRankingRequest<>(rankingRequest, computingShared, candidates);
        }

        private int prepareRankingRequestCalls() {
            return prepareRankingRequestCalls;
        }

        private int prepareComputingRankingRequestCalls() {
            return prepareComputingRankingRequestCalls;
        }
    }

    @Test
    void rejectsMismatchedTransformedActionId() throws Exception {
        try (HotvectCatBoostModel model = HotvectCatBoostModel.loadModel(
                CatBoostBulkScorerTest.class.getResourceAsStream("categorical_example_model.bin")
        )) {
            RecordingTransformer transformer = new RecordingTransformer() {
                @Override
                protected String transformedActionId(ComputingCandidate<String, String> candidate) {
                    return "wrong-" + candidate.actionId();
                }
            };
            CatBoostBulkScorer<String, String> scorer = new CatBoostBulkScorer<>(transformer, model, 2, "regression");
            RankingRequest<String, String> request = RankingTestData.request("example", "shared", "a0");

            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> scorer.score(request)
            );

            assertEquals(
                    "CatBoost scorer returned action id wrong-a0 at position 0, expected a0",
                    error.getMessage()
            );
        }
    }

    @Test
    void forkedScoringKeepsActionsAlignedWithChunkLocalTransformations() throws Exception {
        try (HotvectCatBoostModel model = HotvectCatBoostModel.loadModel(
                CatBoostBulkScorerTest.class.getResourceAsStream("categorical_example_model.bin")
        )) {
            RecordingTransformer transformer = new RecordingTransformer();
            CatBoostBulkScorer<String, String> scorer = new CatBoostBulkScorer<>(transformer, model, 2, "regression");
            RankingRequest<String, String> request = RankingRequest.ofAvailableActions(
                    "example",
                    "shared",
                    List.of(
                            AvailableAction.of("a0", "a0", Map.of("requestSource", "a0")),
                            AvailableAction.of("a1", "a1", Map.of("requestSource", "a1")),
                            AvailableAction.of("a2", "a2", Map.of("requestSource", "a2")),
                            AvailableAction.of("a3", "a3", Map.of("requestSource", "a3"))
                    )
            );

            List<ScoringDecision<String>> decisions = scorer.score(request).decisions();

            assertEquals(List.of("a0", "a1", "a2", "a3"), decisions.stream().map(ScoringDecision::action).toList());
            assertEquals(List.of("a0", "a1", "a2", "a3"), decisions.stream().map(ScoringDecision::actionId).toList());
            assertEquals(
                    List.of("a0", "a1", "a2", "a3"),
                    decisions.stream().map(decision -> decision.additionalProperties().get("sourceAction")).toList()
            );
            assertEquals(
                    List.of("a0", "a1", "a2", "a3"),
                    decisions.stream().map(decision -> decision.additionalProperties().get("requestSource")).toList()
            );
        }
    }

    @Test
    void scorePreparesRankingRequestOnlyOnce() throws Exception {
        try (HotvectCatBoostModel model = HotvectCatBoostModel.loadModel(
                CatBoostBulkScorerTest.class.getResourceAsStream("categorical_example_model.bin")
        )) {
            RecordingTransformer transformer = new RecordingTransformer();
            CatBoostBulkScorer<String, String> scorer = new CatBoostBulkScorer<>(transformer, model, 2, "regression");
            RankingRequest<String, String> request = RankingTestData.request("example", "shared", "a0", "a1", "a2", "a3");

            scorer.score(request);

            assertEquals(1, transformer.prepareRankingRequestCalls());
            assertEquals(0, transformer.prepareComputingRankingRequestCalls());
        }
    }

    @Test
    void scoreOnComputingRequestReturnsFeatureStoreResponsesAndAlignedActions() throws Exception {
        try (HotvectCatBoostModel model = HotvectCatBoostModel.loadModel(
                CatBoostBulkScorerTest.class.getResourceAsStream("categorical_example_model.bin")
        )) {
            RecordingTransformer transformer = new RecordingTransformer();
            FeatureStoreResponseContainer featureStoreResponseContainer = new FeatureStoreResponseContainer(
                    Map.of("debug_view", new EmptyFeatureStoreResponse())
            );
            CatBoostBulkScorer<String, String> scorer = new CatBoostBulkScorer<>(
                    transformer,
                    model,
                    2,
                    "regression",
                    _request -> featureStoreResponseContainer
            );
            RankingRequest<String, String> request = RankingTestData.request("example", "shared", "a0", "a1", "a2", "a3");

            var response = scorer.score(transformer.prepare(request));

            assertEquals(featureStoreResponseContainer, response.featureStoreResponseContainer());
            assertEquals(List.of("a0", "a1", "a2", "a3"), response.decisions().stream().map(ScoringDecision::action).toList());
            assertEquals(List.of("a0", "a1", "a2", "a3"), response.decisions().stream().map(ScoringDecision::actionId).toList());
            assertEquals(
                    List.of("a0", "a1", "a2", "a3"),
                    response.decisions().stream().map(decision -> decision.additionalProperties().get("sourceAction")).toList()
            );
            assertEquals(1, transformer.prepareRankingRequestCalls());
            assertEquals(0, transformer.prepareComputingRankingRequestCalls());
        }
    }

    @Test
    void transformedActionScorerSupportsBooleanCategoricals() throws Exception {
        try (HotvectCatBoostModel model = HotvectCatBoostModel.loadModel(
                CatBoostBulkScorerTest.class.getResourceAsStream("categorical_example_model.bin")
        )) {
            SortedSet<Namespace> usedFeatures = new TreeSet<>(Namespace.alphabetical());
            usedFeatures.add(TestNamespace.CAT);
            usedFeatures.add(TestNamespace.NUM);

            NamespacedRecordImpl<Namespace, Object> record = new NamespacedRecordImpl<>();
            record.put(TestNamespace.CAT, true);
            record.put(TestNamespace.NUM, 1.0d);

            CatBoostTransformedActionScorer<String> scorer = new CatBoostTransformedActionScorer<>(
                    usedFeatures,
                    model,
                    "regression"
            );

            List<ScoringDecision<String>> decisions = scorer.scoreTransformed(List.of(TransformedAction.of("sku-a0", "a0", record)));

            assertEquals(1, decisions.size());
            assertEquals("sku-a0", decisions.getFirst().actionId());
            assertEquals("a0", decisions.getFirst().action());
        }
    }

    private static final class EmptyFeatureStoreResponse implements FeatureStoreResponse {
        @Override
        public Map<String, Object> getEntity(Map<String, Object> entityId) {
            return null;
        }

        @Override
        public Map<Map<String, Object>, Map<String, Object>> getAllEntities() {
            return Map.of();
        }

        @Override
        public Optional<String> getRequestFailure() {
            return Optional.empty();
        }
    }
}
