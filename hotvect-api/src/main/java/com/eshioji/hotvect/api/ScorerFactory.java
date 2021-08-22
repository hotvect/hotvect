package com.eshioji.hotvect.api;

import com.eshioji.hotvect.api.scoring.Scorer;

import java.util.function.Function;
import java.util.function.Supplier;

public interface ScorerFactory<R> extends Function<Readable, Scorer<R>> {
    @Override
    Scorer<R> apply(Readable parameters);
}
