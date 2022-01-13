package com.hotvect.api.vectorization;

import com.hotvect.api.data.SparseVector;

import java.util.function.Function;

public interface ScoringVectorizer<RECORD> extends Function<RECORD, SparseVector>, Vectorizer {
    @Override
    SparseVector apply(RECORD toVectorize);
}

