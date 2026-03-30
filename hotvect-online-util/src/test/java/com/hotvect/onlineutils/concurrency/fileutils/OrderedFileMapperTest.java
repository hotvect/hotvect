package com.hotvect.onlineutils.concurrency.fileutils;

import com.hotvect.onlineutils.testutil.StreamTestUtils;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.Hashing;
import com.hotvect.utils.Pair;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
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
    private static final Function<String, List<ByteBuffer>> HASH_STRING = s -> {
        byte[] bytes = new StringBuilder().append(Hashing.sha512().hashUnencodedChars(s).asInt()).append("\n").toString().getBytes(StandardCharsets.UTF_8);
        return List.of(ByteBuffer.wrap(bytes));
    };

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
    void gzippedInputFile() throws Exception {
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

    @Test
    void writesFullByteBuffer() throws Exception {
        SimpleMeterRegistry mr = new SimpleMeterRegistry();

        File source = Files.createTempFile("ordered-file-mapper-largebuf-source", ".txt").toFile();
        File dest = getTempFile();

        // One input line, one output record with >8KiB payload.
        Files.writeString(source.toPath(), "x\n", StandardCharsets.UTF_8);
        byte[] payload = new byte[20_000];
        for (int i = 0; i < payload.length - 1; i++) {
            payload[i] = 'a';
        }
        payload[payload.length - 1] = '\n';

        Function<String, List<ByteBuffer>> largeBuf = _s -> List.of(ByteBuffer.wrap(payload));

        try {
            OrderedFileMapper subject = OrderedFileMapper.mapper(mr, ImmutableList.of(source), dest, largeBuf, 1);
            subject.call();
            assertEquals(payload.length, dest.length(), "Expected full ByteBuffer to be written");
        } finally {
            dest.delete();
            source.delete();
        }
    }

    private void test(File source, Function<String, List<ByteBuffer>> hashFun) throws Exception {
        test(source, hashFun, -1);
    }


    private void test(File source, Function<String, List<ByteBuffer>> hashFun, int sample) throws Exception {
        SimpleMeterRegistry mr = new SimpleMeterRegistry();

        File dest = getTempFile();
        try {
            OrderedFileMapper subject = OrderedFileMapper.mapper(mr, ImmutableList.of(source), dest, hashFun, sample);
            Map<String, Object> metadata = subject.call();
            assertTrue(metadata.containsKey("mean_throughput"));
            assertTrue(metadata.containsKey("total_record_count"));
            try (BufferedReader original = getAsReader(); BufferedReader processed = getAsReader(dest)) {
                StreamTestUtils.zip(original.lines(), processed.lines(), (original1, actual) -> {
                    int expected = Hashing.sha512().hashUnencodedChars(original1).asInt();
                    Integer actualOut = Integer.valueOf(actual);
                    return Pair.of(expected, actualOut);
                })
                        .forEach(p -> Assertions.assertEquals(p.first(), p.second()));
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
