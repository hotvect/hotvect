package com.eshioji.hotvect.api.algodefinition.regression;

import com.eshioji.hotvect.api.policies.Scorer;
import com.eshioji.hotvect.api.vectorization.regression.Vectorizer;

import java.io.InputStream;
import java.util.Map;
import java.util.function.BiFunction;

public interface ScorerFactory<RECORD> extends BiFunction<Vectorizer<RECORD>, Map<String, InputStream>, Scorer<RECORD>> {
    @Override
    Scorer<RECORD> apply(Vectorizer<RECORD> vectorizer, Map<String, InputStream> predictParameters);
}
