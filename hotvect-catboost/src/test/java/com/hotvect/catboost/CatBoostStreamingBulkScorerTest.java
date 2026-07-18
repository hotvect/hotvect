package com.hotvect.catboost;

import com.hotvect.api.data.Namespace;
import com.hotvect.api.data.AvailableAction;
import com.hotvect.api.data.ValueType;
import com.hotvect.api.data.common.NamespacedRecordImpl;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.ranking.TransformedAction;
import com.hotvect.api.data.scoring.ScoringDecision;
import com.hotvect.core.transform.ranking.PreparedBatchStream;
import com.hotvect.core.transform.ranking.StreamingRankingTransformer;
import com.hotvect.onlineutils.nativelibraries.catboost.HotvectCatBoostModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CatBoostStreamingBulkScorerTest {
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

    private final StreamingRankingTransformer<String, String> transformer = new StreamingRankingTransformer<>() {
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
            return new PreparedBatchStream<>(Stream.of(
                    request.actions().subList(0, 2).stream().map(this::toTransformedAction).toList(),
                    request.actions().subList(2, 4).stream().map(this::toTransformedAction).toList()
            ), java.util.Map.of());
        }

        @Override
        public SortedSet<? extends Namespace> getUsedFeatures() {
            return usedFeatures;
        }

        private TransformedAction<String> toTransformedAction(AvailableAction<String> availableAction) {
            String action = availableAction.action();
            NamespacedRecordImpl<Namespace, Object> record = new NamespacedRecordImpl<>();
            record.put(TestNamespace.CAT, action);
            record.put(TestNamespace.NUM, 1.0d);
            return TransformedAction.of(
                    availableAction.actionId(),
                    action,
                    record,
                    java.util.Map.of("sourceAction", action)
            );
        }
    };

    @Test
    void streamingScoringKeepsActionsAndAdditionalPropertiesAligned() throws Exception {
        try (HotvectCatBoostModel model = HotvectCatBoostModel.loadModel(
                CatBoostStreamingBulkScorerTest.class.getResourceAsStream("categorical_example_model.bin")
        )) {
            CatBoostStreamingBulkScorer<String, String> scorer = new CatBoostStreamingBulkScorer<>(
                    transformer,
                    model,
                    "regression",
                    true
            );
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

            List<ScoringDecision<String>> decisions = scorer.bulkScore(request);

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
    void sequentialAndParallelBatchScoringProduceEquivalentOutputs() throws Exception {
        try (HotvectCatBoostModel modelParallel = HotvectCatBoostModel.loadModel(
                CatBoostStreamingBulkScorerTest.class.getResourceAsStream("categorical_example_model.bin")
        );
             HotvectCatBoostModel modelSequential = HotvectCatBoostModel.loadModel(
                     CatBoostStreamingBulkScorerTest.class.getResourceAsStream("categorical_example_model.bin")
             )) {
            RankingRequest<String, String> request = RankingTestData.request("example", "shared", "a0", "a1", "a2", "a3");

            List<ScoringDecision<String>> parallelDecisions = new CatBoostStreamingBulkScorer<>(
                    transformer,
                    modelParallel,
                    "regression",
                    true
            ).bulkScore(request);
            List<ScoringDecision<String>> sequentialDecisions = new CatBoostStreamingBulkScorer<>(
                    transformer,
                    modelSequential,
                    "regression",
                    false
            ).bulkScore(request);

            assertEquals(
                    parallelDecisions.stream().map(ScoringDecision::action).toList(),
                    sequentialDecisions.stream().map(ScoringDecision::action).toList()
            );
            assertEquals(
                    parallelDecisions.stream().map(ScoringDecision::actionId).toList(),
                    sequentialDecisions.stream().map(ScoringDecision::actionId).toList()
            );
            assertEquals(
                    parallelDecisions.stream().map(ScoringDecision::score).toList(),
                    sequentialDecisions.stream().map(ScoringDecision::score).toList()
            );
            assertEquals(
                    parallelDecisions.stream().map(ScoringDecision::additionalProperties).toList(),
                    sequentialDecisions.stream().map(ScoringDecision::additionalProperties).toList()
            );
        }
    }
}
