package com.hotvect.onlineutils.concurrency.fileutils;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@SuppressWarnings("UnstableApiUsage")
class CpuIntensiveFileAggregatorTest {

    @Test
    void normalFile() throws Exception {
        File source = getAsFile("example.jsons");
        test(source);
    }

    @Test
    void gzippedFile() throws Exception {
        File source = getAsFile("example.jsons.gz");
        test(source);
    }

    private void test(File source) throws Exception {
        SimpleMeterRegistry mr = new SimpleMeterRegistry();
        final BloomFilter<String> firstBloomFilter = BloomFilter.create(
                Funnels.stringFunnel(StandardCharsets.UTF_8),
                1024 * 1024 * 100,
                0.001
        );

        CpuIntensiveFileAggregator<String, BloomFilter<String>> subject = CpuIntensiveFileAggregator.aggregator(
                mr,
                ImmutableList.of(source),
                () -> firstBloomFilter,
                (acc, x) -> {
                    acc.put(x);
                    return acc;
                }
        );

        BloomFilter<String> aggregated = subject.call();

        deterministicAsciiStrings().limit(1000).forEach(s -> {
            assertFalse(aggregated.mightContain(s));
        });

        assertEquals(firstBloomFilter, aggregated);
    }

    private File getAsFile(String name) {
        try {
            return Paths.get(Objects.requireNonNull(this.getClass().getResource(name)).toURI()).toFile();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    private Stream<String> deterministicAsciiStrings() {
        Random random = new Random(48593L);
        return IntStream.range(0, 1000)
                .mapToObj(i -> {
                    StringBuilder value = new StringBuilder(64);
                    for (int j = 0; j < 64; j++) {
                        value.append((char) (Math.floorMod(random.nextInt(), 95) + 32));
                    }
                    return value.toString();
                });
    }
}
