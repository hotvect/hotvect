package com.hotvect.onlineutils.concurrency.fileutils;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.Hashing;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

            File partFile = new File(dest, "part-00000.txt");
            assertEquals(payload.length, partFile.length(), "Expected full ByteBuffer to be written");
            partFile.delete();
        } finally {
            source.delete();
            dest.delete();
            tempDir.toFile().delete();
        }
    }


    @Test
    void happyPath() throws Exception {
        for (MapperCase testCase : mapperCases()) {
            long expected = calculateExpected(testCase.testData());
            List<File> source = toTestFiles(testCase.testData());
            Path tempDir = Files.createTempDirectory("test-sharding");
            File dest = new File(tempDir.toFile(), "sharddir");

            try {
                UnorderedFileMapper testSubject = UnorderedFileMapper.builder(source, dest, transformFun)
                        .meterRegistry(mr)
                        .nThreads(testCase.nThread())
                        .batchSize(testCase.batchSize())
                        .numberOfShards(1)
                        .extension(".txt")
                        .build();
                testSubject.call();

                List<File> partFiles = listPartFiles(dest);
                assertTrue(partFiles.stream().allMatch(part -> part.length() > 0), "No emitted part file should be empty");
                long actual = partFiles.stream()
                        .flatMap(part -> {
                            try {
                                return Files.readAllLines(part.toPath()).stream();
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        })
                        .mapToLong(Long::parseLong)
                        .sum();
                assertEquals(expected, actual);
                partFiles.forEach(File::delete);
            } finally {
                dest.delete();
                source.forEach(File::delete);
                tempDir.toFile().delete();
            }
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

            // Should create at least part-00000.txt
            File partFile = new File(dest, "part-00000.txt");
            assertEquals(true, partFile.exists(), "Part file part-00000.txt should exist");

            // Verify content correctness by reading all part files
            long actual = 0;
            for (int i = 0; i < 100; i++) {  // Check up to 100 shards
                File part = new File(dest, String.format(Locale.ROOT, "part-%05d.txt", i));
                if (part.exists()) {
                    List<String> written = Files.readAllLines(part.toPath());
                    actual += written.stream().mapToLong(Long::parseLong).sum();
                    part.delete();
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

            // Should create single part file
            File partFile = new File(dest, "part-00000.txt");
            assertEquals(true, partFile.exists(), "Part file part-00000.txt should exist");

            // Verify content correctness
            List<String> written = Files.readAllLines(partFile.toPath());
            long actual = written.stream().mapToLong(Long::parseLong).sum();
            assertEquals(expected, actual);

            partFile.delete();
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

            // Should create file with part index
            File partFile = new File(dest, "part-00000.txt");
            assertEquals(true, partFile.exists(), "Part file part-00000.txt should exist");

            // Verify content correctness
            List<String> written = Files.readAllLines(partFile.toPath());
            long actual = written.stream().mapToLong(Long::parseLong).sum();
            assertEquals(expected, actual);

            partFile.delete();
        } finally {
            dest.delete();
            source.forEach(File::delete);
            tempDir.toFile().delete();
        }
    }

    @Test
    void testShardingWithMultiple() throws Exception {
        // numberOfShards > 1 allows multiple files, but only non-empty shards are emitted.
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

            List<File> partFiles = listPartFiles(dest);
            assertFalse(partFiles.isEmpty(), "At least one non-empty part file should be emitted");
            assertTrue(partFiles.size() <= 3, "Should emit at most the configured shard count");
            assertTrue(partFiles.stream().allMatch(part -> part.length() > 0), "No emitted part file should be empty");

            // Verify content correctness across all parts using sum-based checksum
            long actual = partFiles.stream()
                    .flatMap(part -> {
                        try {
                            return Files.readAllLines(part.toPath()).stream();
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .mapToLong(Long::parseLong)
                    .sum();
            assertEquals(expected, actual);

            partFiles.forEach(File::delete);
        } finally {
            dest.delete();
            source.forEach(File::delete);
            tempDir.toFile().delete();
        }
    }

    @Test
    void testDefaultPartFilePattern() throws Exception {
        List<List<Integer>> testData = ImmutableList.of(ImmutableList.of(1, 2, 3, 5, 6, 7));
        long expected = calculateExpected(testData);
        List<File> source = toTestFiles(testData);
        Path tempDir = Files.createTempDirectory("test-part-files");
        File dest = new File(tempDir.toFile(), "partdir");

        try {
            UnorderedFileMapper testSubject = UnorderedFileMapper.builder(source, dest, transformFun)
                    .meterRegistry(mr)
                    .numberOfShards(3)
                    .extension(".txt")
                    .build();
            testSubject.call();

            List<File> partFiles = listPartFiles(dest);
            assertFalse(partFiles.isEmpty(), "At least one part file should be emitted");
            assertTrue(partFiles.stream().allMatch(part -> part.length() > 0), "No emitted part file should be empty");

            long actual = partFiles.stream()
                    .flatMap(part -> {
                        try {
                            return Files.readAllLines(part.toPath()).stream();
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .mapToLong(Long::parseLong)
                    .sum();
            assertEquals(expected, actual);

            partFiles.forEach(File::delete);
        } finally {
            dest.delete();
            source.forEach(File::delete);
            tempDir.toFile().delete();
        }
    }

    @Test
    void testSparseOutputsDoNotCreateEmptyPartFiles() throws Exception {
        Path tempDir = Files.createTempDirectory("test-sparse-shards");
        File dest = new File(tempDir.toFile(), "sharddir");
        File source = Files.createTempFile("unordered-sparse-source", ".txt").toFile();
        Files.writeString(source.toPath(), "1\n", StandardCharsets.UTF_8);

        Function<String, List<ByteBuffer>> oneLine = _s -> List.of(ByteBuffer.wrap("1\n".getBytes(StandardCharsets.UTF_8)));

        try {
            UnorderedFileMapper<String> testSubject = UnorderedFileMapper.builder(List.of(source), dest, oneLine)
                    .meterRegistry(mr)
                    .nThreads(1)
                    .batchSize(1)
                    .numberOfShards(3)
                    .extension(".txt")
                    .build();
            testSubject.call();

            List<File> partFiles = listPartFiles(dest);
            assertEquals(1, partFiles.size(), "Only shards that receive rows should be created");
            assertTrue(partFiles.get(0).getName().startsWith("part-"), "The emitted shard should use the part-* naming");
            assertTrue(partFiles.get(0).length() > 0, "The emitted shard must contain data");
            assertEquals(1L, Files.readAllLines(partFiles.get(0).toPath()).stream().mapToLong(Long::parseLong).sum());
        } finally {
            List<File> partFiles = listPartFiles(dest);
            partFiles.forEach(File::delete);
            source.delete();
            dest.delete();
            tempDir.toFile().delete();
        }
    }

    private List<File> listPartFiles(File dest) throws IOException {
        if (!dest.exists()) {
            return List.of();
        }
        try (Stream<Path> paths = Files.list(dest.toPath())) {
            return paths
                    .filter(Files::isRegularFile)
                    .map(Path::toFile)
                    .filter(file -> file.getName().startsWith("part-") && file.getName().endsWith(".txt"))
                    .sorted(Comparator.comparing(File::getName))
                    .toList();
        }
    }

    private record MapperCase(List<List<Integer>> testData, int nThread, int batchSize) {
    }

    private static List<MapperCase> mapperCases() {
        return List.of(
                new MapperCase(List.of(), 1, 1),
                new MapperCase(List.of(List.of(), List.of(1, 2, 3)), 1, 1),
                new MapperCase(List.of(List.of(-10, -1, 0, 1, 10), List.of(11, 12)), 2, 10),
                new MapperCase(List.of(List.of(100, 101, 102), List.of(1000, -1000)), 3, 100)
        );
    }

}
