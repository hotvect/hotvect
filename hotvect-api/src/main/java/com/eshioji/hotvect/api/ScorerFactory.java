package com.eshioji.hotvect.api;

import com.eshioji.hotvect.api.scoring.Scorer;
import com.eshioji.hotvect.api.vectorization.Vectorizer;

import java.io.InputStream;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

public interface ScorerFactory<R> extends BiFunction<Vectorizer<R>, Map<String, InputStream>, Scorer<R>> {
    @Override
    Scorer<R> apply(Vectorizer<R> vectorizer, Map<String, InputStream> predictParameters);
}
