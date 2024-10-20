package com.hotvect.util;

import com.codahale.metrics.MetricRegistry;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.Hashing;
import com.hotvect.offlineutils.util.OrderedFileMapper;
import com.hotvect.utils.Pair;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("UnstableApiUsage")
public class OrderedFileMapperTest {
    private static final Function<String, List<String>> HASH_STRING = s ->
            ImmutableList.of(String.valueOf(Hashing.sha512().hashString(s, StandardCharsets.UTF_8).asInt()));
    @Test
    void withSamplesDirectory() throws Exception {
        File source = getAsFile("multiple");
        for (Integer i : List.of(1, 10, 999, 1200, 2000)) {
            test(source, HASH_STRING, i);
        }
    }

    @Test
    void withSamples() throws Exception {
        File source = getAsFile("example.jsons");
        for (Integer i : List.of(1, 10, 999)) {
            test(source, HASH_STRING, i);
        }
    }


    @Test
    void normalFile() throws Exception {
        File source = getAsFile("example.jsons");
        test(source, HASH_STRING);
    }

    @Test
    void gzippedFile() throws Exception {
        File source = getAsFile("example.jsons.gz");
        test(source, HASH_STRING);
    }

    @Test
    void corruptedFile() throws Exception {
        File source = getAsFile("example.jsons");
        final AtomicInteger i = new AtomicInteger();

        assertThrows(RuntimeException.class, () -> test(source, s -> {
            if (i.incrementAndGet() > 10) {
                throw new RuntimeException("Corrupted");
            } else {
                return HASH_STRING.apply(s);
            }
        }));
    }

    private void test(File source, Function<String, List<String>> hashFun) throws Exception {
        test(source, hashFun, -1);
    }


    private void test(File source, Function<String, List<String>> hashFun, int sample) throws Exception {
        MetricRegistry mr = new MetricRegistry();

        File dest = getTempFile();
        try {
            OrderedFileMapper subject = OrderedFileMapper.mapper(mr, ImmutableList.of(source), dest, hashFun, sample);
            Map<String, Object> metadata = subject.call();
            assertTrue(metadata.containsKey("mean_throughput"));
            assertTrue(metadata.containsKey("total_record_count"));
            try (BufferedReader original = getAsReader();
                 BufferedReader processed = getAsReader(dest)) {
                StreamUtils.zip(original.lines(), processed.lines(), (original1, actual) -> {
                    int expected = Hashing.sha512().hashString(original1, StandardCharsets.UTF_8).asInt();
                    Integer actualOut = Integer.valueOf(actual);
                    return Pair.of(expected, actualOut);
                }).forEach(p -> assertEquals(p._1, p._2));
            }

            if (sample > 0) {
                try (BufferedReader processed = getAsReader(dest)) {
                    long lineNum = processed.lines().mapToInt(i -> 1).sum();
                    assertEquals(sample, lineNum);
                }
            }
        } finally {
            final boolean delete = dest.delete();
            assertTrue(delete);
        }
    }

    private File getTempFile() {
        try {
            return Files.createTempFile(this.getClass().getCanonicalName() + "-test", "tmp").toFile();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private BufferedReader getAsReader(File file) {
        try {
            return new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
    }


    private BufferedReader getAsReader() {
        return new BufferedReader(
                new InputStreamReader(
                        Objects.requireNonNull(
                                this.getClass().getResourceAsStream("example.jsons")
                        ), StandardCharsets.UTF_8
                )
        );
    }

    private File getAsFile(String name) {
        try {
            return Paths.get(Objects.requireNonNull(this.getClass().getResource(name)).toURI()).toFile();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }
}