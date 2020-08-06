package com.eshioji.hotvect.core.score;


import com.eshioji.hotvect.api.data.SparseVector;

import java.util.function.ToDoubleFunction;

public interface Estimator extends ToDoubleFunction<SparseVector> {
    @Override
    double applyAsDouble(SparseVector featureVector);
}
