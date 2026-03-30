package com.hotvect.catboost;

import com.hotvect.api.data.Namespace;
import com.hotvect.api.data.ValueType;
import com.hotvect.api.data.common.NamespacedRecordImpl;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.ranking.TransformedAction;
import com.hotvect.api.data.scoring.ScoringDecision;
import com.hotvect.core.transform.ranking.StreamingRankingTransformer;
import com.hotvect.onlineutils.nativelibraries.catboost.HotvectCatBoostModel;
import org.junit.jupiter.api.Test;

import java.util.List;
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
            return request.availableActions().stream().map(this::toTransformedAction);
        }

        @Override
        public Stream<List<TransformedAction<String>>> transformBatchStream(RankingRequest<String, String> request) {
            return Stream.of(
                    request.availableActions().subList(0, 2).stream().map(this::toTransformedAction).toList(),
                    request.availableActions().subList(2, 4).stream().map(this::toTransformedAction).toList()
            );
        }

        @Override
        public SortedSet<? extends Namespace> getUsedFeatures() {
            return usedFeatures;
        }

        private TransformedAction<String> toTransformedAction(String action) {
            NamespacedRecordImpl<Namespace, Object> record = new NamespacedRecordImpl<>();
            record.put(TestNamespace.CAT, action);
            record.put(TestNamespace.NUM, 1.0d);
            return TransformedAction.of(action, record, java.util.Map.of("sourceAction", action));
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
                    "regression"
            );
            RankingRequest<String, String> request = new RankingRequest<>("example", "shared", List.of("a0", "a1", "a2", "a3"));

            List<ScoringDecision<String>> decisions = scorer.bulkScore(request);

            assertEquals(List.of("a0", "a1", "a2", "a3"), decisions.stream().map(ScoringDecision::action).toList());
            assertEquals(
                    List.of("a0", "a1", "a2", "a3"),
                    decisions.stream().map(decision -> decision.additionalProperties().get("sourceAction")).toList()
            );
        }
    }
}
