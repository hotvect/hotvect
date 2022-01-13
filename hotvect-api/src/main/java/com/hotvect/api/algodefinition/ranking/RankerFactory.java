package com.hotvect.api.algodefinition.ranking;

import com.hotvect.api.algodefinition.common.AlgorithmFactory;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.vectorization.RankingVectorizer;

import java.io.InputStream;
import java.util.Map;

public interface RankerFactory<SHARED, ACTION> extends AlgorithmFactory<RankingVectorizer<SHARED, ACTION>, Ranker<SHARED, ACTION>> {
    @Override
    Ranker<SHARED, ACTION> apply(RankingVectorizer<SHARED, ACTION> vectorizer, Map<String, InputStream> parameters);
}
