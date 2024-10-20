package com.hotvect.core.transform.ranking;

import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.ranking.RankingResponse;
import com.hotvect.api.transformation.ranking.MemoizedRankingRequest;

public abstract class MemoizedRanker<SHARED, ACTION> implements Ranker<SHARED, ACTION> {
    private final MemoizingRankingTransformer<SHARED, ACTION> transformer;

    protected MemoizedRanker(MemoizingRankingTransformer<SHARED, ACTION> transformer) {
        this.transformer = transformer;
    }

    @Override
    public final RankingResponse<ACTION> rank(RankingRequest<SHARED, ACTION> rankingRequest) {
        var memoized = transformer.memoize(rankingRequest);
        return this.memoizedRank(memoized);
    }

    public abstract RankingResponse<ACTION> memoizedRank(MemoizedRankingRequest<SHARED, ACTION> memoizedRankingRequest);

}
