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
        int[] numericalIndices = vector.getNumericalIndices();
        double[] numericalValues = vector.getNumericalValues();
        for (int j = 0; j < numericalIndices.length; j++) {
            int idx = numericalIndices[j];
            double w = weights.get(idx);
            double v = numericalValues[j];
            ret += (w * v);
        }

        int[] categoricalIndices = vector.getCategoricalIndices();
        for (int idx : categoricalIndices) {
            double w = weights.get(idx);
            ret += w;
        }
        return ret;
    }

}
