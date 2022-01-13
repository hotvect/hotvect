package com.hotvect.api.vectorization;

import com.hotvect.api.data.SparseVector;

import java.util.function.Function;

public interface Vectorizer<R>
        extends Function<R, SparseVector> {
    @Override
    SparseVector apply(R toVectorize);
}

