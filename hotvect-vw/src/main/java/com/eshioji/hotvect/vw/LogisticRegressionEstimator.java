package com.eshioji.hotvect.vw;

import com.eshioji.hotvect.api.data.SparseVector;
import com.eshioji.hotvect.core.score.Estimator;
import it.unimi.dsi.fastutil.ints.Int2DoubleMap;

public class LogisticRegressionEstimator implements Estimator {
    private final double intercept;
    private final Int2DoubleMap weights;

    public LogisticRegressionEstimator(double intercept, Int2DoubleMap weights) {
        this.intercept = intercept;
        this.weights = weights;
    }

    @Override
    public double applyAsDouble(SparseVector featureVector) {
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
