package com.hotvect.api.algorithms;

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
    void bulkScoreFromLegacyApplyPreservesActionAdditionalProperties() {
        var a1 = new Action(Map.of("k", "v1"));
        var a2 = new Action(Map.of("k", "v2"));
        var request = new RankingRequest<Void, Action>("ex", null, List.of(a1, a2));

        var scorer = new OnlyApplyBulkScorer();
        var decisions = scorer.bulkScore(request);

        assertEquals(Map.of("k", "v1"), decisions.get(0).additionalProperties());
        assertEquals(Map.of("k", "v2"), decisions.get(1).additionalProperties());
    }

    @Test
    void computingBulkScoreFromLegacyApplyPreservesActionAdditionalProperties() {
        var a1 = new Action(Map.of("k", "v1"));
        var a2 = new Action(Map.of("k", "v2"));
        var request = new RankingRequest<Void, Action>("ex", null, List.of(a1, a2));
        var computingRequest = new ComputingRankingRequest<Void, Action>(request, null, List.of());

        var scorer = new OnlyApplyComputingBulkScorer();
        var decisions = scorer.bulkScore(computingRequest);

        assertEquals(Map.of("k", "v1"), decisions.get(0).additionalProperties());
        assertEquals(Map.of("k", "v2"), decisions.get(1).additionalProperties());
    }

    @Test
    void computingScoreFromLegacyApplyPreservesActionAdditionalProperties() {
        var a1 = new Action(Map.of("k", "v1"));
        var a2 = new Action(Map.of("k", "v2"));
        var request = new RankingRequest<Void, Action>("ex", null, List.of(a1, a2));
        var computingRequest = new ComputingRankingRequest<Void, Action>(request, null, List.of());

        var scorer = new OnlyApplyComputingBulkScorer();
        var response = scorer.score(computingRequest);

        assertEquals(FeatureStoreResponseContainer.empty(), response.featureStoreResponseContainer());
        assertEquals(Map.of("k", "v1"), response.decisions().get(0).additionalProperties());
        assertEquals(Map.of("k", "v2"), response.decisions().get(1).additionalProperties());
    }
}
