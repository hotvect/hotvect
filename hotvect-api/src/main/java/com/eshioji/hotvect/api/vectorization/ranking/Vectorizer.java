package com.eshioji.hotvect.api.vectorization.ranking;

import com.eshioji.hotvect.api.data.SparseVector;
import com.eshioji.hotvect.api.data.ranking.Request;

import java.util.List;
import java.util.function.Function;

public interface Vectorizer<SHARED, ACTION> extends Function<Request<SHARED, ACTION>, List<SparseVector>> {
    @Override
    List<SparseVector> apply(Request<SHARED, ACTION> request);
}

