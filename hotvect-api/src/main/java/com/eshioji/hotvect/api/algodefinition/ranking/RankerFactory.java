package com.eshioji.hotvect.api.algodefinition.ranking;

import com.eshioji.hotvect.api.policies.Ranker;
import com.eshioji.hotvect.api.vectorization.ranking.Vectorizer;

import java.io.InputStream;
import java.util.Map;
import java.util.function.BiFunction;

public interface RankerFactory<SHARED, ACTION> extends BiFunction<Vectorizer<SHARED, ACTION>, Map<String, InputStream>, Ranker<SHARED, ACTION>> {
}
