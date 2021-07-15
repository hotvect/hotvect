package com.eshioji.hotvect.util;

import com.codahale.metrics.MetricRegistry;
import com.eshioji.hotvect.core.util.Pair;
import com.google.common.hash.Hashing;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CpuIntensiveMapperTest {

    @Test
    void processInOrder() throws Exception{
        final var upperRange = 1_000_000;
        var mr = new MetricRegistry();
        Function<Integer, Integer> fun = x -> Hashing.sha512().hashInt(x).asInt();
        var subject = new CpuIntensiveMapper<>(mr, fun, 300, 1000);
        var queue = subject.start(IntStream.range(0, upperRange).boxed());
        var actual = new ArrayList<Integer>();
        while (true) {
            var hadFinished = subject.hasLoadingFinished();
            var batch = queue.poll(1, TimeUnit.SECONDS);
            if (batch != null) {
                // will throw if batch was a failure
                actual.addAll(batch.get());
            } else if (hadFinished) {
                // Last entry had been put on queue because loading had finished,
                // The only consumer (this thread) since queried the queue and it was empty (batch == null)
                // Means we are done
                break;
            }
        }


        StreamUtils.zip(
                IntStream.range(0, upperRange).boxed(),
                actual.stream(),
                (i, actualResult) -> Pair.of(fun.apply(i), actualResult))
                .forEach(p -> assertEquals(p._1, p._2));

    }
}