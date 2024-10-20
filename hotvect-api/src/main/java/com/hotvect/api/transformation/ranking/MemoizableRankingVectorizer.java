package com.hotvect.api.transformation.ranking;

import com.hotvect.api.algodefinition.ranking.RankingVectorizer;
import com.hotvect.api.data.FeatureNamespace;
import com.hotvect.api.data.SparseVector;
import com.hotvect.api.data.ranking.RankingRequest;

import java.util.List;
import java.util.SortedSet;

public interface MemoizableRankingVectorizer<SHARED, ACTION>  extends RankingVectorizer<SHARED, ACTION> {

    @Deprecated
    @Override
    default List<SparseVector> apply(RankingRequest<SHARED, ACTION> rankingRequest){
        MemoizedRankingRequest<SHARED, ACTION> memoized = this.memoize(rankingRequest);
        return this.apply(memoized);
    }

    List<SparseVector> apply(MemoizedRankingRequest<SHARED, ACTION> input);

    @Override
    SortedSet<? extends FeatureNamespace> getUsedFeatures();

    MemoizedRankingRequest<SHARED,ACTION> memoize(RankingRequest<SHARED, ACTION> rankingRequest);
    MemoizedRankingRequest<SHARED,ACTION> memoize(MemoizedRankingRequest<SHARED, ACTION> memoizedRankingRequest);
}

