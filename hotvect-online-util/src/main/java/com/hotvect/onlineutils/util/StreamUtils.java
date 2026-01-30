package com.hotvect.onlineutils.util;

import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class StreamUtils {
    private StreamUtils() {}

    /**
     * Returns a stream of exactly {@code length} elements built from {@code list}.
     * • If {@code length ≤ list.size()}  the stream is just the first {@code length} items.<br>
     * • If {@code length  > list.size()} the list is traversed cyclically, no copies are made.<br>
     *
     *  The list itself is *not* duplicated; each element is fetched on demand with
     *  {@code list.get(i % list.size())}.
     *
     * @throws IllegalArgumentException if {@code length} is negative or if
     *                                  {@code length > 0} and {@code list} is empty
     */
    public static <V> Stream<V> repeatToLength(List<V> list, int length) {
        Objects.requireNonNull(list, "list");
        if (length < 0) {
            throw new IllegalArgumentException("length must be ≥ 0");
        }
        if (length == 0) {
            return Stream.empty();
        }
        int n = list.size();
        if (n == 0) {
            throw new IllegalArgumentException("cannot create a non-empty stream from an empty list");
        }
        if (length <= n) {
            return list.stream().limit(length);              // just truncate
        }
        /* Produce the indexes 0 … length-1, map them to list[i % n]. */
        return IntStream.range(0, length)
                .mapToObj(i -> list.get((i % n)));
    }
}
