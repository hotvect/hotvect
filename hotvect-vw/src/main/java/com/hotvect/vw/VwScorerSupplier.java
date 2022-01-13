package com.hotvect.vw;

import com.hotvect.api.scoring.Scorer;
import com.hotvect.core.score.ScorerImpl;
import com.hotvect.api.vectorization.Vectorizer;
import it.unimi.dsi.fastutil.ints.Int2DoubleMap;

import java.io.BufferedReader;

public class VwScorerSupplier<R> {
    public Scorer<R> apply(Vectorizer<R> vectorizer, BufferedReader modelParameters) {
        Int2DoubleMap parameters = new VwModelImporter().apply(modelParameters);
        LogisticRegressionEstimator estimator = new LogisticRegressionEstimator(0, parameters);
        return new ScorerImpl<>(vectorizer, estimator);
    }
}
