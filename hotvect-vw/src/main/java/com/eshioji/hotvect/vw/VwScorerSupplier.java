package com.eshioji.hotvect.vw;

import com.eshioji.hotvect.api.data.raw.RawNamespace;
import com.eshioji.hotvect.api.scoring.Scorer;
import com.eshioji.hotvect.core.ScorerSupplier;
import com.eshioji.hotvect.core.score.ScorerImpl;
import com.eshioji.hotvect.core.vectorization.Vectorizer;

public class VwScorerSupplier<R> implements ScorerSupplier<R> {
    @Override
    public Scorer<R> apply(Vectorizer<R> vectorizer, Readable modelParameters) {
        var parameters = new VwModelImporter().apply(modelParameters);
        var estimator = new LogisticRegressionEstimator(0, parameters);
        return new ScorerImpl<>(vectorizer, estimator);
    }
}
