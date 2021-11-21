package com.eshioji.hotvect.api;

import com.eshioji.hotvect.api.scoring.Scorer;
import com.eshioji.hotvect.api.vectorization.Vectorizer;

import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

public interface ScorerFactory<R> extends BiFunction<Vectorizer<R>, Readable, Scorer<R>> {
    @Override
    Scorer<R> apply(Vectorizer<R> vectorizer, Readable parameter);
}
