package com.hotvect.offlineutils.commandline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PerformanceTestTaskSamplingTest {

    @Test
    void pickSamplePoolSizeDefaultsToCappedSamples() {
        Options options = new Options();
        options.samples = 20_000;

        assertEquals(PerformanceTestTask.DEFAULT_SAMPLE_POOL_SIZE, PerformanceTestTask.pickSamplePoolSize(options));
    }

    @Test
    void pickSamplePoolSizeDefaultsWhenSamplesUnset() {
        Options options = new Options();

        assertEquals(PerformanceTestTask.DEFAULT_SAMPLE_POOL_SIZE, PerformanceTestTask.pickSamplePoolSize(options));
    }

    @Test
    void pickSamplePoolSizeHonorsExplicitOverride() {
        Options options = new Options();
        options.samples = 20_000;
        options.samplePoolSize = 128;

        assertEquals(128, PerformanceTestTask.pickSamplePoolSize(options));
    }

    @Test
    void samplingIsDeterministic(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), "a1\na2\na3\na4\na5\na6\na7\na8\na9\na10\n");
        Files.writeString(tempDir.resolve("b.txt"), "b1\nb2\nb3\nb4\nb5\nb6\nb7\nb8\nb9\nb10\n");
        Files.writeString(tempDir.resolve("c.txt"), "c1\nc2\nc3\nc4\nc5\nc6\nc7\nc8\nc9\nc10\n");

        Function<String, List<String>> decoder = line -> List.of(line);

        List<String> sample1 = PerformanceTestTask.sampleDecodedExamples(
                List.of(tempDir.toFile()),
                decoder,
                10,
                3,
                42L,
                5,
                2
        );
        List<String> sample2 = PerformanceTestTask.sampleDecodedExamples(
                List.of(tempDir.toFile()),
                decoder,
                10,
                3,
                42L,
                5,
                2
        );

        assertEquals(sample1, sample2);
    }

    @Test
    void samplingCanUseMultipleSweepsToFillSample(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), "a1\na2\na3\na4\n");
        Files.writeString(tempDir.resolve("b.txt"), "b1\nb2\nb3\nb4\n");
        Files.writeString(tempDir.resolve("c.txt"), "c1\nc2\nc3\nc4\n");

        Function<String, List<String>> decoder = line -> List.of(line);

        List<String> sample = PerformanceTestTask.sampleDecodedExamples(
                List.of(tempDir.toFile()),
                decoder,
                10,
                1,
                42L,
                2,
                2
        );

        assertEquals(10, sample.size());
    }

    @Test
    void oversamplingBuildsLargerCandidatePoolBeforeFinalSampling(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), "a1\na2\na3\na4\na5\na6\na7\na8\na9\na10\n");
        Files.writeString(tempDir.resolve("b.txt"), "b1\nb2\nb3\nb4\nb5\nb6\nb7\nb8\nb9\nb10\n");
        Files.writeString(tempDir.resolve("c.txt"), "c1\nc2\nc3\nc4\nc5\nc6\nc7\nc8\nc9\nc10\n");

        List<String> decoded = new ArrayList<>();
        Function<String, List<String>> decoder = line -> {
            decoded.add(line);
            return List.of(line);
        };
        List<String> normalSample = PerformanceTestTask.sampleDecodedExamples(
                List.of(tempDir.toFile()),
                decoder,
                10,
                1,
                42L,
                10,
                2
        );
        assertEquals(11, decoded.size());
        decoded.clear();

        List<String> oversampledSample = PerformanceTestTask.sampleDecodedExamples(
                List.of(tempDir.toFile()),
                decoder,
                10,
                3,
                42L,
                10,
                2
        );

        assertEquals(30, decoded.size());
        assertEquals(10, oversampledSample.size());
        assertNotEquals(normalSample, oversampledSample);
    }

    @Test
    void reservoirSamplingKeepsOnlyRequestedSampleSizeWhileScanningOversampledPool(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), "a1\na2\na3\na4\na5\na6\na7\na8\na9\na10\n");
        Files.writeString(tempDir.resolve("b.txt"), "b1\nb2\nb3\nb4\nb5\nb6\nb7\nb8\nb9\nb10\n");
        Files.writeString(tempDir.resolve("c.txt"), "c1\nc2\nc3\nc4\nc5\nc6\nc7\nc8\nc9\nc10\n");

        Function<String, List<String>> decoder = line -> List.of(line);
        List<java.io.File> files = List.of(
                tempDir.resolve("a.txt").toFile(),
                tempDir.resolve("b.txt").toFile(),
                tempDir.resolve("c.txt").toFile()
        );

        PerformanceTestTask.ReservoirSample<String> sample1 = PerformanceTestTask.reservoirSampleDecodedExamples(
                files,
                decoder,
                5,
                20,
                42L,
                10,
                2
        );
        PerformanceTestTask.ReservoirSample<String> sample2 = PerformanceTestTask.reservoirSampleDecodedExamples(
                files,
                decoder,
                5,
                20,
                42L,
                10,
                2
        );

        assertEquals(5, sample1.sample().size());
        assertEquals(20, sample1.candidatesSeen());
        assertEquals(sample1, sample2);
    }

    @Test
    void reservoirSamplingDecodesFilesInRoundRobinChunks(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), "a1\na2\na3\na4\n");
        Files.writeString(tempDir.resolve("b.txt"), "b1\nb2\nb3\nb4\n");
        Files.writeString(tempDir.resolve("c.txt"), "c1\nc2\nc3\nc4\n");

        List<String> decoded = new ArrayList<>();
        Function<String, List<String>> decoder = line -> {
            decoded.add(line);
            return List.of(line);
        };

        PerformanceTestTask.ReservoirSample<String> sample = PerformanceTestTask.reservoirSampleDecodedExamples(
                List.of(tempDir.resolve("a.txt").toFile(), tempDir.resolve("b.txt").toFile(), tempDir.resolve("c.txt").toFile()),
                decoder,
                5,
                10,
                42L,
                2,
                2
        );

        assertEquals(
                List.of("a1", "a2", "b1", "b2", "c1", "c2", "a3", "a4", "b3", "b4"),
                decoded
        );
        assertEquals(10, sample.candidatesSeen());
        assertEquals(3, sample.maxFilesTouched());
        assertEquals(2, sample.sweeps());
    }

    @Test
    void reservoirSamplingTouchesMinFilesEvenWhenFirstFileFillsTarget(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), "a1\na2\na3\na4\na5\na6\na7\na8\na9\na10\n");
        Files.writeString(tempDir.resolve("b.txt"), "b1\nb2\n");
        Files.writeString(tempDir.resolve("c.txt"), "c1\nc2\n");

        List<String> decoded = new ArrayList<>();
        Function<String, List<String>> decoder = line -> {
            decoded.add(line);
            return List.of(line);
        };
        List<java.io.File> files = List.of(
                tempDir.resolve("a.txt").toFile(),
                tempDir.resolve("b.txt").toFile(),
                tempDir.resolve("c.txt").toFile()
        );

        PerformanceTestTask.ReservoirSample<String> sample = PerformanceTestTask.reservoirSampleDecodedExamples(
                files,
                decoder,
                5,
                5,
                42L,
                10,
                2
        );

        assertEquals(5, sample.sample().size());
        assertEquals(List.of("a1", "a2", "a3", "a4", "a5", "b1"), decoded);
        assertEquals(6, sample.candidatesSeen());
        assertTrue(sample.sample().contains("b1"));
        assertEquals(2, sample.maxFilesTouched());
        assertEquals(1, sample.sweeps());
    }

    @Test
    void samplingContinuesPastAnEntireFilteredSweep(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("sparse.txt");
        Files.writeString(input, "skip\n".repeat(200) + "valid\n");

        assertEquals(List.of("valid"), PerformanceTestTask.sampleDecodedExamples(
                List.of(input.toFile()),
                line -> line.equals("skip") ? List.<String>of() : List.of(line),
                1, 3, 42L, 200, 20));
    }

    @Test
    void samplingContinuesPastAFilteredGapAfterFindingCandidates(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("gap.txt");
        Files.writeString(input, "first\nskip\nskip\nskip\nlast\n");

        var sample = PerformanceTestTask.reservoirSampleDecodedExamples(
                List.of(input.toFile()),
                line -> line.equals("skip") ? List.<String>of() : List.of(line),
                2, 2, 42L, 2, 1);

        assertEquals(2, sample.candidatesSeen());
        assertTrue(sample.sample().containsAll(List.of("first", "last")));
        assertEquals(3, sample.sweeps());
    }

    @Test
    void samplingExhaustsFilteredAndEmptyFiles(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("empty.txt"), "");
        Files.writeString(tempDir.resolve("filtered.txt"), "skip\n".repeat(5));
        List<String> decoded = new ArrayList<>();

        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                PerformanceTestTask.sampleDecodedExamples(
                        List.of(tempDir.toFile()), line -> {
                            decoded.add(line);
                            return List.of();
                        }, 1, 1, 42L, 2, 20));

        assertEquals(5, decoded.size());
        assertTrue(failure.getMessage().contains("did not decode any examples"));
    }

    @Test
    void samplingInspectsAnEmptyFileAndDecodesTheNextFileAfterReachingTarget(@TempDir Path tempDir) throws Exception {
        Path first = tempDir.resolve("first.txt");
        Path empty = tempDir.resolve("empty.txt");
        Path last = tempDir.resolve("last.txt");
        Files.writeString(first, "first\nunused\n");
        Files.writeString(empty, "");
        Files.writeString(last, "skip\nlast\n");
        List<String> decoded = new ArrayList<>();

        var sample = PerformanceTestTask.reservoirSampleDecodedExamples(
                List.of(first.toFile(), empty.toFile(), last.toFile()), line -> {
                    decoded.add(line);
                    return line.equals("skip") ? List.<String>of() : List.of(line);
                }, 1, 1, 42L, 2, 3);

        assertEquals(List.of("first", "skip", "last"), decoded);
        assertEquals(2, sample.candidatesSeen());
        assertEquals(3, sample.maxFilesTouched());
        assertEquals(1, sample.sample().size());
    }

}
