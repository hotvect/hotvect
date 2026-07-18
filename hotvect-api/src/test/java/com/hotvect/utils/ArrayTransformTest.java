package com.hotvect.utils;

import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import org.junit.jupiter.api.Test;

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

    @Test
    void map() {
        for (Integer[] xs : arrays()) {
            for (Function<Integer, String> function : functions()) {
                var expected = Arrays.stream(xs).map(function).toArray(String[]::new);
                var actual = ArrayTransform.map(xs, function, String.class);
                assertEquals(Arrays.asList(expected), Arrays.asList(actual));
            }
        }
    }

    @Test
    void flatMap() {
        for (Integer[] xs : arrays()) {
            for (Function<Integer, String[]> function : arrayFunctions()) {
                var expected = Arrays.stream(xs).flatMap(x -> {
                    var transformed = function.apply(x);
                    return transformed != null ? Stream.of(transformed) : Stream.empty();
                }).toArray(String[]::new);

                var actual = ArrayTransform.flatMap(xs, function, String.class);
                assertEquals(Arrays.asList(expected), Arrays.asList(actual));
            }
        }
    }

    @Test
    void filter() {
        for (Integer[] xs : arrays()) {
            for (Predicate<Integer> predicate : predicates()) {
                var expected = Arrays.stream(xs).filter(predicate).toArray(Integer[]::new);
                var actual = ArrayTransform.filter(xs, predicate, Integer.class);
                assertEquals(Arrays.asList(expected), Arrays.asList(actual));
            }
        }
    }

    @Test
    void toDoubleArray() {
        for (Integer[] xs : arrays()) {
            for (ToDoubleFunction<Integer> function : toDoubleFunctions()) {
                var expected = Arrays.stream(xs).mapToDouble(function).toArray();
                var actual = ArrayTransform.mapToDouble(xs, function);
                assertEquals(DoubleArrayList.wrap(expected), DoubleArrayList.wrap(actual));
            }
        }
    }

    @Test
    void toIntArray() {
        for (Integer[] xs : arrays()) {
            for (ToIntFunction<Integer> function : toIntFunctions()) {
                var expected = Arrays.stream(xs).mapToInt(function).toArray();
                var actual = ArrayTransform.mapToInt(xs, function);
                assertEquals(IntStream.of(expected).boxed().collect(Collectors.toList()), IntStream.of(actual).boxed().collect(Collectors.toList()));
            }
        }
    }

    @Test
    void mapList() {
        for (List<Integer> xs : lists()) {
            for (Function<Integer, String> function : functions()) {
                var expected = xs.stream().map(function).toArray(String[]::new);
                var actual = ArrayTransform.map(xs, function, String.class);
                assertEquals(Arrays.asList(expected), Arrays.asList(actual));
            }
        }
    }

    @Test
    void flatMapList() {
        for (List<Integer> xs : lists()) {
            for (Function<Integer, List<String>> function : listFunctions()) {
                var expected = xs.stream().flatMap(x -> {
                    var transformed = function.apply(x);
                    return transformed != null ? transformed.stream() : Stream.empty();
                }).toArray(String[]::new);

                var actual = ArrayTransform.flatMap(xs, function, String.class);
                assertEquals(Arrays.asList(expected), Arrays.asList(actual));
            }
        }
    }

    @Test
    void filterList() {
        for (List<Integer> xs : lists()) {
            for (Predicate<Integer> predicate : predicates()) {
                var expected = xs.stream().filter(predicate).toArray(Integer[]::new);
                var actual = ArrayTransform.filter(xs, predicate, Integer.class);
                assertEquals(Arrays.asList(expected), Arrays.asList(actual));
            }
        }
    }

    @Test
    void toDoubleArrayList() {
        for (List<Integer> xs : lists()) {
            for (ToDoubleFunction<Integer> function : toDoubleFunctions()) {
                var expected = xs.stream().mapToDouble(function).toArray();
                var actual = ArrayTransform.mapToDouble(xs, function);
                assertEquals(DoubleArrayList.wrap(expected), DoubleArrayList.wrap(actual));
            }
        }
    }

    @Test
    void toIntArrayList() {
        for (List<Integer> xs : lists()) {
            for (ToIntFunction<Integer> function : toIntFunctions()) {
                var expected = xs.stream().mapToInt(function).toArray();
                var actual = ArrayTransform.mapToInt(xs, function);
                assertEquals(IntStream.of(expected).boxed().collect(Collectors.toList()), IntStream.of(actual).boxed().collect(Collectors.toList()));
            }
        }
    }

    @Test
    void addArrays() {
        for (Integer[][] arrays : genericArrays()) {
            Integer[] expected = Arrays.stream(arrays).flatMap(Arrays::stream).toArray(Integer[]::new);
            Integer[] actual = ArrayTransform.addArrays(Integer.class, arrays);
            assertTrue(Arrays.deepEquals(expected, actual));
        }
    }

    @Test
    void mapNew() {
        for (Integer[] xs : arrays()) {
            for (Function<Integer, String> function : functions()) {
                var expected = Arrays.stream(xs).map(function).toArray(String[]::new);
                var actual = ArrayTransform.map(xs, function, String[]::new);
                assertEquals(Arrays.asList(expected), Arrays.asList(actual));
            }
        }
    }

    @Test
    void flatMapNew() {
        for (Integer[] xs : arrays()) {
            for (Function<Integer, String[]> function : arrayFunctions()) {
                var expected = Arrays.stream(xs).flatMap(x -> {
                    var transformed = function.apply(x);
                    return transformed != null ? Stream.of(transformed) : Stream.empty();
                }).toArray(String[]::new);

                var actual = ArrayTransform.flatMap(xs, function, String[]::new);
                assertEquals(Arrays.asList(expected), Arrays.asList(actual));
            }
        }
    }

    @Test
    void filterNew() {
        for (Integer[] xs : arrays()) {
            for (Predicate<Integer> predicate : predicates()) {
                var expected = Arrays.stream(xs).filter(predicate).toArray(Integer[]::new);
                var actual = ArrayTransform.filter(xs, predicate);
                assertEquals(Arrays.asList(expected), Arrays.asList(actual));
            }
        }
    }

    @Test
    void mapListNew() {
        for (List<Integer> xs : lists()) {
            for (Function<Integer, String> function : functions()) {
                var expected = xs.stream().map(function).toArray(String[]::new);
                var actual = ArrayTransform.map(xs, function, String[]::new);
                assertEquals(Arrays.asList(expected), Arrays.asList(actual));
            }
        }
    }

    @Test
    void flatMapListNew() {
        for (List<Integer> xs : lists()) {
            for (Function<Integer, List<String>> function : listFunctions()) {
                var expected = xs.stream().flatMap(x -> {
                    var transformed = function.apply(x);
                    return transformed != null ? transformed.stream() : Stream.empty();
                }).toArray(String[]::new);

                var actual = ArrayTransform.flatMap(xs, function, String[]::new);
                assertEquals(Arrays.asList(expected), Arrays.asList(actual));
            }
        }
    }

    @Test
    void filterListNew() {
        for (List<Integer> xs : lists()) {
            for (Predicate<Integer> predicate : predicates()) {
                var expected = xs.stream().filter(predicate).toArray(Integer[]::new);
                var actual = ArrayTransform.filter(xs, predicate, Integer[]::new);
                assertEquals(Arrays.asList(expected), Arrays.asList(actual));
            }
        }
    }

    @Test
    void addArraysNew() {
        for (Integer[][] arrays : genericArrays()) {
            Integer[] expected = Arrays.stream(arrays).flatMap(Arrays::stream).toArray(Integer[]::new);
            Integer[] actual = ArrayTransform.addArrays(Integer[]::new, arrays);
            assertTrue(Arrays.deepEquals(expected, actual));
        }
    }

    private static List<Integer[]> arrays() {
        return List.of(
                new Integer[]{},
                new Integer[]{0},
                new Integer[]{-100, -1, 0, 1, 10, 100}
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

    private static List<ToIntFunction<Integer>> toIntFunctions() {
        return List.of(Integer::valueOf);
    }

    private static List<Function<Integer, String[]>> arrayFunctions() {
        return List.of(
                i -> {
                    if (i % 3 == 2) return new String[]{"alpha", "alpha"};
                    if (i % 3 == 1) return new String[]{"alpha"};
                    return new String[]{};
                },
                i -> i < 0 ? null : new String[]{"positive"}
        );
    }

    private static List<Function<Integer, List<String>>> listFunctions() {
        return List.of(
                i -> {
                    if (i % 3 == 2) return Arrays.asList("alpha", "alpha");
                    if (i % 3 == 1) return Collections.singletonList("alpha");
                    return Collections.emptyList();
                },
                i -> i < 0 ? null : List.of("positive")
        );
    }

    private static List<List<Integer>> lists() {
        return List.of(
                List.of(),
                List.of(0),
                List.of(-100, -1, 0, 1, 10, 100)
        );
    }

    private static List<Integer[][]> genericArrays() {
        return List.of(
                new Integer[][]{},
                new Integer[][]{new Integer[]{}},
                new Integer[][]{new Integer[]{1, 2}, new Integer[]{}, new Integer[]{3}},
                new Integer[][]{new Integer[]{-1}, new Integer[]{10, 20}}
        );
    }
}
