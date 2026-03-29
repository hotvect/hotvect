package com.hotvect.core.transform.ranking;

import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.ranking.RankingResponse;
import com.hotvect.core.transform.ranking.ComputingRankingRequest;

public interface ComputingRanker<SHARED, ACTION> extends Ranker<SHARED, ACTION> {
    @Override
    RankingResponse<ACTION> rank(RankingRequest<SHARED, ACTION> rankingRequest);
    RankingResponse<ACTION> rank(ComputingRankingRequest<SHARED, ACTION> rankingRequest);
}
