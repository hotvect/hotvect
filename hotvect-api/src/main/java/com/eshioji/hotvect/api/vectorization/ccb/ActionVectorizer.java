package com.eshioji.hotvect.api.vectorization.ccb;

import com.eshioji.hotvect.api.data.SparseVector;
import com.eshioji.hotvect.api.data.raw.ccb.Request;

import java.util.List;
import java.util.function.Function;

public interface ActionVectorizer<SHARED, ACTION> extends Function<Request<SHARED, ACTION>, List<SparseVector>> {
    @Override
    List<SparseVector> apply(Request<SHARED, ACTION> request);
}

