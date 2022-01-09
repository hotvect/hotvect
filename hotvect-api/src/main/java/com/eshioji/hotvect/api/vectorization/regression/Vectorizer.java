package com.eshioji.hotvect.api.vectorization.regression;

import com.eshioji.hotvect.api.data.SparseVector;

import java.util.function.Function;

public interface Vectorizer<RECORD> extends Function<RECORD, SparseVector> {
    @Override
    SparseVector apply(RECORD toVectorize);
}

