package com.hotvect.api.transformation.ranking;

import com.hotvect.api.algodefinition.ranking.RankingTransformer;
import com.hotvect.api.data.FeatureNamespace;
import com.hotvect.api.data.RawValue;
import com.hotvect.api.data.common.NamespacedRecord;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.transformation.memoization.Computing;

import java.util.List;
import java.util.SortedSet;

public interface MemoizableRankingTransformer<SHARED, ACTION>  extends RankingTransformer<SHARED, ACTION> {

    /**
     * Please prefer to call {@link #apply(MemoizedRankingRequest)} for better performance
     */
    @Deprecated
    @Override
    default List<NamespacedRecord<FeatureNamespace, RawValue>> apply(RankingRequest<SHARED, ACTION> rankingRequest){
        MemoizedRankingRequest<SHARED, ACTION> memoized = this.memoize(rankingRequest);
        return this.apply(memoized);
    }

    List<NamespacedRecord<FeatureNamespace, RawValue>> apply(MemoizedRankingRequest<SHARED, ACTION> input);

    @Override
    SortedSet<FeatureNamespace> getUsedFeatures();

    MemoizedRankingRequest<SHARED, ACTION> memoize(String exampleId, SHARED shared, List<Computing<ACTION>> actions);

    MemoizedRankingRequest<SHARED,ACTION> memoize(RankingRequest<SHARED, ACTION> rankingRequest);
    MemoizedRankingRequest<SHARED,ACTION> memoize(MemoizedRankingRequest<SHARED, ACTION> memoizedRankingRequest);
}

