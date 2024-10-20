package com.hotvect.api.algorithms;

import com.hotvect.api.data.ranking.RankingRequest;
import it.unimi.dsi.fastutil.doubles.DoubleList;

import java.util.function.Function;

public interface BulkScorer<SHARED, ACTION> extends Function<RankingRequest<SHARED, ACTION>, DoubleList>, Algorithm {
    @Override
    DoubleList apply(RankingRequest<SHARED, ACTION> rankingRequest);
}
