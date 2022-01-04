package com.eshioji.hotvect.api.algodefinition.regression;

import com.eshioji.hotvect.api.policies.Scorer;
import com.eshioji.hotvect.api.vectorization.Vectorizer;

import java.io.InputStream;
import java.util.Map;
import java.util.function.BiFunction;

public interface ScorerFactory<R> extends BiFunction<Vectorizer<R>, Map<String, InputStream>, Scorer<R>> {
    @Override
    Scorer<R> apply(Vectorizer<R> vectorizer, Map<String, InputStream> predictParameters);
}
