package com.hotvect.core.util;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@Deprecated(forRemoval = true)
class DoubleFormatUtilsTest {

    @Disabled
    @Test
    @Deprecated
    void knownEdgeCase() {
        formatDouble(0.0022225414992925554);
    }

    @Deprecated
    @Test
    void formatDoubleCoversDeterministicSamples() {
        doubleValues().forEach(this::formatDouble);
    }

    @Deprecated
    void formatDouble(double d) {
        String expected = Double.toString(d);
        var sb = new StringBuilder();
        DoubleFormatUtils.formatDouble(d, 16, 16, sb);
        String actual = sb.toString();
        if (expected.equals(actual) || parsesToSame(expected, actual)) {
            return;
        }
        fail("Expected:" + expected + " but got " + actual);
    }

    private boolean parsesToSame(String expected, String actual) {
        return Float.parseFloat(expected) == Float.parseFloat(actual);
    }

    @Test
    void newFormatDoubleCoversDeterministicSamples() {
        double[] values = boundedDoubleValues();
        for (double originalValue : values) {
            for (int precision = 6; precision <= 9; precision++) {
                int currentPrecision = precision;
                var sb = new StringBuilder();
                DoubleFormatUtils.format(originalValue, currentPrecision, sb);
                String actual = sb.toString();
                double recoveredValue = Double.parseDouble(actual);

                double diff = Math.abs(recoveredValue - originalValue);
                double relativeDiff = diff / Math.abs(originalValue);
                assertTrue(
                        diff == 0 || relativeDiff < 1.0e-9,
                        () -> "value=" + originalValue + ", precision=" + currentPrecision + ", actual=" + actual
                );
            }
        }
    }

    private static DoubleStream doubleValues() {
        Random random = new Random(78491L);
        return DoubleStream.concat(
                DoubleStream.of(
                        -Double.MAX_VALUE,
                        -Math.PI,
                        -1.0,
                        -Double.MIN_VALUE,
                        0.0,
                        Double.MIN_VALUE,
                        1.0,
                        Math.PI,
                        Double.MAX_VALUE
                ),
                random.doubles(1_000, -1.0e7, 1.0e7)
        );
    }

    private static double[] boundedDoubleValues() {
        Random random = new Random(82561L);
        return DoubleStream.concat(
                DoubleStream.of(-1.0e7, -1_000.0, -1.25, 0.0, 1.25, 1_000.0, 1.0e7),
                IntStream.range(0, 1_000).mapToDouble(i -> random.nextInt(20_000_001) - 10_000_000)
        ).toArray();
    }
}
