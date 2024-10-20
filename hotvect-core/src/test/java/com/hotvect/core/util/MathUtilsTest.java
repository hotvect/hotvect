package com.hotvect.core.util;

import com.google.common.collect.Maps;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.ints.Int2DoubleArrayMap;
import it.unimi.dsi.fastutil.ints.Int2DoubleMap;
import net.jqwik.api.*;

import java.util.Arrays;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class MathUtilsTest {

    @Property(afterFailure = AfterFailureMode.SAMPLE_FIRST)
    void apply(@ForAll("requests") double[] request) {
        double totalScore = Arrays.stream(request).sum();
        Int2DoubleMap idx2Score = new Int2DoubleArrayMap(IntStream.range(0, request.length).toArray(), request);
        Int2DoubleMap frequency = new Int2DoubleArrayMap();

        for (int i = 0; i < 1_000_000; i++) {
            var draw = MathUtils.weightedDraw(new DoubleArrayList(request));
            frequency.merge(draw.index, 1.0, Double::sum);
            var score = idx2Score.get(draw.index);
            assertEquals(score/totalScore, draw.probability, 0.005);
        }

        var totalDraws = frequency.values().doubleStream().sum();
        assertEquals(1_000_000, totalDraws);

        var ratios = Maps.transformValues(frequency, d -> d/totalDraws);
        ratios.forEach((key, value) -> {
            var expected = idx2Score.get(key.intValue()) / totalScore;
            var actual = frequency.get(key.intValue()) / totalDraws;
            assertEquals(expected, actual, 0.005);
        });
    }


    @Provide("requests")
    Arbitrary<double[]> generateRequestSamples() {
        return Arbitraries
                .of(0.0, 0.001, 0.1, 10, 100)
                .array(double[].class)
                .ofMaxSize(3)
                .injectDuplicates(0.1)
                .filter(x -> !(Arrays.stream(x).sum() == 0));
    }
}
