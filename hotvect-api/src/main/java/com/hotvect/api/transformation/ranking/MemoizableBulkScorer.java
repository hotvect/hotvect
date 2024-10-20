package com.hotvect.api.transformation.ranking;

import com.hotvect.api.algorithms.BulkScorer;
import com.hotvect.api.data.ranking.RankingRequest;
import it.unimi.dsi.fastutil.doubles.DoubleList;

public interface MemoizableBulkScorer<SHARED, ACTION> extends BulkScorer<SHARED, ACTION> {
    @Override
    DoubleList apply(RankingRequest<SHARED, ACTION> rankingRequest);
    DoubleList apply(MemoizedRankingRequest<SHARED, ACTION> rankingRequest);



}
