package com.hotvect.core.util;

import net.jqwik.api.AfterFailureMode;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@Deprecated(forRemoval = true)
class DoubleFormatUtilsTest {

    @Disabled
    @Test
    @Deprecated
    void knownEdgeCase(){
        formatDouble(0.0022225414992925554);
    }

    @Deprecated
    @Property(afterFailure = AfterFailureMode.SAMPLE_FIRST, tries = 100000)
    void formatDouble(@ForAll double d) {
        String expected = Double.toString(d);
        var sb = new StringBuilder();
        DoubleFormatUtils.formatDouble(d, 16, 16, sb);
        String actual = sb.toString();
        if (expected.equals(actual) || parsesToSame(expected, actual)) {
            // Pass
        } else {
            fail("Expected:" + expected + " but got " + actual);
        }
    }

    private boolean parsesToSame(String expected, String actual) {
        return Float.parseFloat(expected) == Float.parseFloat(actual);
    }

    @Property(afterFailure = AfterFailureMode.SAMPLE_FIRST, tries = 100000)
    void newFormatDouble(
            @ForAll @DoubleRange(min = -1.0e7, max = 1.0e7) double originalValue,
            @ForAll @IntRange(min = 6, max = 9) int precision
    ) {
        var sb = new StringBuilder();
        DoubleFormatUtils.format(originalValue, precision, sb);
        String actual = sb.toString();
        double recoveredValue = Double.parseDouble(actual);

        double diff = Math.abs(recoveredValue - originalValue);
        double relativeDiff = diff / Math.abs(originalValue);
        assertTrue(diff==0 || relativeDiff < 1.0e-9);
    }
}