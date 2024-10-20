package com.hotvect.api.algorithms;

import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.ranking.RankingResponse;

public interface Ranker<SHARED, ACTION> extends Algorithm{
    RankingResponse<ACTION> rank(RankingRequest<SHARED, ACTION> rankingRequest);
}
