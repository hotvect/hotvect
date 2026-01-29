package com.hotvect.onlineutils.util;

import java.util.concurrent.TimeUnit;

public final class MetricUtils {

    private MetricUtils() {}

    /**
     * Calculates the operation rate per second based on the number of operations
     * and the elapsed time between start and end timestamps.
     */
    public static double calculateRate(long startTimeNs, long endTimeNs, long operationsCount) {
        long elapsedNanos = endTimeNs - startTimeNs;
        double elapsedSeconds = elapsedNanos / (double) TimeUnit.SECONDS.toNanos(1);

        return operationsCount > 0 && elapsedSeconds > 0 ? operationsCount / elapsedSeconds : 0.0;
    }
}
