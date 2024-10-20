package com.hotvect.utils;

import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.doubles.DoubleList;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

public class ListTransform {
    private ListTransform() {
    }

    public static <S, T> List<T> map(List<S> source, Function<S, T> transformation) {
        if (source.isEmpty()) {
            return Collections.emptyList();
        } else if (source.size() == 1) {
            return Collections.singletonList(transformation.apply(source.get(0)));
        } else {
            List<T> ret = new ArrayList<>(source.size());
            for (S s : source) {
                ret.add(transformation.apply(s));
            }
            return ret;
        }
    }

    public static <T> List<T> filter(List<T> source, Predicate<T> filter) {
        if (source.isEmpty()) {
            return Collections.emptyList();
        } else if (source.size() == 1) {
            if (filter.test(source.get(0))) {
                return Collections.singletonList(source.get(0));
            } else {
                return Collections.emptyList();
            }
        } else {
            List<T> ret = new ArrayList<>(source.size());
            for (T t : source) {
                if (filter.test(t)) {
                    ret.add(t);
                }
            }
            return ret;
        }
    }

    public static <S, T> List<T> flatMap(List<S> source, Function<S, T> transformation) {
        if (source.isEmpty()) {
            return Collections.emptyList();
        } else if (source.size() == 1) {
            var transformed = transformation.apply(source.get(0));
            return transformed != null ? Collections.singletonList(transformed) : Collections.emptyList();
        } else {
            List<T> ret = new ArrayList<>(source.size());
            for (S s : source) {
                var transformed = transformation.apply(s);
                if (transformed != null) {
                    ret.add(transformation.apply(s));
                }
            }
            return ret;
        }
    }


    public static <S> DoubleList mapToDouble(List<S> source, ToDoubleFunction<S> transformation) {
        if (source.isEmpty()) {
            return new DoubleArrayList();
        } else if (source.size() == 1) {
            return DoubleArrayList.wrap(new double[]{transformation.applyAsDouble(source.get(0))});
        } else {
            DoubleList ret = new DoubleArrayList(source.size());
            for (S s : source) {
                ret.add(transformation.applyAsDouble(s));
            }
            return ret;
        }
    }
}
