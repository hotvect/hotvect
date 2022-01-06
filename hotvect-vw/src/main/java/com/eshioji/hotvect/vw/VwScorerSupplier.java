package com.eshioji.hotvect.vw;

import com.eshioji.hotvect.api.policies.Scorer;
import com.eshioji.hotvect.core.score.ScorerImpl;
import com.eshioji.hotvect.api.vectorization.regression.Vectorizer;
import it.unimi.dsi.fastutil.ints.Int2DoubleMap;

import java.io.BufferedReader;

public class VwScorerSupplier<R> {
    public Scorer<R> apply(Vectorizer<R> vectorizer, BufferedReader modelParameters) {
        Int2DoubleMap parameters = new VwModelImporter().apply(modelParameters);
        LogisticRegressionEstimator estimator = new LogisticRegressionEstimator(0, parameters);
        return new ScorerImpl<>(vectorizer, estimator);
    }
}
