package com.eshioji.hotvect.core.util;

import javax.annotation.CheckReturnValue;
import javax.annotation.concurrent.Immutable;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.function.Function;
/*
Source code adopted from https://github.com/quicktheories/QuickTheories/blob/master/core/src/main/java/org/quicktheories/api/Pair.java

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License.

You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

This file has been modified by Enno Shioji to remove support for non-pair tuples.
 */

@Immutable
@CheckReturnValue
public final class Pair<A, B> {
    public final A _1;
    public final B _2;

    private Pair(A _1, B _2) {
        super();
        this._1 = _1;
        this._2 = _2;
    }

    /**
     * Creates a pair from the two supplied values
     *
     * @param <A> first type
     * @param <B> second type
     * @param a
     *          first supplied value
     * @param b
     *          second supplied value
     * @return a pair
     */
    public static <A, B> Pair<A, B> of(A a, B b) {
        return new Pair<>(a, b);
    }

    public <A1,B1> Pair<A1, B1> map(Function<A,A1> fa, Function<B,B1> fb) {
        return Pair.of(fa.apply(_1), fb.apply(_2));
    }

    @Override
    public String toString() {
        StringJoiner sj = new StringJoiner(", ", "{", "}");
        return sj
                .add("" + _1)
                .add("" + _2)
                .toString();
    }

    @Override
    public int hashCode() {
        return Objects.hash(_1, _2);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        @SuppressWarnings("rawtypes")
        Pair other = (Pair) obj;
        return Objects.equals(_1, other._1) &&
                Objects.equals(_2, other._2);
    }

}
