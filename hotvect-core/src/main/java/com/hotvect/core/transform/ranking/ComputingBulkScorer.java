package com.hotvect.core.transform.ranking;

import com.hotvect.api.algorithms.BulkScorer;
import com.hotvect.api.data.FeatureStoreResponseContainer;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.scoring.BulkScoreResponse;
import com.hotvect.api.data.scoring.ScoringDecision;

import java.util.List;

public interface ComputingBulkScorer<SHARED, ACTION> extends BulkScorer<SHARED, ACTION> {
    List<ScoringDecision<ACTION>> bulkScore(ComputingRankingRequest<SHARED, ACTION> rankingRequest);

    default BulkScoreResponse<ACTION> score(ComputingRankingRequest<SHARED, ACTION> rankingRequest) {
        return BulkScoreResponse.of(
                bulkScore(rankingRequest),
                FeatureStoreResponseContainer.empty()
        );
    }
}
