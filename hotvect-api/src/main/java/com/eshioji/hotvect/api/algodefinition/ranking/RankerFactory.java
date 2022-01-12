package com.eshioji.hotvect.api.algodefinition.ranking;

import com.eshioji.hotvect.api.algodefinition.common.AlgorithmFactory;
import com.eshioji.hotvect.api.algorithms.Ranker;
import com.eshioji.hotvect.api.vectorization.RankingVectorizer;

import java.io.InputStream;
import java.util.Map;
import java.util.function.BiFunction;

public interface RankerFactory<SHARED, ACTION> extends AlgorithmFactory<RankingVectorizer<SHARED, ACTION>, Ranker<SHARED, ACTION>> {
    @Override
    Ranker<SHARED, ACTION> apply(RankingVectorizer<SHARED, ACTION> vectorizer, Map<String, InputStream> parameters);
}
