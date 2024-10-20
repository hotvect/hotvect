package com.hotvect.offlineutils.util;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;


class MathUtilsTest {

    @Test
    void meanWithConfidenceIntervals() {
        var input = new double[]{19.1, 11.6, 14.7, 18.5, 10.2, 28.7, 16.1, 15.3, 13.9, 13.5, 12.0, 7.7, 20.7, 17.2, 8.6, 19.0, 24.2, 20.9, 17.3, 21.3};
        var actual = MathUtils.meanWithConfidenceIntervals(Arrays.stream(input).boxed().collect(Collectors.toList()), 0.95);
        assertEquals(14.05153, actual.get("lower"), 0.00001);
        assertEquals(18.99846, actual.get("upper"), 0.00001);
        assertEquals(16.525, actual.get("mean"), 0.00001);
    }
}