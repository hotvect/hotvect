package com.eshioji.hotvect.api.algorithms;

import com.eshioji.hotvect.api.data.ranking.RankingDecision;
import com.eshioji.hotvect.api.data.ranking.RankingRequest;

import java.util.List;
import java.util.function.Function;

public interface Ranker<SHARED, ACTION> extends Function<RankingRequest<SHARED, ACTION>, List<? extends RankingDecision>>, Algorithm{
    @Override
    List<? extends RankingDecision> apply(RankingRequest<SHARED, ACTION> rankingRequest);
}
