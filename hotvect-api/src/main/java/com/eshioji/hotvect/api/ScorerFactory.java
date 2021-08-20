package com.eshioji.hotvect.api;

import com.eshioji.hotvect.api.scoring.Scorer;

import java.util.function.Supplier;

public interface ScorerFactory<R> extends Supplier<Scorer<R>> {
}
