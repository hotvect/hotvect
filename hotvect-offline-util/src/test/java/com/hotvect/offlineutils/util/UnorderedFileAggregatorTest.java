package com.hotvect.offlineutils.util;

import com.codahale.metrics.MetricRegistry;
import net.jqwik.api.*;
import net.jqwik.api.arbitraries.ListArbitrary;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.WithNull;
import org.junit.jupiter.api.Test;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;

class UnorderedFileAggregatorTest {

    @Test
    void failFast() throws Exception {
        MetricRegistry mr = new MetricRegistry();

        List<File> source = toTestFiles(
                Arrays.asList(
                        Arrays.asList(1, 2, 3),
                        Arrays.asList(4, 5, 6)
                )
        );
        AtomicLong state = new AtomicLong(0);
        BiConsumer<AtomicLong, String> badUpdate = (s, str) -> {
            int i = Integer.parseInt(str);
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
        UnorderedFileAggregator<AtomicLong> testSubject = UnorderedFileAggregator.aggregator(mr, source, state, badUpdate);
        assertThrows(RuntimeException.class, testSubject::call);
        source.forEach(File::delete);
    }

    @Property(afterFailure = AfterFailureMode.SAMPLE_FIRST, tries = 10)
    void happyPath(
            @ForAll("testdata") List<List<@WithNull Integer>> testData,
            @ForAll @IntRange(min = 1, max = 3) int nThread,
            @ForAll @IntRange(min = 1, max = 100) int batchSize
    ) throws Exception {
        MetricRegistry mr = new MetricRegistry();

        long expectedSum = calculateExpectedSum(testData);
        long expectedLineCount = calculateExpectedLineCount(testData);
        List<File> source = toTestFiles(testData);
        AtomicLong state = new AtomicLong(0);
        BiConsumer<AtomicLong, String> update = (s, str) -> s.addAndGet(Long.parseLong(str));
        UnorderedFileAggregator<AtomicLong> testSubject = UnorderedFileAggregator.aggregator(mr, source, state, update, nThread, batchSize);
        Map<String, Object> metadata = testSubject.call();
        long actualSum = state.get();
        assertEquals(expectedSum, actualSum);

        long linesRead = (Long) metadata.get("lines_read");
        long recordsProcessed = (Long) metadata.get("records_processed");
        long numberOfFilesRead = (Integer) metadata.get("number_of_files_read");

        assertEquals(expectedLineCount, linesRead);
        assertEquals(expectedLineCount, recordsProcessed);
        assertEquals(source.size(), numberOfFilesRead);

        source.forEach(File::delete);
    }

    private long calculateExpectedSum(List<List<Integer>> testData) {
        return testData.stream()
                .flatMap(Collection::stream)
                .filter(Objects::nonNull)
                .mapToLong(Integer::longValue)
                .sum();
    }

    private long calculateExpectedLineCount(List<List<Integer>> testData) {
        return testData.stream()
                .flatMap(Collection::stream)
                .filter(Objects::nonNull)
                .count();
    }

    private List<File> toTestFiles(List<List<Integer>> testData) throws Exception {
        Path tmpdir = Files.createTempDirectory("unit-test-tmp-data-delete-me");
        List<File> ret = new ArrayList<>();
        for (List<Integer> testDatum : testData) {
            Path testFile = Paths.get(tmpdir.toString(), UUID.randomUUID() + ".txt");
            try (BufferedWriter out = new BufferedWriter(new FileWriter(testFile.toFile(), StandardCharsets.UTF_8), 32768)) {
                for (Integer integer : testDatum) {
                    if (integer != null) {
                        String line = integer + System.lineSeparator();
                        out.write(line);
                    }
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