package com.hotvect.testutils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.hash.Hashing;
import org.quicktheories.api.Pair;
import org.quicktheories.core.Gen;

import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.quicktheories.generators.Generate.constant;
import static org.quicktheories.generators.Generate.intArrays;
import static org.quicktheories.generators.SourceDSL.*;

public enum TestUtils {
    ;

    private static final ObjectMapper mapper = new ObjectMapper();

    public static void assertJsonEquals(String json1, String json2) {

        try {
            JsonNode tree1 = mapper.readTree(json1);
            JsonNode tree2 = mapper.readTree(json2);
            if (!tree1.equals(tree2)) {
                throw new AssertionError(String.format("%s differs from %s", tree1, tree2));
            }
        } catch (JsonProcessingException e) {
            throw new AssertionError(String.format("Could not parse %s or %s", json1, json2));
        }
    }

    public static Gen<double[]> doubleArrays(Gen<Integer> sizes, Gen<Double> contents) {
        Gen<double[]> gen = td -> {
            int size = sizes.generate(td);
            double[] ds = new double[size];
            for (int i = 0; i != size; i++) {
                ds[i] = contents.generate(td);
            }
            return ds;
        };
        return gen.describedAs(Arrays::toString);
    }


    public static Gen<String[]> stringArrays(Gen<Integer> sizes, Gen<String> contents) {
        Gen<String[]> gen = td -> {
            int size = sizes.generate(td);
            String[] ds = new String[size];
            for (int i = 0; i != size; i++) {
                ds[i] = contents.generate(td);
            }
            return ds;
        };
        return gen.describedAs(Arrays::toString);
    }

    public static Gen<String[]> stringArraysWithDefaultContent() {
        return stringArrays(integers().between(0, 2), strings().allPossible().ofLengthBetween(0, 4));
    }

    public static Gen<String> defaultStrings() {
        return strings().allPossible().ofLengthBetween(0, 3);
    }


    public static Gen<int[]> categoricalVectors() {
        return intArrays(integers().between(0, 3), integers().all());
    }

    public static Gen<int[]> categoricalVectors(Gen<Integer> content) {
        return intArrays(integers().between(0, 3), content);
    }

    public static Gen<Double> discreteDoubles() {
        return doubles().any();
    }

    public static Gen<Pair<int[], double[]>> testVectors() {
        return categoricalVectors(integers().between(0, 3))
                .mutate((is, rand) -> Pair.of(is, doubleArrays(constant(is.length), discreteDoubles()).generate(rand)));
    }

    public static Gen<Pair<String[], double[]>> stringToNumericals() {
        return stringArrays(integers().between(0, 3), strings().allPossible().ofLengthBetween(0, 3))
                .mutate((is, rand) -> Pair.of(is, doubleArrays(constant(is.length), discreteDoubles()).generate(rand)));
    }


    public static <T> Gen<Pair<T, T>> generateEquals(Gen<T> gen) {
        return gen.mutate((i, rand) -> Pair.of(i, constant(i).generate(rand)));
    }

    public static IntStream ints() {
        return ints(System.currentTimeMillis());
    }


    public static IntStream ints(long seed) {
        Random rng = new Random(seed);
        return IntStream.concat(
                IntStream.of(Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE),
                IntStream.generate(rng::nextInt));
    }

    public static Stream<Pair<int[], int[]>> twoIntArrays() {
        return twoIntArrays(System.currentTimeMillis());
    }

    public static Stream<Pair<int[], int[]>> twoIntArrays(long seed) {
        IntStream ints = ints(seed);
        return ints.mapToObj(i -> Pair.of(generateCategoricals(i), generateCategoricals(i)));
    }

    public static void shuffleArray(int[] ar) {
        Random rnd = ThreadLocalRandom.current();
        for (int i = ar.length - 1; i > 0; i--) {
            int index = rnd.nextInt(i + 1);
            // Simple swap
            int a = ar[index];
            ar[index] = ar[i];
            ar[i] = a;
        }
    }

    public static <T> Gen<T[]> generateArrays(Gen<Integer> sizes, Gen<T> contents, Class<T> contentType){
            Gen<T[]> gen = td -> {
                int size = sizes.generate(td);
                @SuppressWarnings("unchecked")
                T[] ds = (T[]) Array.newInstance(contentType, size);
                for (int i = 0; i != size; i++) {
                    ds[i] = contents.generate(td);
                }
                return ds;
            };
            return gen.describedAs(Arrays::toString);
    }


    private static int[] generateCategoricals(final int seed) {
        int size = Math.abs(Hashing.murmur3_32().hashInt(seed).asInt() % 3) + 1;
        int[] ret = new int[size];
        int h = seed;
        for (int i = 0; i < ret.length; i++) {
            h ^= Hashing.murmur3_32().hashInt(h).asInt();
            ret[i] = h;
        }
        return ret;
    }

}
