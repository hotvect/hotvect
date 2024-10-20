package com.hotvect.offlineutils.util;

import com.codahale.metrics.MetricRegistry;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterators;
import com.google.common.io.MoreFiles;
import com.google.common.io.RecursiveDeleteOption;
import net.jqwik.api.AfterFailureMode;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileReducerTest {
    private static Path writeIntsAsMultiFiles(List<Integer> ints) throws IOException {
        Path tmpSource = Files.createTempDirectory("unit-test-delete-me");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                if (tmpSource.toFile().exists()) {
                    MoreFiles.deleteRecursively(tmpSource, RecursiveDeleteOption.ALLOW_INSECURE);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }));
        int numpartitions = 10;
        Iterators.partition(ints.iterator(), Math.max(ints.size() / numpartitions, 1)).forEachRemaining(partition -> {
            try {
                Path tmpFile = Files.createTempFile(tmpSource, "unit-test-delete-me", ".txt");
                // write the partition to the file newline delimited text file
                try (BufferedWriter writer = Files.newBufferedWriter(tmpFile, StandardCharsets.UTF_8)) {
                    partition.forEach(i -> {
                        try {
                            writer.write(String.valueOf(i));
                            writer.newLine();
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        return tmpSource;
    }

    @Property(afterFailure = AfterFailureMode.SAMPLE_FIRST, tries = 3)
    void testLargerFiles(@ForAll @IntRange(min = 2000000, max = 3000000) int size) throws Exception {
        List<Integer> ints = Arbitraries.integers().list().ofMinSize(size).sample();
        Path tmpSource = writeIntsAsMultiFiles(ints);
        try {
            MetricRegistry mr = new MetricRegistry();
            FileReducer<Integer> subject = FileReducer.reducer(
                    mr,
                    ImmutableList.of(tmpSource.toFile()),
                    () -> 0,
                    (acc, inputString) -> acc + Integer.parseInt(inputString),
                    Integer::sum,
                    2,
                    1000
            );
            Integer result = subject.call();
            assertEquals(ints.stream().mapToInt(i -> i).sum(), result);
        } finally {
            MoreFiles.deleteRecursively(tmpSource, RecursiveDeleteOption.ALLOW_INSECURE);
        }
    }

    @Property(afterFailure = AfterFailureMode.SAMPLE_FIRST)
    void testSmallerFiles(@ForAll @IntRange(max = 5) int size) throws Exception {
        List<Integer> ints = Arbitraries.integers().list().ofSize(size).sample();
        Path tmpSource = writeIntsAsMultiFiles(ints);
        try {
            MetricRegistry mr = new MetricRegistry();
            FileReducer<Integer> subject = FileReducer.reducer(
                    mr,
                    ImmutableList.of(tmpSource.toFile()),
                    () -> 0,
                    (acc, inputString) -> acc + Integer.parseInt(inputString),
                    Integer::sum,
                    2,
                    1000
            );
            Integer result = subject.call();
            assertEquals(ints.stream().mapToInt(i -> i).sum(), result);
        } finally {
            MoreFiles.deleteRecursively(tmpSource, RecursiveDeleteOption.ALLOW_INSECURE);
        }
    }

    @Test
    void corruptedFile() throws Exception {
        List<Integer> ints = IntStream.range(0, 1000000).boxed().collect(Collectors.toList());
        Path tmpSource = writeIntsAsMultiFiles(ints);
        renameLastFileToGz(tmpSource);

        assertThrows(Exception.class, () -> {

                    try {
                        MetricRegistry mr = new MetricRegistry();
                        FileReducer<Integer> subject = FileReducer.reducer(
                                mr,
                                ImmutableList.of(tmpSource.toFile()),
                                () -> 0,
                                (acc, inputString) -> acc + Integer.parseInt(inputString),
                                Integer::sum,
                                2,
                                1000
                        );
                        Integer result = subject.call();
                        assertEquals(ints.stream().mapToInt(i -> i).sum(), result);
                    } finally {
                        MoreFiles.deleteRecursively(tmpSource, RecursiveDeleteOption.ALLOW_INSECURE);
                    }
                }
        );
    }

    @Test
    void corruptedAccumulation() throws Exception {
        List<Integer> ints = IntStream.range(0, 1000000).boxed().collect(Collectors.toList());
        Path tmpSource = writeIntsAsMultiFiles(ints);

        assertThrows(Exception.class, () -> {

                    try {
                        MetricRegistry mr = new MetricRegistry();
                        FileReducer<Integer> subject = FileReducer.reducer(
                                mr,
                                ImmutableList.of(tmpSource.toFile()),
                                () -> 0,
                                (acc, inputString) -> {
                                    if (acc > 100000) {
                                        throw new IllegalArgumentException("Corrupted");
                                    }
                                    return acc + Integer.parseInt(inputString);

                                },
                                Integer::sum,
                                2,
                                1000
                        );
                        Integer result = subject.call();
                        assertEquals(ints.stream().mapToInt(i -> i).sum(), result);
                    } finally {
                        MoreFiles.deleteRecursively(tmpSource, RecursiveDeleteOption.ALLOW_INSECURE);
                    }
                }
        );
    }

    @Test
    void corruptedReduction() throws Exception {
        List<Integer> ints = IntStream.range(0, 1000000).boxed().collect(Collectors.toList());
        Path tmpSource = writeIntsAsMultiFiles(ints);

        assertThrows(Exception.class, () -> {

                    try {
                        MetricRegistry mr = new MetricRegistry();
                        FileReducer<Integer> subject = FileReducer.reducer(
                                mr,
                                ImmutableList.of(tmpSource.toFile()),
                                () -> 0,
                                (acc, inputString) -> acc + Integer.parseInt(inputString),
                                (i1, i2) -> {
                                    if (i1 > 100000) {
                                        throw new IllegalArgumentException("Corrupted");
                                    }
                                    return i1 + i2;
                                },
                                2,
                                1000
                        );
                        Integer result = subject.call();
                        assertEquals(ints.stream().mapToInt(i -> i).sum(), result);
                    } finally {
                        MoreFiles.deleteRecursively(tmpSource, RecursiveDeleteOption.ALLOW_INSECURE);
                    }
                }
        );
    }

    private static void renameLastFileToGz(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("Provided path is not a directory");
        }

        try (Stream<Path> paths = Files.list(directory)) {
            List<Path> sortedFiles = paths
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.naturalOrder())
                    .collect(Collectors.toList());

            if (!sortedFiles.isEmpty()) {
                Path lastFile = sortedFiles.get(sortedFiles.size() - 1);
                Path newFile = Paths.get(lastFile.toString() + ".gz");
                Files.move(lastFile, newFile);
            }
        }
    }

}