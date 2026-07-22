package com.hotvect.onlineutils.concurrency;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.google.common.hash.Hashing;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class CpuIntensiveMapperTest {

    @Test
    void processInOrder() throws Exception {
        final int upperRange = 1_000_000;
        SimpleMeterRegistry mr = new SimpleMeterRegistry();
        Function<Integer, Integer> fun = x -> Hashing.sha512().hashInt(x).asInt();
        CpuIntensiveMapper<Integer, Integer> subject = new CpuIntensiveMapper<>(mr, fun, 2, 300, 1000);
        BlockingQueue<Future<Collection<Integer>>> queue = subject.start(IntStream.range(0, upperRange).boxed());
        ArrayList<Integer> actual = new ArrayList<>();
        while (true) {
            boolean hadFinished = subject.hasLoadingFinished();
            Future<Collection<Integer>> batch = queue.poll(1, TimeUnit.SECONDS);
            if (batch != null) {
                // Will throw if batch was a failure
                actual.addAll(batch.get());
            } else if (hadFinished) {
                // Last entry had been put on queue because loading had finished,
                // The only consumer (this thread) since queried the queue and it was empty (batch == null)
                // Means we are done
                break;
            }
        }

        // Zip the expected results with actual results and assert equality
        Stream<Pair<Integer, Integer>> zippedStream = zip(
                IntStream.range(0, upperRange).boxed(),
                actual.stream(),
                (i, actualResult) -> new Pair<>(fun.apply(i), actualResult)
        );
        zippedStream.forEach(p -> assertEquals(p.first, p.second));
    }

    private static class Pair<A, B> {
        final A first;
        final B second;

        Pair(A first, B second) {
            this.first = first;
            this.second = second;
        }
    }

    // Zip function to combine two streams
    private static <A, B, R> Stream<R> zip(Stream<A> streamA, Stream<B> streamB, BiFunction<A, B, R> combiner) {
        Iterator<A> iteratorA = streamA.iterator();
        Iterator<B> iteratorB = streamB.iterator();

        Iterable<R> iterable = () -> new Iterator<R>() {
            @Override
            public boolean hasNext() {
                return iteratorA.hasNext() && iteratorB.hasNext();
            }

            @Override
            public R next() {
                A a = iteratorA.next();
                B b = iteratorB.next();
                return combiner.apply(a, b);
            }
        };
        return StreamSupport.stream(iterable.spliterator(), false);
    }
}
