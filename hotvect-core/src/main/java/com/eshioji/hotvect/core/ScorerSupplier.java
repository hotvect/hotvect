package com.eshioji.hotvect.core;

import com.eshioji.hotvect.api.data.raw.RawNamespace;
import com.eshioji.hotvect.api.scoring.Scorer;
import com.eshioji.hotvect.core.vectorization.Vectorizer;

import java.util.function.BiFunction;

public interface ScorerSupplier<R> extends BiFunction<Vectorizer<R>, Readable, Scorer<R>> {
    @Override
    Scorer<R> apply(Vectorizer<R> vectorizer, Readable modelParameters);
}
