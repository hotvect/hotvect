package com.eshioji.hotvect.api.algorithms;

import com.eshioji.hotvect.api.data.ccb.CcbRankingDecision;
import com.eshioji.hotvect.api.data.ranking.RankingRequest;

import java.util.List;

public interface CcbRanker<SHARED, ACTION> extends Ranker<SHARED, ACTION> {
    @Override
    List<CcbRankingDecision> apply(RankingRequest<SHARED, ACTION> rankingRequest);
}
