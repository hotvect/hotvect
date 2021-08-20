package com.eshioji.hotvect.core.vectorization;

import com.eshioji.hotvect.api.data.SparseVector;

import java.util.function.Function;

public interface Vectorizer<R>
        extends Function<R, SparseVector> {
}

