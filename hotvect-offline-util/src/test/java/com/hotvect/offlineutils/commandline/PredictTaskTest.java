package com.hotvect.offlineutils.commandline;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.algodefinition.AlgorithmId;
import com.hotvect.api.algodefinition.common.RewardFunction;
import com.hotvect.api.algodefinition.common.RewardFunctionFactory;
import com.hotvect.api.algodefinition.ranking.RankerFactory;
import com.hotvect.api.algodefinition.ranking.RankingExampleDecoderFactory;
import com.hotvect.api.algodefinition.ranking.RankingTransformer;
import com.hotvect.api.algodefinition.ranking.RankingTransformerFactory;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.codec.ranking.RankingExampleDecoder;
import com.hotvect.api.data.common.Example;
import com.hotvect.api.data.ranking.*;
import com.hotvect.onlineutils.concurrency.fileutils.OrderedFileMapper;
import com.hotvect.onlineutils.concurrency.fileutils.UnorderedFileMapper;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


public class PredictTaskTest {
    public static class ExampleDecoderFactory implements RankingExampleDecoderFactory<String, String, String> {
        @Override
        public RankingExampleDecoder<String, String, String> apply(Optional<JsonNode> hyperparameter) {
            return toDecode -> ImmutableList.of(
                    new RankingExample<String, String, String>(
                            "example",
                            RankingTestData.request("example", "shared", "action1", "action2"),
                            ImmutableList.of(
                                    new RankingOutcome<>(
                                            RankingDecision.builder("action1", 0, "action1").build(),
                                            "outcome"
                                    ),
                                    new RankingOutcome<>(
                                            RankingDecision.builder("action2", 1, "action2").build(),
                                            "outcome"
                                    )
                            )
                    )
            );
        }
    }

    public static class TestTransformer implements RankingTransformerFactory<String, String> {
        @Override
        public RankingTransformer<String, String> apply(Optional<JsonNode> hyperparameter, Map<String, InputStream> parameter) {
            return null;
        }
    }

    public static class TestAlgorithmFactory implements RankerFactory<RankingTransformer<String, String>, String, String> {
        @Override
        public Ranker<String, String> apply(RankingTransformer<String, String> stringStringStringRankingTransformer, Map<String, InputStream> parameters, Optional<JsonNode> hyperparameter) {
            return rankingRequest -> RankingResponse.newResponse(
                    java.util.stream.IntStream.range(0, rankingRequest.actions().size())
                            .mapToObj(i -> {
                                var action = rankingRequest.actions().get(i);
                                return RankingDecision.builder(action.actionId(), i, action.action()).build();
                            })
                            .toList()
            );
        }
    }

    public static class TestRewardFunctionFactoroy implements RewardFunctionFactory<String> {
        @Override
        public RewardFunction<String> get() {
            return s -> 1.0;
        }
    }

    private static AlgorithmDefinition algorithmDefinition() {
        return algorithmDefinition(null);
    }

    private static AlgorithmDefinition algorithmDefinition(JsonNode rawAlgorithmDefinition) {
        String nestedClassPrefix = ExampleDecoderFactory.class.getDeclaringClass().getCanonicalName() + "$";
        return new AlgorithmDefinition(
                rawAlgorithmDefinition,
                new AlgorithmId("test-algorithm", "1.2.3"),
                ImmutableMap.of(),
                ImmutableMap.of(),
                null,
                nestedClassPrefix + ExampleDecoderFactory.class.getSimpleName(),
                nestedClassPrefix + TestTransformer.class.getSimpleName(),
                null,
                nestedClassPrefix + TestRewardFunctionFactoroy.class.getSimpleName(),
                null,
                nestedClassPrefix + TestAlgorithmFactory.class.getSimpleName(),
                Optional.<JsonNode>empty(),
                Optional.<JsonNode>empty(),
                Optional.<JsonNode>empty(),
                Optional.<JsonNode>empty(),
                Optional.<JsonNode>empty()
        );
    }

    @Test
    void supportsMissingParameterZipWhenAlgorithmDoesNotNeedParameters() throws Exception {
        Options options = new Options();
        options.sourceFiles = ImmutableMap.of("default", ImmutableList.of(Paths.get(Objects.requireNonNull(this.getClass().getResource("multiple")).toURI()).toFile()));
        Path tempDir = Files.createTempDirectory("predict-no-parameters");
        options.destinationFile = tempDir.resolve("prediction").toFile();

        try {
            OfflineTaskContext offlineTaskContext = new OfflineTaskContext(
                    new URLClassLoader(new URL[0], this.getClass().getClassLoader()),
                    new SimpleMeterRegistry(),
                    options,
                    algorithmDefinition()
            );

            PredictTask<? extends Example<?, ?>, Ranker<String, String>, String> testSubject = new PredictTask<>(offlineTaskContext) {
                @Override
                protected Map<String, Object> callOrderedFileMapper(OrderedFileMapper processor) {
                    throw new AssertionError("Ordered path should not be used by default");
                }

                @Override
                protected Map<String, Object> callUnorderedFileMapper(UnorderedFileMapper<String> processor) {
                    return Map.of("lines_written", 1L);
                }
            };

            var result = testSubject.perform();
            assertEquals("unordered", result.get("prediction_output_ordering"));
        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Test
    void noSampling() throws Exception {
        Options options = new Options();
        options.parameters = Paths.get(Objects.requireNonNull(this.getClass().getResource("test-algorithm-parameter.zip")).toURI()).toFile();
        options.sourceFiles = ImmutableMap.of("default", ImmutableList.of(Paths.get(Objects.requireNonNull(this.getClass().getResource("multiple")).toURI()).toFile()));
        Path tempDir = Files.createTempDirectory("predict-no-sampling");
        options.destinationFile = tempDir.resolve("prediction").toFile();

        try{
            OfflineTaskContext offlineTaskContext = new OfflineTaskContext(new URLClassLoader(new URL[0], this.getClass().getClassLoader()), new SimpleMeterRegistry(), options, algorithmDefinition());

            PredictTask<? extends Example<?, ?>, Ranker<String, String>, String> testSubject = new PredictTask<>(offlineTaskContext);

            var result = testSubject.perform();
            System.out.println(result);

        }finally {
            deleteRecursively(tempDir);
        }
    }

    @Test
    void withSampling() throws Exception {
        Options options = new Options();
        options.parameters = Paths.get(Objects.requireNonNull(this.getClass().getResource("test-algorithm-parameter.zip")).toURI()).toFile();
        options.sourceFiles = ImmutableMap.of("default", ImmutableList.of(Paths.get(Objects.requireNonNull(this.getClass().getResource("multiple")).toURI()).toFile()));
        Path tempDir = Files.createTempDirectory("predict-with-sampling");
        options.destinationFile = tempDir.resolve("prediction").toFile();
        options.samples = 1987;

        try{
            OfflineTaskContext offlineTaskContext = new OfflineTaskContext(new URLClassLoader(new URL[0], this.getClass().getClassLoader()), new SimpleMeterRegistry(), options, algorithmDefinition());

            PredictTask<? extends Example<?, ?>, Ranker<String, String>, String> testSubject = new PredictTask<>(offlineTaskContext);

            var result = testSubject.perform();
            System.out.println(result);

        }finally {
            deleteRecursively(tempDir);
        }

    }

    @Test
    void defaultsToUnorderedPredictWhenFlagsAndAlgorithmDefinitionDoNotSpecifyOrdering() throws Exception {
        Options options = new Options();
        options.parameters = Paths.get(Objects.requireNonNull(this.getClass().getResource("test-algorithm-parameter.zip")).toURI()).toFile();
        options.sourceFiles = ImmutableMap.of("default", ImmutableList.of(Paths.get(Objects.requireNonNull(this.getClass().getResource("multiple")).toURI()).toFile()));
        Path tempDir = Files.createTempDirectory("predict-default-unordered");
        options.destinationFile = tempDir.resolve("prediction").toFile();

        OfflineTaskContext offlineTaskContext = new OfflineTaskContext(
                new URLClassLoader(new URL[0], this.getClass().getClassLoader()),
                new SimpleMeterRegistry(),
                options,
                algorithmDefinition()
        );

        PredictTask<? extends Example<?, ?>, Ranker<String, String>, String> testSubject = new PredictTask<>(offlineTaskContext) {
            @Override
            protected Map<String, Object> callOrderedFileMapper(OrderedFileMapper processor) {
                throw new AssertionError("Ordered path should not be used by default");
            }

            @Override
            protected Map<String, Object> callUnorderedFileMapper(UnorderedFileMapper<String> processor) {
                return Map.of("lines_written", 1L);
            }
        };

        var result = testSubject.perform();
        assertEquals("unordered", result.get("prediction_output_ordering"));
        assertTrue(((Number) result.get("prediction_writer_num_shards")).intValue() >= 1);
        deleteRecursively(tempDir);
    }

    @Test
    void unorderedPredictShouldWriteShardedOutputDirectory() throws Exception {
        Options options = new Options();
        options.parameters = Paths.get(Objects.requireNonNull(this.getClass().getResource("test-algorithm-parameter.zip")).toURI()).toFile();
        options.sourceFiles = ImmutableMap.of("default", ImmutableList.of(Paths.get(Objects.requireNonNull(this.getClass().getResource("multiple")).toURI()).toFile()));
        File tempDir = Files.createTempDirectory("predict-unordered").toFile();
        tempDir.deleteOnExit();
        File destinationPath = new File(tempDir, "prediction");
        options.destinationFile = destinationPath;
        options.unordered = true;
        options.writerNumShards = 2;

        try {
            OfflineTaskContext offlineTaskContext = new OfflineTaskContext(new URLClassLoader(new URL[0], this.getClass().getClassLoader()), new SimpleMeterRegistry(), options, algorithmDefinition());

            PredictTask<? extends Example<?, ?>, Ranker<String, String>, String> testSubject = new PredictTask<>(offlineTaskContext);

            var result = testSubject.perform();

            List<String> outputFiles;
            List<String> lines;
            try (var partFiles = Files.list(destinationPath.toPath())) {
                outputFiles = partFiles
                        .map(path -> path.getFileName().toString())
                        .sorted()
                        .toList();
            }
            try (var partFiles = Files.list(destinationPath.toPath())) {
                lines = partFiles
                        .sorted()
                        .flatMap(path -> {
                            try {
                                return Files.lines(path, StandardCharsets.UTF_8);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        })
                        .toList();
            }
            assertFalse(outputFiles.isEmpty());
            assertTrue(outputFiles.size() <= 2);
            assertTrue(outputFiles.stream().allMatch(fileName ->
                    fileName.equals("part-00000.jsonl") || fileName.equals("part-00001.jsonl")
            ));
            assertEquals("unordered", result.get("prediction_output_ordering"));
            assertEquals(2, result.get("prediction_writer_num_shards"));
            assertEquals(true, destinationPath.isDirectory());
            assertEquals(result.get("total_record_count"), (long) lines.size());
            assertEquals(result.get("lines_written"), (long) lines.size());
        } finally {
            deleteRecursively(tempDir.toPath());
        }
    }

    @Test
    void algorithmDefinitionCanEnableUnorderedPredict() throws Exception {
        Options options = new Options();
        options.parameters = Paths.get(Objects.requireNonNull(this.getClass().getResource("test-algorithm-parameter.zip")).toURI()).toFile();
        options.sourceFiles = ImmutableMap.of("default", ImmutableList.of(Paths.get(Objects.requireNonNull(this.getClass().getResource("multiple")).toURI()).toFile()));
        Path tempDir = Files.createTempDirectory("predict-algodef-unordered");
        options.destinationFile = tempDir.resolve("prediction").toFile();

        ObjectNode rawAlgorithmDefinition = JsonNodeFactory.instance.objectNode();
        rawAlgorithmDefinition.putObject("hotvect_execution_parameters")
                .putObject("predict")
                .put("ordered", false)
                .put("writer_num_shards", 2);

        OfflineTaskContext offlineTaskContext = new OfflineTaskContext(
                new URLClassLoader(new URL[0], this.getClass().getClassLoader()),
                new SimpleMeterRegistry(),
                options,
                algorithmDefinition(rawAlgorithmDefinition)
        );

        PredictTask<? extends Example<?, ?>, Ranker<String, String>, String> testSubject = new PredictTask<>(offlineTaskContext) {
            @Override
            protected Map<String, Object> callOrderedFileMapper(OrderedFileMapper processor) {
                throw new AssertionError("Ordered path should not be used");
            }

            @Override
            protected Map<String, Object> callUnorderedFileMapper(UnorderedFileMapper<String> processor) {
                return Map.of("lines_written", 1L);
            }
        };

        var result = testSubject.perform();
        assertEquals("unordered", result.get("prediction_output_ordering"));
        assertEquals(2, result.get("prediction_writer_num_shards"));
        deleteRecursively(tempDir);
    }

    @Test
    void orderedPredictWritesSinglePartFile() throws Exception {
        Options options = new Options();
        options.parameters = Paths.get(Objects.requireNonNull(this.getClass().getResource("test-algorithm-parameter.zip")).toURI()).toFile();
        options.sourceFiles = ImmutableMap.of("default", ImmutableList.of(Paths.get(Objects.requireNonNull(this.getClass().getResource("multiple")).toURI()).toFile()));
        Path tempDir = Files.createTempDirectory("predict-ordered-layout");
        Path destinationPath = tempDir.resolve("prediction");
        options.destinationFile = destinationPath.toFile();
        options.ordered = true;

        try {
            OfflineTaskContext offlineTaskContext = new OfflineTaskContext(
                    new URLClassLoader(new URL[0], this.getClass().getClassLoader()),
                    new SimpleMeterRegistry(),
                    options,
                    algorithmDefinition()
            );

            PredictTask<? extends Example<?, ?>, Ranker<String, String>, String> testSubject = new PredictTask<>(offlineTaskContext);
            var result = testSubject.perform();

            List<String> outputFiles;
            try (var partFiles = Files.list(destinationPath)) {
                outputFiles = partFiles
                        .map(path -> path.getFileName().toString())
                        .sorted()
                        .toList();
            }

            assertEquals(List.of("part-00000.jsonl"), outputFiles);
            assertEquals("ordered", result.get("prediction_output_ordering"));
            assertEquals(1, result.get("prediction_writer_num_shards"));
            assertTrue(!Files.exists(destinationPath.resolve("shard_0.jsonl")));
        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Test
    void algorithmDefinitionCanAutoDetermineUnorderedPredictShardCount() throws Exception {
        Options options = new Options();
        options.parameters = Paths.get(Objects.requireNonNull(this.getClass().getResource("test-algorithm-parameter.zip")).toURI()).toFile();
        options.sourceFiles = ImmutableMap.of("default", ImmutableList.of(Paths.get(Objects.requireNonNull(this.getClass().getResource("multiple")).toURI()).toFile()));
        Path tempDir = Files.createTempDirectory("predict-algodef-unordered-auto");
        options.destinationFile = tempDir.resolve("prediction").toFile();

        ObjectNode rawAlgorithmDefinition = JsonNodeFactory.instance.objectNode();
        rawAlgorithmDefinition.putObject("hotvect_execution_parameters")
                .putObject("predict")
                .put("ordered", false);

        OfflineTaskContext offlineTaskContext = new OfflineTaskContext(
                new URLClassLoader(new URL[0], this.getClass().getClassLoader()),
                new SimpleMeterRegistry(),
                options,
                algorithmDefinition(rawAlgorithmDefinition)
        );

        PredictTask<? extends Example<?, ?>, Ranker<String, String>, String> testSubject = new PredictTask<>(offlineTaskContext) {
            @Override
            protected Map<String, Object> callOrderedFileMapper(OrderedFileMapper processor) {
                throw new AssertionError("Ordered path should not be used");
            }

            @Override
            protected Map<String, Object> callUnorderedFileMapper(UnorderedFileMapper<String> processor) {
                return Map.of("lines_written", 1L);
            }
        };

        var result = testSubject.perform();
        assertEquals("unordered", result.get("prediction_output_ordering"));
        assertTrue(((Number) result.get("prediction_writer_num_shards")).intValue() >= 1);
        assertTrue(((Number) result.get("prediction_writer_num_shards")).intValue() <= 16);
        deleteRecursively(tempDir);
    }

    @Test
    void unorderedPredictHonorsQueueLengthForReadAndWriteQueues() throws Exception {
        Options options = new Options();
        options.parameters = Paths.get(Objects.requireNonNull(this.getClass().getResource("test-algorithm-parameter.zip")).toURI()).toFile();
        options.sourceFiles = ImmutableMap.of("default", ImmutableList.of(Paths.get(Objects.requireNonNull(this.getClass().getResource("multiple")).toURI()).toFile()));
        Path tempDir = Files.createTempDirectory("predict-queue-length");
        options.destinationFile = tempDir.resolve("prediction").toFile();
        options.unordered = true;
        options.queueLength = 777;

        try {
            OfflineTaskContext offlineTaskContext = new OfflineTaskContext(
                    new URLClassLoader(new URL[0], this.getClass().getClassLoader()),
                    new SimpleMeterRegistry(),
                    options,
                    algorithmDefinition()
            );

            PredictTask<? extends Example<?, ?>, Ranker<String, String>, String> testSubject = new PredictTask<>(offlineTaskContext) {
                @Override
                protected Map<String, Object> callUnorderedFileMapper(UnorderedFileMapper<String> processor) throws Exception {
                    assertEquals(777, readPrivateInt(processor, "readQueueSize"));
                    assertEquals(777, readPrivateInt(processor, "writeQueueSize"));
                    return Map.of("lines_written", 1L);
                }
            };

            var result = testSubject.perform();
            assertEquals(777, result.get("prediction_effective_read_queue_size"));
            assertEquals(777, result.get("prediction_effective_write_queue_size"));
        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Test
    void forwardsLegacyQueueLengthToUnorderedMapper() throws Exception {
        Options options = new Options();
        options.parameters = Paths.get(Objects.requireNonNull(this.getClass().getResource("test-algorithm-parameter.zip")).toURI()).toFile();
        options.sourceFiles = ImmutableMap.of("default", ImmutableList.of(Paths.get(Objects.requireNonNull(this.getClass().getResource("multiple")).toURI()).toFile()));
        Path tempDir = Files.createTempDirectory("predict-unordered-legacy-queue");
        options.destinationFile = tempDir.resolve("prediction").toFile();
        options.unordered = true;
        options.queueLength = 7;
        options.maxThreads = 3;
        options.batchSize = 5;

        try {
            OfflineTaskContext offlineTaskContext = new OfflineTaskContext(
                    new URLClassLoader(new URL[0], this.getClass().getClassLoader()),
                    new SimpleMeterRegistry(),
                    options,
                    algorithmDefinition()
            );

            final UnorderedFileMapper<String>[] captured = new UnorderedFileMapper[1];
            PredictTask<? extends Example<?, ?>, Ranker<String, String>, String> testSubject = new PredictTask<>(offlineTaskContext) {
                @Override
                protected Map<String, Object> callUnorderedFileMapper(UnorderedFileMapper<String> processor) {
                    captured[0] = processor;
                    return Map.of("lines_written", 1L);
                }
            };

            testSubject.perform();

            assertNotNull(captured[0]);
            assertEquals(7, readPrivateInt(captured[0], "readQueueSize"));
            assertEquals(7, readPrivateInt(captured[0], "writeQueueSize"));
        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Test
    void forwardsSplitQueueLengthsToUnorderedMapper() throws Exception {
        Options options = new Options();
        options.parameters = Paths.get(Objects.requireNonNull(this.getClass().getResource("test-algorithm-parameter.zip")).toURI()).toFile();
        options.sourceFiles = ImmutableMap.of("default", ImmutableList.of(Paths.get(Objects.requireNonNull(this.getClass().getResource("multiple")).toURI()).toFile()));
        Path tempDir = Files.createTempDirectory("predict-unordered-split-queue");
        options.destinationFile = tempDir.resolve("prediction").toFile();
        options.unordered = true;
        options.queueLength = 7;
        options.readQueueLength = 11;
        options.writeQueueLength = 13;
        options.maxThreads = 3;
        options.batchSize = 5;

        try {
            OfflineTaskContext offlineTaskContext = new OfflineTaskContext(
                    new URLClassLoader(new URL[0], this.getClass().getClassLoader()),
                    new SimpleMeterRegistry(),
                    options,
                    algorithmDefinition()
            );

            final UnorderedFileMapper<String>[] captured = new UnorderedFileMapper[1];
            PredictTask<? extends Example<?, ?>, Ranker<String, String>, String> testSubject = new PredictTask<>(offlineTaskContext) {
                @Override
                protected Map<String, Object> callUnorderedFileMapper(UnorderedFileMapper<String> processor) {
                    captured[0] = processor;
                    return Map.of("lines_written", 1L);
                }
            };

            testSubject.perform();

            assertNotNull(captured[0]);
            assertEquals(11, readPrivateInt(captured[0], "readQueueSize"));
            assertEquals(13, readPrivateInt(captured[0], "writeQueueSize"));
        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Test
    void unorderedPredictUsesMapperDefaultQueueLengthWhenNoQueueSizesAreConfigured() throws Exception {
        Options options = new Options();
        options.parameters = Paths.get(Objects.requireNonNull(this.getClass().getResource("test-algorithm-parameter.zip")).toURI()).toFile();
        options.sourceFiles = ImmutableMap.of("default", ImmutableList.of(Paths.get(Objects.requireNonNull(this.getClass().getResource("multiple")).toURI()).toFile()));
        Path tempDir = Files.createTempDirectory("predict-default-queue-length");
        options.destinationFile = tempDir.resolve("prediction").toFile();
        options.unordered = true;
        options.maxThreads = 31;

        try {
            OfflineTaskContext offlineTaskContext = new OfflineTaskContext(
                    new URLClassLoader(new URL[0], this.getClass().getClassLoader()),
                    new SimpleMeterRegistry(),
                    options,
                    algorithmDefinition()
            );

            final UnorderedFileMapper<String>[] captured = new UnorderedFileMapper[1];
            PredictTask<? extends Example<?, ?>, Ranker<String, String>, String> testSubject = new PredictTask<>(offlineTaskContext) {
                @Override
                protected Map<String, Object> callUnorderedFileMapper(UnorderedFileMapper<String> processor) {
                    captured[0] = processor;
                    return Map.of("lines_written", 1L);
                }
            };

            var result = testSubject.perform();
            assertNotNull(captured[0]);
            assertNull(readPrivateInteger(captured[0], "readQueueSize"));
            assertNull(readPrivateInteger(captured[0], "writeQueueSize"));
            assertEquals(3968, result.get("prediction_effective_read_queue_size"));
            assertEquals(3968, result.get("prediction_effective_write_queue_size"));
        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Test
    void unorderedPredictTreatsZeroMaxThreadsAsAutoAndComputesPositiveDefaultQueueLength() throws Exception {
        Options options = new Options();
        options.parameters = Paths.get(Objects.requireNonNull(this.getClass().getResource("test-algorithm-parameter.zip")).toURI()).toFile();
        options.sourceFiles = ImmutableMap.of("default", ImmutableList.of(Paths.get(Objects.requireNonNull(this.getClass().getResource("multiple")).toURI()).toFile()));
        Path tempDir = Files.createTempDirectory("predict-default-queue-length-zero-max-threads");
        options.destinationFile = tempDir.resolve("prediction").toFile();
        options.unordered = true;
        options.maxThreads = 0;

        try {
            OfflineTaskContext offlineTaskContext = new OfflineTaskContext(
                    new URLClassLoader(new URL[0], this.getClass().getClassLoader()),
                    new SimpleMeterRegistry(),
                    options,
                    algorithmDefinition()
            );

            final UnorderedFileMapper<String>[] captured = new UnorderedFileMapper[1];
            PredictTask<? extends Example<?, ?>, Ranker<String, String>, String> testSubject = new PredictTask<>(offlineTaskContext) {
                @Override
                protected Map<String, Object> callUnorderedFileMapper(UnorderedFileMapper<String> processor) {
                    captured[0] = processor;
                    return Map.of("lines_written", 1L);
                }
            };

            var result = testSubject.perform();
            assertNotNull(captured[0]);
            assertNull(readPrivateInteger(captured[0], "readQueueSize"));
            assertNull(readPrivateInteger(captured[0], "writeQueueSize"));
            assertTrue(((Number) result.get("prediction_effective_computation_threads")).intValue() > 0);
            assertTrue(((Number) result.get("prediction_effective_read_queue_size")).intValue() > 0);
            assertTrue(((Number) result.get("prediction_effective_write_queue_size")).intValue() > 0);
        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Test
    void unorderedPredictAutoWriterShardsFollowEffectiveComputationThreads() throws Exception {
        Options options = new Options();
        options.parameters = Paths.get(Objects.requireNonNull(this.getClass().getResource("test-algorithm-parameter.zip")).toURI()).toFile();
        options.sourceFiles = ImmutableMap.of("default", ImmutableList.of(Paths.get(Objects.requireNonNull(this.getClass().getResource("multiple")).toURI()).toFile()));
        Path tempDir = Files.createTempDirectory("predict-auto-writer-shards");
        options.destinationFile = tempDir.resolve("prediction").toFile();
        options.unordered = true;
        options.maxThreads = 2;

        try {
            OfflineTaskContext offlineTaskContext = new OfflineTaskContext(
                    new URLClassLoader(new URL[0], this.getClass().getClassLoader()),
                    new SimpleMeterRegistry(),
                    options,
                    algorithmDefinition()
            );

            PredictTask<? extends Example<?, ?>, Ranker<String, String>, String> testSubject = new PredictTask<>(offlineTaskContext) {
                @Override
                protected Map<String, Object> callUnorderedFileMapper(UnorderedFileMapper<String> processor) {
                    return Map.of("lines_written", 1L);
                }
            };

            var result = testSubject.perform();
            assertEquals(2, result.get("prediction_effective_computation_threads"));
            assertEquals(1, result.get("prediction_writer_num_shards"));
        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Test
    void unorderedPredictCanOverrideReaderThreadsFromAlgorithmDefinition() throws Exception {
        Options options = new Options();
        options.parameters = Paths.get(Objects.requireNonNull(this.getClass().getResource("test-algorithm-parameter.zip")).toURI()).toFile();
        options.sourceFiles = ImmutableMap.of("default", ImmutableList.of(Paths.get(Objects.requireNonNull(this.getClass().getResource("multiple")).toURI()).toFile()));
        Path tempDir = Files.createTempDirectory("predict-reader-threads");
        options.destinationFile = tempDir.resolve("prediction").toFile();
        options.unordered = true;

        ObjectNode rawAlgorithmDefinition = JsonNodeFactory.instance.objectNode();
        rawAlgorithmDefinition.putObject("hotvect_execution_parameters")
                .putObject("predict")
                .put("reader_threads", 9);

        try {
            OfflineTaskContext offlineTaskContext = new OfflineTaskContext(
                    new URLClassLoader(new URL[0], this.getClass().getClassLoader()),
                    new SimpleMeterRegistry(),
                    options,
                    algorithmDefinition(rawAlgorithmDefinition)
            );

            PredictTask<? extends Example<?, ?>, Ranker<String, String>, String> testSubject = new PredictTask<>(offlineTaskContext) {
                @Override
                protected Map<String, Object> callUnorderedFileMapper(UnorderedFileMapper<String> processor) throws Exception {
                    assertEquals(9, readPrivateInt(processor, "nReaderThreads"));
                    return Map.of("lines_written", 1L);
                }
            };

            var result = testSubject.perform();
            assertEquals(9, result.get("prediction_effective_reader_threads"));
        } finally {
            deleteRecursively(tempDir);
        }
    }



    @Test
    void shouldThrowExceptionWhenNoRowsWritten() throws Exception {
        Options options = new Options();
        options.parameters = Paths.get(Objects.requireNonNull(this.getClass().getResource("test-algorithm-parameter.zip")).toURI()).toFile();
        options.sourceFiles = ImmutableMap.of("default", ImmutableList.of(Paths.get(Objects.requireNonNull(this.getClass().getResource("multiple")).toURI()).toFile()));
        Path tempDir = Files.createTempDirectory("predict-empty-ordered");
        options.destinationFile = tempDir.resolve("prediction").toFile();
        options.ordered = true;

        try {
            OfflineTaskContext offlineTaskContext = new OfflineTaskContext(new URLClassLoader(new URL[0], this.getClass().getClassLoader()), new SimpleMeterRegistry(), options, algorithmDefinition());

            PredictTask<? extends Example<?, ?>, Ranker<String, String>, String> testSubject = new PredictTask<>(offlineTaskContext) {
                @Override
                protected Map<String, Object> callOrderedFileMapper(OrderedFileMapper processor) {
                    return Map.of("total_record_count", 0L);
                }
            };

            Exception exception = assertThrows(Exception.class, testSubject::perform);
            assertEquals("No rows have been written.", exception.getMessage());
        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Test
    void shouldThrowExceptionWhenNoRowsWrittenInUnorderedMode() throws Exception {
        Options options = new Options();
        options.parameters = Paths.get(Objects.requireNonNull(this.getClass().getResource("test-algorithm-parameter.zip")).toURI()).toFile();
        options.sourceFiles = ImmutableMap.of("default", ImmutableList.of(Paths.get(Objects.requireNonNull(this.getClass().getResource("multiple")).toURI()).toFile()));
        Path tempDir = Files.createTempDirectory("predict-empty-unordered");
        options.destinationFile = tempDir.resolve("prediction").toFile();
        options.unordered = true;

        try {
            OfflineTaskContext offlineTaskContext = new OfflineTaskContext(new URLClassLoader(new URL[0], this.getClass().getClassLoader()), new SimpleMeterRegistry(), options, algorithmDefinition());

            PredictTask<? extends Example<?, ?>, Ranker<String, String>, String> testSubject = new PredictTask<>(offlineTaskContext) {
                @Override
                protected Map<String, Object> callUnorderedFileMapper(UnorderedFileMapper<String> processor) {
                    return Map.of("lines_written", 0L);
                }
            };

            Exception exception = assertThrows(Exception.class, testSubject::perform);
            assertEquals("No rows have been written.", exception.getMessage());
        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Test
    void shouldRejectMultipleWriterShardsForOrderedPredict() throws Exception {
        Options options = new Options();
        options.parameters = Paths.get(Objects.requireNonNull(this.getClass().getResource("test-algorithm-parameter.zip")).toURI()).toFile();
        options.sourceFiles = ImmutableMap.of("default", ImmutableList.of(Paths.get(Objects.requireNonNull(this.getClass().getResource("multiple")).toURI()).toFile()));
        Path tempDir = Files.createTempDirectory("predict-ordered-multiple-shards");
        options.destinationFile = tempDir.resolve("prediction").toFile();
        options.ordered = true;
        options.writerNumShards = 2;

        try {
            OfflineTaskContext offlineTaskContext = new OfflineTaskContext(new URLClassLoader(new URL[0], this.getClass().getClassLoader()), new SimpleMeterRegistry(), options, algorithmDefinition());

            PredictTask<? extends Example<?, ?>, Ranker<String, String>, String> testSubject = new PredictTask<>(offlineTaskContext);

            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, testSubject::perform);
            assertEquals("writer-num-shards > 1 may only be used with unordered predict output.", exception.getMessage());
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private static void deleteRecursively(Path path) throws Exception {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            for (Path current : walk.sorted((a, b) -> b.compareTo(a)).toList()) {
                Files.deleteIfExists(current);
            }
        }
    }

    private static int readPrivateInt(Object target, String fieldName) throws Exception {
        Integer value = readPrivateInteger(target, fieldName);
        return value.intValue();
    }

    private static Integer readPrivateInteger(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return (Integer) field.get(target);
    }
}
