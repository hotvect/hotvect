package com.hotvect.offlineutils.commandline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

public class PerformanceTestTaskSamplingTest {

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

        Function<String, List<String>> decoder = line -> List.of(line);

        PerformanceTestTask.SamplingCandidates<String> oversampledCandidates = PerformanceTestTask.collectDecodedCandidates(
                List.of(tempDir.resolve("a.txt").toFile(), tempDir.resolve("b.txt").toFile(), tempDir.resolve("c.txt").toFile()),
                decoder,
                30,
                10,
                2
        );
        List<String> normalSample = PerformanceTestTask.sampleDecodedExamples(
                List.of(tempDir.toFile()),
                decoder,
                10,
                1,
                42L,
                10,
                2
        );
        List<String> oversampledSample = PerformanceTestTask.sampleDecodedExamples(
                List.of(tempDir.toFile()),
                decoder,
                10,
                3,
                42L,
                10,
                2
        );

        assertEquals(30, oversampledCandidates.candidates().size());
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
    void candidateCollectionPreservesRoundRobinChunkOrder(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), "a1\na2\na3\na4\n");
        Files.writeString(tempDir.resolve("b.txt"), "b1\nb2\nb3\nb4\n");
        Files.writeString(tempDir.resolve("c.txt"), "c1\nc2\nc3\nc4\n");

        Function<String, List<String>> decoder = line -> List.of(line);

        PerformanceTestTask.SamplingCandidates<String> candidates = PerformanceTestTask.collectDecodedCandidates(
                List.of(tempDir.resolve("a.txt").toFile(), tempDir.resolve("b.txt").toFile(), tempDir.resolve("c.txt").toFile()),
                decoder,
                10,
                2,
                2
        );

        assertEquals(
                List.of("a1", "a2", "b1", "b2", "c1", "c2", "a3", "a4", "b3", "b4"),
                candidates.candidates()
        );
        assertEquals(3, candidates.maxFilesTouched());
        assertEquals(2, candidates.sweeps());
    }

    @Test
    void reservoirSamplingTouchesMinFilesEvenWhenFirstFileFillsTarget(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), "a1\na2\na3\na4\na5\na6\na7\na8\na9\na10\n");
        Files.writeString(tempDir.resolve("b.txt"), "b1\nb2\n");
        Files.writeString(tempDir.resolve("c.txt"), "c1\nc2\n");

        Function<String, List<String>> decoder = line -> List.of(line);
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
        assertEquals(5, sample.candidatesSeen());
        assertEquals(2, sample.maxFilesTouched());
        assertEquals(1, sample.sweeps());
    }

    @Test
    void candidateCollectionTouchesMinFilesEvenWhenFirstFileFillsTarget(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), "a1\na2\na3\na4\na5\na6\na7\na8\na9\na10\n");
        Files.writeString(tempDir.resolve("b.txt"), "b1\nb2\n");
        Files.writeString(tempDir.resolve("c.txt"), "c1\nc2\n");

        Function<String, List<String>> decoder = line -> List.of(line);

        PerformanceTestTask.SamplingCandidates<String> candidates = PerformanceTestTask.collectDecodedCandidates(
                List.of(tempDir.resolve("a.txt").toFile(), tempDir.resolve("b.txt").toFile(), tempDir.resolve("c.txt").toFile()),
                decoder,
                5,
                10,
                2
        );

        assertEquals(List.of("a1", "a2", "a3", "a4", "a5"), candidates.candidates());
        assertEquals(2, candidates.maxFilesTouched());
        assertEquals(1, candidates.sweeps());
    }
}
