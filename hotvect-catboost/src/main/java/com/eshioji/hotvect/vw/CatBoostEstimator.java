package com.eshioji.hotvect.vw;

import ai.catboost.CatBoostModel;
import com.eshioji.hotvect.api.data.SparseVector;
import com.eshioji.hotvect.core.score.Estimator;

public class CatBoostEstimator implements Estimator {
    private final CatBoostModel model;

    public CatBoostEstimator(CatBoostModel model) {
        this.model = model;
    }

    @Override
    public double applyAsDouble(SparseVector featureVector) {
        model.predict();




        double coeff = calculateCoeff(featureVector);
        return 1 / (1 + Math.exp((-1) * (intercept + coeff)));
    }

    private double calculateCoeff(SparseVector vector) {
        double ret = 0;
        int[] indices = vector.indices();
        double[] values = vector.values();
        for (int j = 0; j < vector.indices().length; j++) {
            int idx = indices[j];
            double w = weights.get(idx);
            double v = values[j];
            ret += (w * v);
        }
        return ret;
    }

}
