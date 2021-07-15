package com.eshioji.hotvect.core;

import com.eshioji.hotvect.api.data.raw.RawNamespace;
import com.eshioji.hotvect.api.scoring.Scorer;
import com.eshioji.hotvect.core.vectorization.Vectorizer;

import java.util.function.BiFunction;

public interface ScorerSupplier<IN extends Enum<IN> & RawNamespace> extends BiFunction<Vectorizer<IN>, Readable, Scorer<IN>> {
    @Override
    Scorer<IN> apply(Vectorizer<IN> vectorizer, Readable modelParameters);
}
