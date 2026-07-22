package com.hotvect.utils;

import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ListTransformTest {

    @Test
    void map() {
        for (List<Integer> xs : lists()) {
            for (Function<Integer, String> function : functions()) {
                var expected = xs.stream().map(function).collect(Collectors.toList());
                var actual = ListTransform.map(xs, function);
                assertEquals(expected, actual);
            }
        }
    }

    @Test
    void flatMap() {
        for (List<Integer> xs : lists()) {
            for (Function<Integer, String> function : functions()) {
                var expected = xs.stream().flatMap(x -> {
                    var transformed = function.apply(x);
                    return transformed != null ? Stream.of(transformed) : Stream.empty();
                }).collect(Collectors.toList());

                var actual = ListTransform.flatMap(xs, function);
                assertEquals(expected, actual);
            }
        }
    }

    @Test
    void filter() {
        for (List<Integer> xs : lists()) {
            for (Predicate<Integer> predicate : predicates()) {
                var expected = xs.stream().filter(predicate).collect(Collectors.toList());

                var actual = ListTransform.filter(xs, predicate);
                assertEquals(expected, actual);
            }
        }
    }

    @Test
    void toDoubleList() {
        for (List<Integer> xs : lists()) {
            for (ToDoubleFunction<Integer> function : toDoubleFunctions()) {
                var expected = DoubleArrayList.wrap(xs.stream().mapToDouble(function).toArray());

                var actual = ListTransform.mapToDouble(xs, function);
                assertEquals(expected, actual);
            }
        }
    }

    private static List<List<Integer>> lists() {
        return List.of(
                List.of(),
                List.of(0),
                List.of(-100, -1, 0, 1, 10, 100),
                List.of(3, 3, 7, 10, 20, 30)
        );
    }

    private static List<Predicate<Integer>> predicates() {
        return List.of(
                x -> true,
                x -> x % 10 == 0,
                x -> false
        );
    }

    private static List<Function<Integer, String>> functions() {
        return List.of(
                String::valueOf,
                i -> i % 10 == 0 ? "yes" : null
        );
    }

    private static List<ToDoubleFunction<Integer>> toDoubleFunctions() {
        return List.of(Double::valueOf);
    }
}
