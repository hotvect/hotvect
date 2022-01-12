package com.eshioji.hotvect.vw;

import com.eshioji.hotvect.api.algorithms.Scorer;
import com.eshioji.hotvect.core.score.ScorerImpl;
import com.eshioji.hotvect.api.vectorization.ScoringVectorizer;
import it.unimi.dsi.fastutil.ints.Int2DoubleMap;

import java.io.BufferedReader;

public class VwScorerSupplier<RECORD> {
    public Scorer<RECORD> apply(ScoringVectorizer<RECORD> scoringVectorizer, BufferedReader modelParameters) {
        Int2DoubleMap parameters = new VwModelImporter().apply(modelParameters);
        LogisticRegressionEstimator estimator = new LogisticRegressionEstimator(0, parameters);
        return new ScorerImpl<>(scoringVectorizer, estimator);
    }
}
