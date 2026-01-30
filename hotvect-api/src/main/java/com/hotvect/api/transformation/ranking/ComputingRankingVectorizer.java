package com.hotvect.api.transformation.ranking;

import com.hotvect.api.algodefinition.ranking.RankingVectorizer;
import com.hotvect.api.data.Namespace;
import com.hotvect.api.data.SparseVector;
import com.hotvect.api.data.ranking.RankingRequest;

import java.util.List;
import java.util.SortedSet;

@Deprecated(forRemoval = true)
public interface ComputingRankingVectorizer<SHARED, ACTION>  extends RankingVectorizer<SHARED, ACTION> {

    @Deprecated
    @Override
    default List<SparseVector> apply(RankingRequest<SHARED, ACTION> rankingRequest){
        ComputingRankingRequest<SHARED, ACTION> memoized = this.prepare(rankingRequest);
        return this.apply(memoized);
    }

    List<SparseVector> apply(ComputingRankingRequest<SHARED, ACTION> input);

    @Override
    SortedSet<? extends Namespace> getUsedFeatures();

    ComputingRankingRequest<SHARED,ACTION> prepare(RankingRequest<SHARED, ACTION> rankingRequest);
    ComputingRankingRequest<SHARED,ACTION> prepare(ComputingRankingRequest<SHARED, ACTION> computingRankingRequest);
}
