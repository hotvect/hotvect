package com.eshioji.hotvect.core.util;

import com.google.common.collect.ImmutableList;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

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
}
