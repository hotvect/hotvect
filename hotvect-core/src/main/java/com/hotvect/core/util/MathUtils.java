package com.hotvect.core.util;

import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import it.unimi.dsi.util.XoShiRo256PlusPlusRandomGenerator;
import org.apache.commons.math3.exception.NotANumberException;
import org.apache.commons.math3.exception.NotFiniteNumberException;
import org.apache.commons.math3.exception.NotPositiveException;
import org.apache.commons.math3.random.RandomGenerator;
import org.apache.commons.math3.util.MathArrays;

import java.util.Arrays;

public class MathUtils {
    public static class Draw {
        public final int index;
        public final double probability;

        public Draw(int index, double probability) {
            this.index = index;
            this.probability = probability;
        }
    }

    private static final ThreadLocal<RandomGenerator> THREAD_LOCAL_RANDOM = ThreadLocal.withInitial(XoShiRo256PlusPlusRandomGenerator::new);

    private MathUtils() {
    }

    /* The below code was adapted from https://github.com/apache/commons-math/blob/3.6.1-release/src/main/java/org/apache/commons/math3/distribution/EnumeratedDistribution.java
     * Modified by Enno Shioji
     * Code licensed under http://www.apache.org/licenses/LICENSE-2.0
     */
    /**
     * @param scores
     * @return
     */
    public static Draw weightedDraw(DoubleList scores) {
        if(scores.size() == 1){
            return new Draw(0, 1.0);
        }

        DoubleList probabilities = normalizeScores(scores);

        double[] cumulativeProbabilities = new double[probabilities.size()];
        double sum = 0;
        for (int i = 0; i < probabilities.size(); i++) {
            sum += probabilities.getDouble(i);
            cumulativeProbabilities[i] = sum;
        }

        double randomValue = THREAD_LOCAL_RANDOM.get().nextDouble();
        int index = Arrays.binarySearch(cumulativeProbabilities, randomValue);
        if (index < 0) {
            index = -index - 1;
        }

        if (index < probabilities.size() && randomValue < cumulativeProbabilities[index]) {
            return new Draw(index, probabilities.getDouble(index));
        }

        /* This should never happen, but it ensures we will return a correct
         * object in case there is some floating point inequality problem
         * wrt the cumulative probabilities. */
        int idx = cumulativeProbabilities.length - 1;
        return new Draw(idx, probabilities.getDouble(idx));
    }

    public static DoubleList normalizeScores(DoubleList scores){
        final double[] probs = new double[scores.size()];

        for (int i = 0; i < scores.size(); i++) {
            double p = scores.getDouble(i);
            if (p < 0) {
                throw new NotPositiveException(p);
            }
            if (Double.isInfinite(p)) {
                throw new NotFiniteNumberException(p);
            }
            if (Double.isNaN(p)) {
                throw new NotANumberException();
            }

            // If something has a 0.0 probability, we assign it a very small probability
            if(p == 0.0){
                p = Double.MIN_NORMAL;
            }
            probs[i] = p;
        }

        return DoubleArrayList.wrap(MathArrays.normalizeArray(probs, 1.0));

    }

    public static double sigmoid(double x) {
        return 1 / (1 + Math.exp(-x));
    }



}
