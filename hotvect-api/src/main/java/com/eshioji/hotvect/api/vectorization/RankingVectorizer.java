package com.eshioji.hotvect.api.vectorization;

import com.eshioji.hotvect.api.data.SparseVector;
import com.eshioji.hotvect.api.data.ranking.RankingRequest;

import java.util.List;
import java.util.function.Function;

public interface RankingVectorizer<SHARED, ACTION> extends Function<RankingRequest<SHARED, ACTION>, List<SparseVector>>, Vectorizer {
    @Override
    List<SparseVector> apply(RankingRequest<SHARED, ACTION> rankingRequest);
}

