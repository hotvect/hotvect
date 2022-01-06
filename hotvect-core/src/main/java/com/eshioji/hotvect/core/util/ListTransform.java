package com.eshioji.hotvect.core.util;

import com.google.common.collect.ImmutableList;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.doubles.DoubleList;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

public class ListTransform {
    private ListTransform(){}
    public static <S, T> List<T> map(List<S> source, Function<S, T> transformation){
        if (source.isEmpty()){
            return ImmutableList.of();
        } else if (source.size() == 1){
            return ImmutableList.of(transformation.apply(source.get(0)));
        } else {
            List<T> ret = new ArrayList<>(source.size());
            for (S s : source) {
                ret.add(transformation.apply(s));
            }
            return ret;
        }
    }

    public static <S> DoubleList mapToDouble(List<S> source, ToDoubleFunction<S> transformation){
        if (source.isEmpty()){
            return new DoubleArrayList();
        } else if (source.size() == 1){
            return new DoubleArrayList(new double[]{transformation.applyAsDouble(source.get(0))});
        } else {
            DoubleList ret = new DoubleArrayList(source.size());
            for (S s : source) {
                ret.add(transformation.applyAsDouble(s));
            }
            return ret;
        }
    }
}
