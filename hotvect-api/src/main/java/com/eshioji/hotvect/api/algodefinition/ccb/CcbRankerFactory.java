package com.eshioji.hotvect.api.algodefinition.ccb;

import com.eshioji.hotvect.api.algodefinition.common.AlgorithmFactory;
import com.eshioji.hotvect.api.algorithms.CcbRanker;
import com.eshioji.hotvect.api.vectorization.RankingVectorizer;

import java.io.InputStream;
import java.util.Map;
import java.util.function.BiFunction;

public interface CcbRankerFactory<SHARED, ACTION> extends AlgorithmFactory<RankingVectorizer<SHARED, ACTION>, CcbRanker<SHARED, ACTION>> {
    @Override
    CcbRanker<SHARED, ACTION> apply(RankingVectorizer<SHARED, ACTION> vectorizer, Map<String, InputStream> parameters);
}
