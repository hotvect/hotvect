package com.eshioji.hotvect.core.util;

import com.eshioji.hotvect.api.data.RawValue;
import com.eshioji.hotvect.core.transform.ranking.SharedTransformation;
import com.google.common.base.Charsets;
import com.google.common.hash.Hashing;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.awt.desktop.AppReopenedEvent;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.stream.IntStream;

import static com.eshioji.hotvect.core.util.IdentityCaching.cachedSharedTransformation;
import static org.junit.jupiter.api.Assertions.*;

class IdentityCachingTest {
    private final Function<String, String> original = s -> {
        int h = Hashing.sha512().hashString(s, Charsets.UTF_8).asInt();
        for (int i = 0; i < 1000; i++) {
            h += Hashing.sha512().hashInt(h).asInt();
        }
        return Integer.toString(h);
    };
    private final SharedTransformation<String> subject = cachedSharedTransformation(s -> RawValue.singleString(original.apply(s)));

    @Property
    void test(@ForAll String any) {
        var expected = original.apply(any);
        var actual = subject.apply(any);
        Assertions.assertEquals(expected, actual.getSingleString());
    }

    @Test
    void performaceIsBetter(){
        long start = System.nanoTime();
        System.out.println(IntStream.range(0, 10000).mapToObj(i -> original.apply("" + i % 100)).reduce((s1, s2) -> s1.length() < s2.length() ? s1: s2).get());
        long lap = System.nanoTime();
        System.out.println(IntStream.range(0, 10000).mapToObj(i -> subject.apply("" + i % 100)).reduce((s1, s2) -> s1.getSingleString().length() < s2.getSingleString().length() ? s1: s2).get().getSingleString());
        long finished = System.nanoTime();
        System.out.println(TimeUnit.NANOSECONDS.toMillis(lap-start) + " vs " + TimeUnit.NANOSECONDS.toMillis(finished - lap));

        Assertions.assertTrue(lap - start > (finished - lap) * 1.2);
    }
}