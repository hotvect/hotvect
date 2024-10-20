package com.hotvect.offlineutils.util;

import com.google.common.collect.ImmutableMap;
import org.apache.commons.math3.distribution.TDistribution;
import org.apache.commons.math3.stat.descriptive.SummaryStatistics;

import java.util.List;
import java.util.Map;

public class MathUtils {
    private MathUtils(){}
    public static Map<String, Double> meanWithConfidenceIntervals(List<Double> observations, double level){
        SummaryStatistics stats = new SummaryStatistics();
        observations.forEach(stats::addValue);
        double ci = calcMeanCI(stats, level);
        double lower = stats.getMean() - ci;
        double upper = stats.getMean() + ci;
        return ImmutableMap.of(
                "upper", upper,
                "mean", stats.getMean(),
                "lower", lower
        );
    }


    private static double calcMeanCI(SummaryStatistics stats, double level) {
            TDistribution tDist = new TDistribution(stats.getN() - 1);
            double critVal = tDist.inverseCumulativeProbability(1.0 - (1 - level) / 2);
            return critVal * stats.getStandardDeviation() / Math.sqrt(stats.getN());
    }

}
