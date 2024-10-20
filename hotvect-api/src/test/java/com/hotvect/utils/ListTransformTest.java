package com.hotvect.utils;

import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import net.jqwik.api.*;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ListTransformTest {

    @Property(afterFailure = AfterFailureMode.SAMPLE_FIRST)
    void map(@ForAll("lists") List<Integer> xs, @ForAll("functions") Function<Integer, String> function) {
        var expected = xs.stream().map(function).collect(Collectors.toList());
        var actual = ListTransform.map(xs, function);
        assertEquals(expected, actual);
    }

    @Property(afterFailure = AfterFailureMode.SAMPLE_FIRST)
    void flatMap(@ForAll("lists") List<Integer> xs, @ForAll("functions") Function<Integer, String> function) {
        var expected = xs.stream().flatMap(x -> {
            var transformed = function.apply(x);
            return transformed != null ? Stream.of(transformed) : Stream.empty();
        }).collect(Collectors.toList());

        var actual = ListTransform.flatMap(xs, function);
        assertEquals(expected, actual);
    }

    @Property(afterFailure = AfterFailureMode.SAMPLE_FIRST)
    void filter(@ForAll("lists") List<Integer> xs, @ForAll("predicates") Predicate<Integer> predicate) {
        var expected = xs.stream().filter(predicate).collect(Collectors.toList());

        var actual = ListTransform.filter(xs, predicate);
        assertEquals(expected, actual);
    }

    @Property(afterFailure = AfterFailureMode.SAMPLE_FIRST)
    void toDoubleList(@ForAll("lists") List<Integer> xs, @ForAll("toDoubleFunctions") ToDoubleFunction<Integer> function) {
        var expected = DoubleArrayList.wrap(xs.stream().mapToDouble(function).toArray());

        var actual = ListTransform.mapToDouble(xs, function);
        assertEquals(expected, actual);
    }

    @Provide
    Arbitrary<List<Integer>> lists(){
        return Arbitraries
                .integers().between(-100, 100)
                .list().ofMaxSize(100);
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

}