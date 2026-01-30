package com.hotvect.testutils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.hash.Hashing;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Tuple;
import net.jqwik.api.Tuple.Tuple2;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

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

    public static Arbitrary<double[]> doubleArrays(Arbitrary<Integer> sizes, Arbitrary<Double> contents) {
        return sizes.flatMap(size -> contents.array(double[].class).ofSize(size));
    }

    public static Arbitrary<String[]> stringArrays(Arbitrary<Integer> sizes, Arbitrary<String> contents) {
        return sizes.flatMap(size -> contents.array(String[].class).ofSize(size));
    }

    public static Arbitrary<String[]> stringArraysWithDefaultContent() {
        Arbitrary<Integer> sizes = Arbitraries.integers().between(0, 2);
        Arbitrary<String> contents = Arbitraries.strings().ofMinLength(0).ofMaxLength(4);
        return stringArrays(sizes, contents);
    }

    public static Arbitrary<String> defaultStrings() {
        return Arbitraries.strings().ofMinLength(0).ofMaxLength(3);
    }

    public static Arbitrary<int[]> categoricalVectors() {
        Arbitrary<Integer> sizes = Arbitraries.integers().between(0, 3);
        Arbitrary<Integer> contents = Arbitraries.integers();
        return sizes.flatMap(size -> contents.array(int[].class).ofSize(size));
    }

    public static Arbitrary<int[]> categoricalVectors(Arbitrary<Integer> content) {
        Arbitrary<Integer> sizes = Arbitraries.integers().between(0, 3);
        return sizes.flatMap(size -> content.array(int[].class).ofSize(size));
    }

    public static Arbitrary<Double> discreteDoubles() {
        return Arbitraries.doubles();
    }

    public static Arbitrary<Tuple2<int[], double[]>> testVectors() {
        return categoricalVectors().flatMap(is -> {
            int length = is.length;
            Arbitrary<double[]> doublesArray = discreteDoubles().array(double[].class).ofSize(length);
            return doublesArray.map(ds -> Tuple.of(is, ds));
        });
    }

    public static Arbitrary<Tuple2<String[], double[]>> stringToNumericals() {
        Arbitrary<Integer> sizes = Arbitraries.integers().between(0, 3);
        Arbitrary<String> strings = Arbitraries.strings().withCharRange('a', 'z').ofMinLength(0).ofMaxLength(3);
        return stringArrays(sizes, strings).flatMap(is -> {
            int length = is.length;
            Arbitrary<double[]> doublesArray = discreteDoubles().array(double[].class).ofSize(length);
            return doublesArray.map(ds -> Tuple.of(is, ds));
        });
    }

    public static <T> Arbitrary<Tuple2<T, T>> generateEquals(Arbitrary<T> gen) {
        return gen.map(i -> Tuple.of(i, i));
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

//    public static <T> Arbitrary<T[]> generateArrays(Arbitrary<Integer> sizes, Arbitrary<T> contents, Class<T> contentType) {
//        return sizes.flatMap(size -> contents.array(contentType).ofSize(size));
//    }

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