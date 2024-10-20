package com.hotvect.api.transformation.memoization;


import java.io.Serializable;
import java.util.function.Function;

public interface InteractingComputation<SHARED, ACTION, V> extends Function<ComputingCandidate<SHARED, ACTION>, V>, Serializable {
    @Override
    V apply(ComputingCandidate<SHARED, ACTION> computingCandidate);
}
