package com.hotvect.api.transformation.ranking;

import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.ranking.RankingResponse;

public interface MemoizableRanker<SHARED, ACTION> extends Ranker<SHARED, ACTION> {
    @Override
    RankingResponse<ACTION> rank(RankingRequest<SHARED, ACTION> rankingRequest);
    RankingResponse<ACTION> rank(MemoizedRankingRequest<SHARED, ACTION> rankingRequest);
}
