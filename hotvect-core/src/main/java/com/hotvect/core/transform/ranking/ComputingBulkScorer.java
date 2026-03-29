package com.hotvect.core.transform.ranking;

import com.hotvect.api.algorithms.BulkScorer;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.scoring.BulkScoreResponse;

public interface ComputingBulkScorer<SHARED, ACTION> extends BulkScorer<SHARED, ACTION> {
    @Override
    BulkScoreResponse<ACTION> score(RankingRequest<SHARED, ACTION> rankingRequest);

    BulkScoreResponse<ACTION> score(ComputingRankingRequest<SHARED, ACTION> rankingRequest);
}
