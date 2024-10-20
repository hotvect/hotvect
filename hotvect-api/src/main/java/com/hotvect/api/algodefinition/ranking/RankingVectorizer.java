package com.hotvect.api.algodefinition.ranking;

import com.hotvect.api.algodefinition.common.Vectorizer;
import com.hotvect.api.data.FeatureNamespace;
import com.hotvect.api.data.SparseVector;
import com.hotvect.api.data.ranking.RankingRequest;

import java.util.List;
import java.util.SortedSet;
import java.util.function.Function;

public interface RankingVectorizer<SHARED, ACTION> extends Function<RankingRequest<SHARED, ACTION>, List<SparseVector>>, Vectorizer {
    @Override
    List<SparseVector> apply(RankingRequest<SHARED, ACTION> rankingRequest);
    SortedSet<? extends FeatureNamespace> getUsedFeatures();
}

