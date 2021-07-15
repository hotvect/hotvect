package com.eshioji.hotvect.util;

import com.codahale.metrics.MetricRegistry;
import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.quicktheories.QuickTheory.qt;
import static org.quicktheories.generators.SourceDSL.strings;

@SuppressWarnings("UnstableApiUsage")
class CpuIntensiveFileAggregatorTest {
    @Test
    void normalFile() throws Exception {
        var source = getAsFile("example.jsons");
        test(source);
    }

    @Test
    void gzippedFile() throws Exception {
        var source = getAsFile("example.jsons.gz");
        test(source);
    }

    private void test(File source) throws Exception {
        var mr = new MetricRegistry();

        final BloomFilter<String> firstBloomFilter = BloomFilter.create(
                Funnels.stringFunnel(StandardCharsets.UTF_8),
                1024 * 1024 * 100,
                0.001
        );

        var subject = new CpuIntensiveFileAggregator<>(mr,
                source,
                () -> firstBloomFilter,
                (acc, x) -> {
                    acc.put(x);
                    return acc;
                }

        );
        var aggregated = subject.call();

        qt().forAll(strings().ascii().ofLength(64)).checkAssert(
                s -> assertFalse(aggregated.test(s))
        );

        assertEquals(firstBloomFilter, aggregated);
    }

    private File getTempFile() {
        try {
            return Files.createTempFile(this.getClass().getCanonicalName() + "-test", "tmp").toFile();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private byte[] getAsByteArray(File file) {
        try {
            return Files.readAllBytes(file.toPath());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    private BufferedReader getAsReader(String name) {
        return new BufferedReader(new InputStreamReader(this.getClass().getResourceAsStream(name), StandardCharsets.UTF_8));
    }

    private File getAsFile(String name) {
        try {
            return Paths.get(this.getClass().getResource(name).toURI()).toFile();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }
}