package com.hotvect.onlineutils.concurrency.fileutils;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.Hashing;
import net.jqwik.api.*;
import net.jqwik.api.arbitraries.ListArbitrary;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.WithNull;
import org.junit.jupiter.api.Test;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UnorderedFileMapperTest {
    private static final SimpleMeterRegistry mr = new SimpleMeterRegistry();

    private final Function<String, List<ByteBuffer>> transformFun = s -> {
        int parsed = Integer.parseInt(s);
        if (parsed % 4 == 0) {
            return Collections.emptyList();
        } else if (parsed % 3 == 0) {
            return List.of(ByteBuffer.wrap((parsed + "\n").getBytes(StandardCharsets.UTF_8)));
        } else {
            int hashed = Hashing.murmur3_32_fixed().hashInt(parsed).asInt();
            return List.of(ByteBuffer.wrap((parsed +"\n").getBytes(StandardCharsets.UTF_8)), ByteBuffer.wrap((hashed+"\n").getBytes(StandardCharsets.UTF_8)));
        }
    };

    @Test
    void failFast() throws Exception {
        List<File> source = toTestFiles(
                ImmutableList.of(
                        ImmutableList.of(1, 2, 3),
                        ImmutableList.of(4, 5, 6)

                )
        );

        Path tempDir = Files.createTempDirectory("test-fail-fast");
        File dest = new File(tempDir.toFile(), "encoded");

        try {
            Function<String, List<ByteBuffer>> badFun = s -> {
                int i = Integer.parseInt(s);
                if (i % 2 == 0) {
                    throw new RuntimeException("Bad thing");
                } else if (i % 3 == 0) {
                    try {
                        Thread.sleep(TimeUnit.DAYS.toMillis(1));
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
                throw new RuntimeException("Bad function");
            };


            UnorderedFileMapper testSubject = UnorderedFileMapper.builder(source, dest, badFun)
                    .meterRegistry(mr)
                    .numberOfShards(1)
                    .extension(".txt")
                    .build();

            assertThrows(Throwable.class, testSubject::call);
        } finally {
            dest.delete();
            source.forEach(File::delete);
            tempDir.toFile().delete();
        }

    }

    @Test
    void writesFullByteBuffer() throws Exception {
        Path tempDir = Files.createTempDirectory("test-unordered-largebuf");
        File dest = new File(tempDir.toFile(), "sharddir");

        File source = Files.createTempFile("unordered-largebuf-source", ".txt").toFile();
        Files.writeString(source.toPath(), "1\n", StandardCharsets.UTF_8);

        byte[] payload = new byte[20_000];
        Arrays.fill(payload, 0, payload.length - 1, (byte) 'b');
        payload[payload.length - 1] = '\n';

        Function<String, List<ByteBuffer>> largeBuf = _s -> List.of(ByteBuffer.wrap(payload));

        try {
            UnorderedFileMapper<String> testSubject = UnorderedFileMapper.builder(List.of(source), dest, largeBuf)
                    .meterRegistry(mr)
                    .nThreads(1)
                    .batchSize(1)
                    .numberOfShards(1)
                    .extension(".txt")
                    .build();
            testSubject.call();

            File shardFile = new File(dest, "shard_0.txt");
            assertEquals(payload.length, shardFile.length(), "Expected full ByteBuffer to be written");
            shardFile.delete();
        } finally {
            source.delete();
            dest.delete();
            tempDir.toFile().delete();
        }
    }


    @Property(afterFailure = AfterFailureMode.SAMPLE_FIRST, tries = 10)
    void happyPath(
            @ForAll("testdata") List<List<@WithNull Integer>> testData,
            @ForAll @IntRange(min = 1, max = 3) int nThread,
            @ForAll @IntRange(min = 1, max = 100) int batchSize
    ) throws Exception {
        long expected = calculateExpected(testData);
        List<File> source = toTestFiles(testData);
        Path tempDir = Files.createTempDirectory("test-sharding");
        File dest = new File(tempDir.toFile(), "sharddir");

        try {
            UnorderedFileMapper testSubject = UnorderedFileMapper.builder(source, dest, transformFun)
                    .meterRegistry(mr)
                    .nThreads(nThread)
                    .batchSize(batchSize)
                    .numberOfShards(1)  // Single shard for simplicity in property test
                    .extension(".txt")
                    .build();
            testSubject.call();

            // Read from shard_0.txt
            File shardFile = new File(dest, "shard_0.txt");
            List<String> written = Files.readAllLines(shardFile.toPath());
            long actual = written.stream().mapToLong(Long::parseLong).sum();
            assertEquals(expected, actual);

            shardFile.delete();
        } finally {
            dest.delete();
            source.forEach(File::delete);
            tempDir.toFile().delete();
        }

    }

    private long calculateExpected(List<List<Integer>> testData) {
        Stream<Integer> flattened = testData.stream().flatMap(Collection::stream).filter(Objects::nonNull);
        Stream<String> serialized = flattened.map(Object::toString);
        Stream<List<ByteBuffer>> transformed = serialized.map(transformFun);
        LongStream flattenedOutput = transformed.flatMap(Collection::stream).mapToLong(x -> Long.parseLong(new String(x.array(), StandardCharsets.UTF_8).strip()));

        return flattenedOutput.sum();
    }

    private List<File> toTestFiles(List<List<Integer>> testData) throws IOException {
        Path tmpdir = Files.createTempDirectory("unit-test-tmp-data-delete-me");
        List<File> ret = new ArrayList<>();
        for (List<Integer> testDatum : testData) {
            Path testFile = Paths.get(tmpdir.toString(), UUID.randomUUID() + ".txt");
            try (BufferedWriter out = new BufferedWriter(new FileWriter(testFile.toFile(), StandardCharsets.UTF_8), 32768)) {
                for (Integer integer : testDatum) {
                    String line = integer + System.lineSeparator();
                    out.write(line);
                }
            }
            testFile.toFile().deleteOnExit();
            ret.add(testFile.toFile());
        }
        return ret;
    }

    @Provide("testdata")
    private ListArbitrary<List<Integer>> generateTestData() {
        var ints = Arbitraries.integers().between(-10000, 10000);
        var file = ints.list().ofMinSize(0).ofMaxSize(1000);
        return file.list().ofMinSize(0).ofMaxSize(12);
    }

    @Test
    void testShardingWithNegativeValue() throws Exception {
        // numberOfShards <= 0 should auto-determine shard count (minimum 1 shard)
        List<List<Integer>> testData = ImmutableList.of(ImmutableList.of(1, 2, 3, 5, 6, 7));
        long expected = calculateExpected(testData);
        List<File> source = toTestFiles(testData);
        Path tempDir = Files.createTempDirectory("test-sharding");
        File dest = new File(tempDir.toFile(), "sharddir");

        try {
            UnorderedFileMapper testSubject = UnorderedFileMapper.builder(source, dest, transformFun)
                    .meterRegistry(mr)
                    .numberOfShards(-1)
                    .extension(".txt")
                    .build();
            testSubject.call();

            // Should create at least shard_0.txt
            File shardFile = new File(dest, "shard_0.txt");
            assertEquals(true, shardFile.exists(), "Shard file shard_0.txt should exist");

            // Verify content correctness by reading all shard files
            long actual = 0;
            for (int i = 0; i < 100; i++) {  // Check up to 100 shards
                File shard = new File(dest, "shard_" + i + ".txt");
                if (shard.exists()) {
                    List<String> written = Files.readAllLines(shard.toPath());
                    actual += written.stream().mapToLong(Long::parseLong).sum();
                    shard.delete();
                } else {
                    break;
                }
            }
            assertEquals(expected, actual);
        } finally {
            dest.delete();
            source.forEach(File::delete);
            tempDir.toFile().delete();
        }
    }

    @Test
    void testShardingWithZero() throws Exception {
        // numberOfShards == 0 should be treated as auto-sharding (creates 1 shard)
        List<List<Integer>> testData = ImmutableList.of(ImmutableList.of(1, 2, 3, 5, 6, 7));
        long expected = calculateExpected(testData);
        List<File> source = toTestFiles(testData);
        Path tempDir = Files.createTempDirectory("test-sharding");
        File dest = new File(tempDir.toFile(), "test-output");

        try {
            UnorderedFileMapper testSubject = UnorderedFileMapper.builder(source, dest, transformFun)
                    .meterRegistry(mr)
                    .numberOfShards(0)
                    .extension(".txt")
                    .build();
            testSubject.call();

            // Should create single shard file
            File shardFile = new File(dest, "shard_0.txt");
            assertEquals(true, shardFile.exists(), "Shard file test-output_0.txt should exist");

            // Verify content correctness
            List<String> written = Files.readAllLines(shardFile.toPath());
            long actual = written.stream().mapToLong(Long::parseLong).sum();
            assertEquals(expected, actual);

            shardFile.delete();
        } finally {
            dest.delete();
            source.forEach(File::delete);
            tempDir.toFile().delete();
        }
    }

    @Test
    void testShardingWithOne() throws Exception {
        // numberOfShards == 1 should produce single file
        List<List<Integer>> testData = ImmutableList.of(ImmutableList.of(1, 2, 3, 5, 6, 7));
        long expected = calculateExpected(testData);
        List<File> source = toTestFiles(testData);
        Path tempDir = Files.createTempDirectory("test-sharding");
        File dest = new File(tempDir.toFile(), "test-output");

        try {
            UnorderedFileMapper testSubject = UnorderedFileMapper.builder(source, dest, transformFun)
                    .meterRegistry(mr)
                    .numberOfShards(1)
                    .extension(".txt")
                    .build();
            testSubject.call();

            // Should create file with shard index
            File shardFile = new File(dest, "shard_0.txt");
            assertEquals(true, shardFile.exists(), "Shard file with _0 should exist");

            // Verify content correctness
            List<String> written = Files.readAllLines(shardFile.toPath());
            long actual = written.stream().mapToLong(Long::parseLong).sum();
            assertEquals(expected, actual);

            shardFile.delete();
        } finally {
            dest.delete();
            source.forEach(File::delete);
            tempDir.toFile().delete();
        }
    }

    @Test
    void testShardingWithMultiple() throws Exception {
        // numberOfShards > 1 should produce multiple files
        List<List<Integer>> testData = ImmutableList.of(ImmutableList.of(1, 2, 3, 5, 6, 7));
        long expected = calculateExpected(testData);
        List<File> source = toTestFiles(testData);
        Path tempDir = Files.createTempDirectory("test-sharding");
        File dest = new File(tempDir.toFile(), "sharddir");

        try {
            UnorderedFileMapper testSubject = UnorderedFileMapper.builder(source, dest, transformFun)
                    .meterRegistry(mr)
                    .numberOfShards(3)
                    .extension(".txt")
                    .build();
            testSubject.call();

            // Should create multiple files
            File shard0 = new File(dest, "shard_0.txt");
            File shard1 = new File(dest, "shard_1.txt");
            File shard2 = new File(dest, "shard_2.txt");

            assertEquals(true, shard0.exists(), "Shard 0 should exist");
            assertEquals(true, shard1.exists(), "Shard 1 should exist");
            assertEquals(true, shard2.exists(), "Shard 2 should exist");

            // Verify content correctness across all shards using sum-based checksum
            long actual = Stream.of(shard0, shard1, shard2)
                    .flatMap(shard -> {
                        try {
                            return Files.readAllLines(shard.toPath()).stream();
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .mapToLong(Long::parseLong)
                    .sum();
            assertEquals(expected, actual);

            shard0.delete();
            shard1.delete();
            shard2.delete();
        } finally {
            dest.delete();
            source.forEach(File::delete);
            tempDir.toFile().delete();
        }
    }

}
