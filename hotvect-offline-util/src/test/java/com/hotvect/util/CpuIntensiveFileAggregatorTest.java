package com.hotvect.util;

import com.codahale.metrics.MetricRegistry;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import com.hotvect.offlineutils.util.CpuIntensiveFileAggregator;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.quicktheories.QuickTheory.qt;
import static org.quicktheories.generators.SourceDSL.strings;

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
        MetricRegistry mr = new MetricRegistry();

        final BloomFilter<String> firstBloomFilter = BloomFilter.create(
                Funnels.stringFunnel(StandardCharsets.UTF_8),
                1024 * 1024 * 100,
                0.001
        );

          CpuIntensiveFileAggregator<BloomFilter<String>> subject = CpuIntensiveFileAggregator.aggregator(mr,
                ImmutableList.of(source),
                () -> firstBloomFilter,
                (acc, x) -> {
                    acc.put(x);
                    return acc;
                }

        );
        BloomFilter<String> aggregated = subject.call();

        qt().forAll(strings().ascii().ofLength(64)).checkAssert(
                s -> assertFalse(aggregated.test(s))
        );

        assertEquals(firstBloomFilter, aggregated);
    }


    private File getAsFile(String name) {
        try {
            return Paths.get(Objects.requireNonNull(this.getClass().getResource(name)).toURI()).toFile();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }
}
