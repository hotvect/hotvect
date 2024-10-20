package com.hotvect.offlineutils.util;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Slf4jReporter;
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
    private static final MetricRegistry mr = new MetricRegistry();

    static {
        Slf4jReporter.forRegistry(mr).build().start(5, TimeUnit.SECONDS);
    }

    private final Function<String, List<String>> transformFun = s -> {
        int parsed = Integer.parseInt(s);
        if (parsed % 4 == 0) {
            return Collections.emptyList();
        } else if (parsed % 3 == 0) {
            return List.of(String.valueOf(parsed));
        } else {
            int hashed = Hashing.murmur3_32_fixed().hashInt(parsed).asInt();
            return List.of(String.valueOf(parsed), String.valueOf(hashed));
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

        File dest = Files.createTempFile("unit-test-temp-data-delete-me", ".txt").toFile();
        dest.deleteOnExit();

        try {
            Function<String, List<String>> badFun = s -> {
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


            UnorderedFileMapper testSubject = UnorderedFileMapper.mapper(mr, source, dest, badFun);

            assertThrows(Throwable.class, testSubject::call);
        } finally {
            dest.delete();
            source.forEach(File::delete);
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
        File dest = Files.createTempFile("unit-test-temp-data-delete-me", ".txt").toFile();
        dest.deleteOnExit();

        try {
            UnorderedFileMapper testSubject = UnorderedFileMapper.mapper(mr, source, dest, transformFun, nThread, batchSize);
            testSubject.call();
            List<String> written = Files.readAllLines(dest.toPath());
            long actual = written.stream().mapToLong(Long::parseLong).sum();
            assertEquals(expected, actual);
        } finally {
            dest.delete();
            source.forEach(File::delete);
        }

    }

    private long calculateExpected(List<List<Integer>> testData) {
        Stream<Integer> flattened = testData.stream().flatMap(Collection::stream).filter(Objects::nonNull);
        Stream<String> serialized = flattened.map(Object::toString);
        Stream<List<String>> transformed = serialized.map(transformFun);
        LongStream flattenedOutput = transformed.flatMap(Collection::stream).mapToLong(Long::parseLong);
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
}