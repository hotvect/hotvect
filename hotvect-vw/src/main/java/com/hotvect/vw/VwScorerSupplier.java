package com.hotvect.vw;

import com.hotvect.api.algodefinition.scoring.ScoringVectorizer;
import com.hotvect.api.algorithms.Scorer;
import com.hotvect.core.score.ScorerImpl;
import it.unimi.dsi.fastutil.ints.Int2DoubleMap;

import java.io.BufferedReader;

public class VwScorerSupplier<RECORD> {
    public Scorer<RECORD> apply(ScoringVectorizer<RECORD> scoringVectorizer, BufferedReader modelParameters) {
        Int2DoubleMap parameters = new VwModelImporter().apply(modelParameters);
        LogisticRegressionEstimator estimator = new LogisticRegressionEstimator(0, parameters);
        return new ScorerImpl<>(scoringVectorizer, estimator);
    }
}
