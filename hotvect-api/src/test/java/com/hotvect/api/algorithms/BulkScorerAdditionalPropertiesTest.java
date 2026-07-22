package com.hotvect.api.algorithms;

import com.hotvect.api.data.AvailableAction;
import com.hotvect.api.data.FeatureStoreResponseContainer;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.transformation.ranking.ComputingBulkScorer;
import com.hotvect.api.transformation.ranking.ComputingRankingRequest;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BulkScorerAdditionalPropertiesTest {

    private static final class Action {
        private final Map<String, Object> additionalProperties;

        private Action(Map<String, Object> additionalProperties) {
            this.additionalProperties = additionalProperties;
        }

        public Map<String, Object> getAdditionalProperties() {
            return additionalProperties;
        }
    }

    private static final class OnlyApplyBulkScorer implements BulkScorer<Void, Action> {
        @Override
        public DoubleList apply(RankingRequest<Void, Action> rankingRequest) {
            return new DoubleArrayList(new double[]{1.0, 2.0});
        }
    }

    private static final class OnlyApplyComputingBulkScorer implements ComputingBulkScorer<Void, Action> {
        @Override
        public DoubleList apply(ComputingRankingRequest<Void, Action> rankingRequest) {
            return new DoubleArrayList(new double[]{1.0, 2.0});
        }
    }

    @Test
    void bulkScoreFromLegacyApplyDoesNotReadRawActionAdditionalProperties() {
        var a1 = new Action(Map.of("k", "v1"));
        var a2 = new Action(Map.of("k", "v2"));
        var request = rankingRequest(a1, a2);

        var scorer = new OnlyApplyBulkScorer();
        var decisions = scorer.bulkScore(request);

        assertEquals("a1", decisions.get(0).actionId());
        assertEquals("a2", decisions.get(1).actionId());
        assertEquals(Map.of(), decisions.get(0).additionalProperties());
        assertEquals(Map.of(), decisions.get(1).additionalProperties());
    }

    @Test
    void bulkScoreFromLegacyApplyUsesAvailableActionAdditionalProperties() {
        var a1 = new Action(Map.of("k", "raw-v1"));
        var a2 = new Action(Map.of("k", "raw-v2"));
        var request = RankingRequest.<Void, Action>ofAvailableActions(
                "ex",
                null,
                List.of(
                        AvailableAction.of("a1", a1, Map.of("k", "request-v1")),
                        AvailableAction.of("a2", a2, Map.of("k", "request-v2"))
                )
        );

        var scorer = new OnlyApplyBulkScorer();
        var decisions = scorer.bulkScore(request);

        assertEquals(Map.of("k", "request-v1"), decisions.get(0).additionalProperties());
        assertEquals(Map.of("k", "request-v2"), decisions.get(1).additionalProperties());
    }

    @Test
    void computingBulkScoreFromLegacyApplyDoesNotReadRawActionAdditionalProperties() {
        var a1 = new Action(Map.of("k", "v1"));
        var a2 = new Action(Map.of("k", "v2"));
        var request = rankingRequest(a1, a2);
        var computingRequest = new ComputingRankingRequest<Void, Action>(request, null, List.of());

        var scorer = new OnlyApplyComputingBulkScorer();
        var decisions = scorer.bulkScore(computingRequest);

        assertEquals("a1", decisions.get(0).actionId());
        assertEquals("a2", decisions.get(1).actionId());
        assertEquals(Map.of(), decisions.get(0).additionalProperties());
        assertEquals(Map.of(), decisions.get(1).additionalProperties());
    }

    @Test
    void computingScoreFromLegacyApplyDoesNotReadRawActionAdditionalProperties() {
        var a1 = new Action(Map.of("k", "v1"));
        var a2 = new Action(Map.of("k", "v2"));
        var request = rankingRequest(a1, a2);
        var computingRequest = new ComputingRankingRequest<Void, Action>(request, null, List.of());

        var scorer = new OnlyApplyComputingBulkScorer();
        var response = scorer.score(computingRequest);

        assertEquals(FeatureStoreResponseContainer.empty(), response.featureStoreResponseContainer());
        assertEquals("a1", response.decisions().get(0).actionId());
        assertEquals("a2", response.decisions().get(1).actionId());
        assertEquals(Map.of(), response.decisions().get(0).additionalProperties());
        assertEquals(Map.of(), response.decisions().get(1).additionalProperties());
    }

    private static RankingRequest<Void, Action> rankingRequest(Action a1, Action a2) {
        return RankingRequest.ofAvailableActions(
                "ex",
                null,
                List.of(
                        AvailableAction.of("a1", a1),
                        AvailableAction.of("a2", a2)
                )
        );
    }
}
