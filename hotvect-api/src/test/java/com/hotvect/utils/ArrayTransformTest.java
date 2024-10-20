package com.hotvect.utils;

import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import net.jqwik.api.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArrayTransformTest {

    @Property(afterFailure = AfterFailureMode.SAMPLE_FIRST)
    void map(@ForAll("arrays") Integer[] xs, @ForAll("functions") Function<Integer, String> function) {
        var expected = Arrays.stream(xs).map(function).toArray(String[]::new);
        var actual = ArrayTransform.map(xs, function, String.class);
        assertEquals(Arrays.asList(expected), Arrays.asList(actual));
    }

    @Property(afterFailure = AfterFailureMode.SAMPLE_FIRST)
    void flatMap(@ForAll("arrays") Integer[] xs, @ForAll("arrayFunctions") Function<Integer, String[]> function) {
        var expected = Arrays.stream(xs).flatMap(x -> {
            var transformed = function.apply(x);
            return transformed != null ? Stream.of(transformed) : Stream.empty();
        }).toArray(String[]::new);

        var actual = ArrayTransform.flatMap(xs, function, String.class);
        assertEquals(Arrays.asList(expected), Arrays.asList(actual));
    }

    @Property(afterFailure = AfterFailureMode.SAMPLE_FIRST)
    void filter(@ForAll("arrays") Integer[] xs, @ForAll("predicates") Predicate<Integer> predicate) {
        var expected = Arrays.stream(xs).filter(predicate).toArray(Integer[]::new);

        var actual = ArrayTransform.filter(xs, predicate, Integer.class);
        assertEquals(Arrays.asList(expected), Arrays.asList(actual));
    }

    @Property(afterFailure = AfterFailureMode.SAMPLE_FIRST)
    void toDoubleArray(@ForAll("arrays") Integer[] xs, @ForAll("toDoubleFunctions") ToDoubleFunction<Integer> function) {
        var expected = Arrays.stream(xs).mapToDouble(function).toArray();

        var actual = ArrayTransform.mapToDouble(xs, function);
        assertEquals(DoubleArrayList.wrap(expected), DoubleArrayList.wrap(actual));
    }

    @Property(afterFailure = AfterFailureMode.SAMPLE_FIRST)
    void toIntArray(@ForAll("arrays") Integer[] xs, @ForAll("toIntFunctions") ToIntFunction<Integer> function) {
        var expected = Arrays.stream(xs).mapToInt(function).toArray();

        var actual = ArrayTransform.mapToInt(xs, function);
        assertEquals(IntStream.of(expected).boxed().collect(Collectors.toList()), IntStream.of(actual).boxed().collect(Collectors.toList()));
    }

    @Property(afterFailure = AfterFailureMode.SAMPLE_FIRST)
    void mapList(@ForAll("lists") List<Integer> xs, @ForAll("functions") Function<Integer, String> function) {
        var expected = xs.stream().map(function).toArray(String[]::new);
        var actual = ArrayTransform.map(xs, function, String.class);
        assertEquals(Arrays.asList(expected), Arrays.asList(actual));
    }

    @Property(afterFailure = AfterFailureMode.SAMPLE_FIRST)
    void flatMapList(@ForAll("lists") List<Integer> xs, @ForAll("listFunctions") Function<Integer, List<String>> function) {
        var expected = xs.stream().flatMap(x -> {
            var transformed = function.apply(x);
            return transformed != null ? transformed.stream() : Stream.empty();
        }).toArray(String[]::new);

        var actual = ArrayTransform.flatMap(xs, function, String.class);
        assertEquals(Arrays.asList(expected), Arrays.asList(actual));
    }

    @Property(afterFailure = AfterFailureMode.SAMPLE_FIRST)
    void filterList(@ForAll("lists") List<Integer> xs, @ForAll("predicates") Predicate<Integer> predicate) {
        var expected = xs.stream().filter(predicate).toArray(Integer[]::new);

        var actual = ArrayTransform.filter(xs, predicate, Integer.class);
        assertEquals(Arrays.asList(expected), Arrays.asList(actual));
    }

    @Property(afterFailure = AfterFailureMode.SAMPLE_FIRST)
    void toDoubleArrayList(@ForAll("lists") List<Integer> xs, @ForAll("toDoubleFunctions") ToDoubleFunction<Integer> function) {
        var expected = xs.stream().mapToDouble(function).toArray();

        var actual = ArrayTransform.mapToDouble(xs, function);
        assertEquals(DoubleArrayList.wrap(expected), DoubleArrayList.wrap(actual));
    }

    @Property(afterFailure = AfterFailureMode.SAMPLE_FIRST)
    void toIntArrayList(@ForAll("lists") List<Integer> xs, @ForAll("toIntFunctions") ToIntFunction<Integer> function) {
        var expected = xs.stream().mapToInt(function).toArray();

        var actual = ArrayTransform.mapToInt(xs, function);
        assertEquals(IntStream.of(expected).boxed().collect(Collectors.toList()), IntStream.of(actual).boxed().collect(Collectors.toList()));
    }

    @Provide
    Arbitrary<Integer[]> arrays(){
        return Arbitraries
                .integers().between(-100, 100)
                .array(Integer[].class).ofMaxSize(100);
    }

    @Provide
    Arbitrary<Predicate<Integer>> predicates(){
        return Arbitraries.of(
                x -> true,
                x -> x % 10 == 0,
                x -> false
        );
    }

    @Provide
    Arbitrary<Function<Integer, String>> functions(){
        return Arbitraries.of(
                String::valueOf,
                i -> i % 10 == 0 ? "yes" : null
        );
    }

    @Provide
    Arbitrary<ToDoubleFunction<Integer>> toDoubleFunctions(){
        return Arbitraries.of(
                Double::valueOf
        );
    }

    @Provide
    Arbitrary<ToIntFunction<Integer>> toIntFunctions(){
        return Arbitraries.of(
                Integer::valueOf
        );
    }
    @Provide
    Arbitrary<Function<Integer, String[]>> arrayFunctions() {
        Arbitrary<String> strings = Arbitraries.strings().alpha().ofLength(5);
        return strings.map(string -> (i -> {
            if (i % 3 == 2) return new String[]{string, string};
            if (i % 3 == 1) return new String[]{string};
            return new String[]{};
        }));
    }

    @Provide
    Arbitrary<Function<Integer, List<String>>> listFunctions() {
        Arbitrary<String> strings = Arbitraries.strings().alpha().ofLength(5);
        return strings.map(string -> (i -> {
            if (i % 3 == 2) return Arrays.asList(string, string);
            if (i % 3 == 1) return Collections.singletonList(string);
            return Collections.emptyList();
        }));
    }

    @Provide
    Arbitrary<List<Integer>> lists() {
        return Arbitraries.integers().between(-100, 100).list().ofMaxSize(100);
    }

    @Property(afterFailure = AfterFailureMode.SAMPLE_FIRST)
    void addArrays(@ForAll("genericArrays") Integer[][] arrays) {
        // Flatten the arrays to calculate the expected result
        Integer[] expected = Arrays.stream(arrays).flatMap(Arrays::stream).toArray(Integer[]::new);

        // Use the addArrays method to get the actual result
        Integer[] actual = ArrayTransform.addArrays(Integer.class, arrays);
        assertTrue(Arrays.deepEquals(expected, actual));
    }

    @Provide
    Arbitrary<Integer[][]> genericArrays() {
        return Arbitraries.integers().between(-100, 100)
                .array(Integer[].class)
                .ofMaxSize(10) // Size of each inner array
                .array(Integer[][].class)
                .ofMaxSize(5); // Number of arrays
    }




}
