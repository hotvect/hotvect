package com.hotvect.catboost;

import com.hotvect.api.data.FeatureStoreResponseContainer;
import com.hotvect.api.data.Namespace;
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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

    private final ComputingRankingTransformer<String, String> transformer = new ComputingRankingTransformer<>() {
        private final SortedSet<Namespace> usedFeatures = new TreeSet<>(Namespace.alphabetical());

        {
            usedFeatures.add(TestNamespace.CAT);
            usedFeatures.add(TestNamespace.NUM);
        }

        @Override
        public SortedSet<Namespace> getUsedFeatures() {
            return usedFeatures;
        }

        @Override
        public ComputingRankingRequest<String, String> prepare(String exampleId, String shared, List<Computable<String>> actions) {
            RankingRequest<String, String> rankingRequest = new RankingRequest<>(
                    exampleId,
                    shared,
                    actions.stream().map(Computable::getOriginalInput).toList()
            );
            return toComputingRankingRequest(rankingRequest);
        }

        @Override
        public ComputingRankingRequest<String, String> prepare(RankingRequest<String, String> rankingRequest) {
            return toComputingRankingRequest(rankingRequest);
        }

        @Override
        public ComputingRankingRequest<String, String> prepare(ComputingRankingRequest<String, String> computingRankingRequest) {
            return computingRankingRequest;
        }

        @Override
        public List<TransformedAction<String>> transform(ComputingRankingRequest<String, String> rankingRequest) {
            return rankingRequest.candidates().stream().map(candidate -> {
                String action = candidate.getOriginalInput().second();
                NamespacedRecordImpl<Namespace, Object> record = new NamespacedRecordImpl<>();
                record.put(TestNamespace.CAT, action);
                record.put(TestNamespace.NUM, 1.0d);
                return TransformedAction.of(action, record, Map.of("sourceAction", action));
            }).toList();
        }

        @Override
        public List<TransformationMetadata> getTransformationMetadata() {
            return List.of();
        }

        private ComputingRankingRequest<String, String> toComputingRankingRequest(RankingRequest<String, String> rankingRequest) {
            Computing<RankingRequest<String, String>> computingShared = Computing.builder(rankingRequest).build();
            List<ComputingCandidate<String, String>> candidates = rankingRequest.availableActions().stream()
                    .map(action -> new ComputingCandidate<String, String>(
                            computingShared,
                            Computing.builder(action).build(),
                            null,
                            null,
                            null
                    ))
                    .toList();
            return new ComputingRankingRequest<>(rankingRequest, computingShared, candidates);
        }
    };

    @Test
    void forkedScoringKeepsActionsAlignedWithChunkLocalTransformations() throws Exception {
        try (HotvectCatBoostModel model = HotvectCatBoostModel.loadModel(
                CatBoostBulkScorerTest.class.getResourceAsStream("categorical_example_model.bin")
        )) {
            CatBoostBulkScorer<String, String> scorer = new CatBoostBulkScorer<>(transformer, model, 2, "regression");
            RankingRequest<String, String> request = new RankingRequest<>("example", "shared", List.of("a0", "a1", "a2", "a3"));

            List<ScoringDecision<String>> decisions = scorer.score(request).decisions();

            assertEquals(List.of("a0", "a1", "a2", "a3"), decisions.stream().map(ScoringDecision::action).toList());
            assertEquals(
                    List.of("a0", "a1", "a2", "a3"),
                    decisions.stream().map(decision -> decision.additionalProperties().get("sourceAction")).toList()
            );
        }
    }

    @Test
    void scoreOnComputingRequestReturnsFeatureStoreResponsesAndAlignedActions() throws Exception {
        try (HotvectCatBoostModel model = HotvectCatBoostModel.loadModel(
                CatBoostBulkScorerTest.class.getResourceAsStream("categorical_example_model.bin")
        )) {
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
            RankingRequest<String, String> request = new RankingRequest<>("example", "shared", List.of("a0", "a1", "a2", "a3"));

            var response = scorer.score(transformer.prepare(request));

            assertEquals(featureStoreResponseContainer, response.featureStoreResponseContainer());
            assertEquals(List.of("a0", "a1", "a2", "a3"), response.decisions().stream().map(ScoringDecision::action).toList());
            assertEquals(
                    List.of("a0", "a1", "a2", "a3"),
                    response.decisions().stream().map(decision -> decision.additionalProperties().get("sourceAction")).toList()
            );
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
