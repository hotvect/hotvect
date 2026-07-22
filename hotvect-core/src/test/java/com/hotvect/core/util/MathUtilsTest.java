package com.hotvect.core.util;

import com.google.common.collect.Maps;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.ints.Int2DoubleArrayMap;
import it.unimi.dsi.fastutil.ints.Int2DoubleMap;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class MathUtilsTest {

    @ParameterizedTest
    @MethodSource("requests")
    void apply(double[] request) {
        double totalScore = Arrays.stream(request).sum();
        Int2DoubleMap idx2Score = new Int2DoubleArrayMap(IntStream.range(0, request.length).toArray(), request);
        Int2DoubleMap frequency = new Int2DoubleArrayMap();

        for (int i = 0; i < 500_000; i++) {
            var draw = MathUtils.weightedDraw(new DoubleArrayList(request));
            frequency.merge(draw.index(), 1.0, Double::sum);
            var score = idx2Score.get(draw.index());
            assertEquals(score / totalScore, draw.probability(), 0.005);
        }

        var totalDraws = frequency.values().doubleStream().sum();
        assertEquals(500_000, totalDraws);

        var ratios = Maps.transformValues(frequency, d -> d / totalDraws);
        ratios.forEach((key, value) -> {
            var expected = idx2Score.get(key.intValue()) / totalScore;
            var actual = frequency.get(key.intValue()) / totalDraws;
            assertEquals(expected, actual, 0.005);
        });
    }

    private static Stream<Arguments> requests() {
        return Stream.of(
                Arguments.of((Object) new double[]{1.0}),
                Arguments.of((Object) new double[]{0.001, 0.1}),
                Arguments.of((Object) new double[]{1.0, 10.0, 100.0}),
                Arguments.of((Object) new double[]{0.0, 1.0, 10.0})
        );
    }
}
