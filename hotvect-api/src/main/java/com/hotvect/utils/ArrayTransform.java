package com.hotvect.utils;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.*;

public class ArrayTransform {
    private ArrayTransform() {
    }

    /**
     * Prefer {@link #map(Object[], Function, IntFunction)}. Array.newInstance is not ideal for performance.
     * @param source
     * @param transformation
     * @param clazz
     * @return
     * @param <S>
     * @param <T>
     */
    @Deprecated
    public static <S, T> T[] map(S[] source, Function<S, T> transformation, Class<T> clazz) {
        if (source.length == 0) {
            return (T[]) Array.newInstance(clazz, 0);
        } else {
            T[] ret = (T[]) Array.newInstance(clazz, source.length);
            for (int i = 0; i < source.length; i++) {
                ret[i] = transformation.apply(source[i]);
            }
            return ret;
        }
    }

    public static <S, T> T[] map(S[] source, Function<S, T> transformation, IntFunction<T[]> generator) {
        if (source.length == 0) {
            return generator.apply(0);
        }
        T[] ret = generator.apply(source.length);
        for (int i = 0; i < source.length; i++) {
            ret[i] = transformation.apply(source[i]);
        }
        return ret;
    }

    @Deprecated(forRemoval = true)
    public static <T> T[] filter(T[] source, Predicate<T> filter, Class<T> clazz) {
        List<T> temp = new ArrayList<>(source.length);
        for (T t : source) {
            if (filter.test(t)) {
                temp.add(t);
            }
        }
        return temp.toArray((T[]) Array.newInstance(clazz, 0));
    }

    public static <T> T[] filter(T[] source, Predicate<T> filter) {
        List<T> temp = new ArrayList<>(source.length);
        for (T t : source) {
            if (filter.test(t)) {
                temp.add(t);
            }
        }

        return temp.toArray(Arrays.copyOf(source, 0));
    }

    @Deprecated(forRemoval = true)
    public static <S, T> T[] flatMap(S[] source, Function<S, T[]> transformation, Class<T> clazz) {
        ArrayList<T> temp = new ArrayList<>();
        for (S s : source) {
            T[] transformed = transformation.apply(s);
            if (transformed != null) {
                Collections.addAll(temp, transformed);
            }
        }
        return temp.toArray((T[]) Array.newInstance(clazz, 0));
    }

    public static <S, T> T[] flatMap(S[] source, Function<S, T[]> transformation, IntFunction<T[]> generator) {
        ArrayList<T> temp = new ArrayList<>();
        for (S s : source) {
            T[] transformed = transformation.apply(s);
            if (transformed != null) {
                Collections.addAll(temp, transformed);
            }
        }
        return temp.toArray(generator.apply(0));
    }

    public static <S> int[] mapToInt(S[] source, ToIntFunction<S> transformation) {
        int[] ret = new int[source.length];
        for (int i = 0; i < source.length; i++) {
            ret[i] = transformation.applyAsInt(source[i]);
        }
        return ret;
    }

    public static <S> double[] mapToDouble(S[] source, ToDoubleFunction<S> transformation) {
        double[] ret = new double[source.length];
        for (int i = 0; i < source.length; i++) {
            ret[i] = transformation.applyAsDouble(source[i]);
        }
        return ret;
    }

    @Deprecated(forRemoval = true)
    public static <S, T> T[] map(List<S> source, Function<S, T> transformation, Class<T> clazz) {
        T[] ret = (T[]) Array.newInstance(clazz, source.size());
        for (int i = 0; i < source.size(); i++) {
            ret[i] = transformation.apply(source.get(i));
        }
        return ret;
    }

    public static <S, T> T[] map(List<S> source, Function<S, T> transformation, IntFunction<T[]> generator) {
        T[] ret = generator.apply(source.size());
        for (int i = 0; i < source.size(); i++) {
            ret[i] = transformation.apply(source.get(i));
        }
        return ret;
    }

    @Deprecated(forRemoval = true)
    public static <T> T[] filter(List<T> source, Predicate<T> filter, Class<T> clazz) {
        ArrayList<T> temp = new ArrayList<>();
        for (T t : source) {
            if (filter.test(t)) {
                temp.add(t);
            }
        }
        return temp.toArray((T[]) Array.newInstance(clazz, 0));
    }

    public static <T> T[] filter(List<T> source, Predicate<T> filter, IntFunction<T[]> generator) {
        ArrayList<T> temp = new ArrayList<>();
        for (T t : source) {
            if (filter.test(t)) {
                temp.add(t);
            }
        }
        return temp.toArray(generator.apply(0));
    }

    @Deprecated(forRemoval = true)
    public static <S, T> T[] flatMap(List<S> source, Function<S, List<T>> transformation, Class<T> clazz) {
        ArrayList<T> temp = new ArrayList<>();
        for (S s : source) {
            List<T> transformed = transformation.apply(s);
            if (transformed != null) {
                temp.addAll(transformed);
            }
        }
        return temp.toArray((T[]) Array.newInstance(clazz, 0));
    }

    public static <S, T> T[] flatMap(List<S> source, Function<S, List<T>> transformation, IntFunction<T[]> generator) {
        ArrayList<T> temp = new ArrayList<>();
        for (S s : source) {
            List<T> transformed = transformation.apply(s);
            if (transformed != null) {
                temp.addAll(transformed);
            }
        }
        return temp.toArray(generator.apply(0));
    }

    public static <S> double[] mapToDouble(List<S> source, ToDoubleFunction<S> transformation) {
        double[] ret = new double[source.size()];
        for (int i = 0; i < source.size(); i++) {
            ret[i] = transformation.applyAsDouble(source.get(i));
        }
        return ret;
    }

    public static <S> int[] mapToInt(List<S> source, ToIntFunction<S> transformation) {
        int[] ret = new int[source.size()];
        for (int i = 0; i < source.size(); i++) {
            ret[i] = transformation.applyAsInt(source.get(i));
        }
        return ret;
    }

    @Deprecated(forRemoval = true)
    public static <T> T[] addArrays(Class<T> clazz, T[]... arrays) {
        // Check if the input arrays are empty or null
        if (arrays.length == 0 || (arrays.length == 1 && arrays[0].length == 0)) {
            return (T[]) Array.newInstance(clazz, 0); // Return an empty array of the appropriate type
        }

        // Calculate the total length
        int totalLength = 0;
        for (T[] array : arrays) {
            totalLength += array.length;
        }

        // Create the final array
        T[] finalArray = (T[]) Array.newInstance(clazz, totalLength);

        // Copy elements
        int currentPos = 0;
        for (T[] array : arrays) {
            System.arraycopy(array, 0, finalArray, currentPos, array.length);
            currentPos += array.length;
        }
        return finalArray;
    }

    public static <T> T[] addArrays(IntFunction<T[]> generator, T[]... arrays) {
        if (arrays.length == 0 || (arrays.length == 1 && arrays[0].length == 0)) {
            return generator.apply(0);
        }
        int totalLength = 0;
        for (T[] array : arrays) {
            totalLength += array.length;
        }
        T[] finalArray = generator.apply(totalLength);
        int currentPos = 0;
        for (T[] array : arrays) {
            System.arraycopy(array, 0, finalArray, currentPos, array.length);
            currentPos += array.length;
        }
        return finalArray;
    }
}
