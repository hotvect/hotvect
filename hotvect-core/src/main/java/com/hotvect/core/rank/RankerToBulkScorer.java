package com.hotvect.core.rank;

import com.hotvect.api.algorithms.BulkScorer;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.data.ranking.RankingResponse;
import com.hotvect.api.data.ranking.RankingDecision;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.scoring.BulkScoreResponse;
import com.hotvect.api.data.scoring.ScoringDecision;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.doubles.DoubleList;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility class to convert a ranker into a bulk scorer.
 * This is necessary because we haven't started using {@link BulkScorer} yet.
 * @param <SHARED>
 * @param <ACTION>
 */
public class RankerToBulkScorer<SHARED, ACTION> implements BulkScorer<SHARED, ACTION> {
    private final Ranker<SHARED, ACTION> ranker;
    public RankerToBulkScorer(Ranker<SHARED, ACTION> ranker) {
        this.ranker = ranker;
    }

    @Override
    public BulkScoreResponse<ACTION> score(RankingRequest<SHARED, ACTION> rankingRequest) {
        RankingResponse<ACTION> rankingResponse = this.ranker.rank(rankingRequest);
        List<RankingDecision<ACTION>> scoredAndRanked = new ArrayList<>(rankingResponse.decisions());

        double[] scores = new double[scoredAndRanked.size()];
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object>[] decisionAdditionalProperties = new java.util.Map[scores.length];
        for (RankingDecision<ACTION> rankingDecision : scoredAndRanked) {
            int actionIdx = rankingDecision.getActionIndex();
            scores[actionIdx] = rankingDecision.score();
            decisionAdditionalProperties[actionIdx] = rankingDecision.additionalProperties();
        }

        List<ScoringDecision<ACTION>> decisions = new ArrayList<>(scores.length);
        for (int i = 0; i < scores.length; i++) {
            decisions.add(ScoringDecision.of(rankingRequest.availableActions().get(i), scores[i], decisionAdditionalProperties[i]));
        }

        return BulkScoreResponse.of(
                decisions,
                rankingResponse.featureStoreResponseContainer(),
                rankingResponse.additionalProperties()
        );
    }

    @Override
    public DoubleList apply(RankingRequest<SHARED, ACTION> rankingRequest) {
        List<RankingDecision<ACTION>> scoredAndRanked = new ArrayList<>(this.ranker.rank(rankingRequest).decisions());
        double[] scores = new double[scoredAndRanked.size()];
        for (RankingDecision<ACTION> rankingDecision : scoredAndRanked) {
            int actionIdx = rankingDecision.getActionIndex();
            scores[actionIdx] = rankingDecision.score();
        }
        return DoubleArrayList.wrap(scores);
    }

    @Override
    public List<ScoringDecision<ACTION>> bulkScore(RankingRequest<SHARED, ACTION> rankingRequest) {
        // Preserve decision additionalProperties (e.g., feature-audit data) even on the legacy API.
        return score(rankingRequest).decisions();
    }
}
