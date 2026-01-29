package com.hotvect.utils;

public record Pair<FIRST, SECOND>(FIRST first, SECOND second) {
    public static <FIRST, SECOND> Pair<FIRST, SECOND> of(FIRST first, SECOND second) {
        return new Pair<>(first, second);
    }

    @Deprecated(forRemoval = true)
    public FIRST _1() {
        return first;
    }

    @Deprecated(forRemoval = true)
    public SECOND _2() {
        return second;
    }
}
