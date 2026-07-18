package com.hotvect.offlineutils.commandline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.algodefinition.AlgorithmId;
import com.hotvect.api.algodefinition.common.RewardFunction;
import com.hotvect.api.algodefinition.common.RewardFunctionFactory;
import com.hotvect.api.algodefinition.ranking.RankingExampleDecoderFactory;
import com.hotvect.api.algodefinition.ranking.RankingTransformer;
import com.hotvect.api.algodefinition.ranking.RankingTransformerFactory;
import com.hotvect.api.codec.ranking.RankingExampleDecoder;
import com.hotvect.api.data.Namespace;
import com.hotvect.api.data.OfflineRequest;
import com.hotvect.api.data.common.Example;
import com.hotvect.api.data.common.NamespacedRecord;
import com.hotvect.api.data.common.NamespacedRecordImpl;
import com.hotvect.api.data.ranking.RankingDecision;
import com.hotvect.api.data.ranking.RankingExample;
import com.hotvect.api.data.ranking.RankingOutcome;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.onlineutils.concurrency.fileutils.OrderedFileMapper;
import com.hotvect.onlineutils.concurrency.fileutils.UnorderedFileMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
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
import java.util.SortedSet;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditTaskTest {
    private record TestNamespace(String name) implements Namespace {
        @Override
        public String toString() {
            return name;
        }
    }

    private static final Namespace AUDIT_FEATURE = new TestNamespace("feature_1");

    public static class ExampleDecoderFactory implements RankingExampleDecoderFactory<String, String, Double> {
        @Override
        public RankingExampleDecoder<String, String, Double> apply(Optional<JsonNode> hyperparameter) {
            return toDecode -> ImmutableList.of(
                    new RankingExample<>(
                            "example",
                            RankingTestData.request("example", "shared", "action1", "action2"),
                            ImmutableList.of(
                                    new RankingOutcome<>(
                                            RankingDecision.builder("action1", "action1").build(),
                                            1.0
                                    ),
                                    new RankingOutcome<>(
                                            RankingDecision.builder("action2", "action2").build(),
                                            2.0
                                    )
                            )
                    )
            );
        }
    }

    public static class TestTransformerFactory implements RankingTransformerFactory<String, String> {
        @Override
        public RankingTransformer<String, String> apply(Optional<JsonNode> hyperparameter, Map<String, InputStream> parameter) {
            return new RankingTransformer<>() {
                @Override
                public List<NamespacedRecord<Namespace, Object>> apply(RankingRequest<String, String> rankingRequest) {
                    NamespacedRecordImpl<Namespace, Object> first = new NamespacedRecordImpl<>();
                    first.put(AUDIT_FEATURE, "value-1");
                    NamespacedRecordImpl<Namespace, Object> second = new NamespacedRecordImpl<>();
                    second.put(AUDIT_FEATURE, "value-2");
                    return ImmutableList.of(first, second);
                }

                @Override
                public SortedSet<? extends Namespace> getUsedFeatures() {
                    TreeSet<Namespace> namespaces = new TreeSet<>(Namespace.alphabetical());
                    namespaces.add(AUDIT_FEATURE);
                    return namespaces;
                }
            };
        }
    }

    public static class TestRewardFunctionFactory implements RewardFunctionFactory<Double> {
        @Override
        public RewardFunction<Double> get() {
            return value -> value;
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
                nestedClassPrefix + TestTransformerFactory.class.getSimpleName(),
                null,
                nestedClassPrefix + TestRewardFunctionFactory.class.getSimpleName(),
                null,
                null,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()
        );
    }

    @Test
    void defaultsToOrderedAuditWhenFlagsAndAlgorithmDefinitionDoNotSpecifyOrdering() throws Exception {
        Options options = new Options();
        options.parameters = Paths.get(Objects.requireNonNull(this.getClass().getResource("test-algorithm-parameter.zip")).toURI()).toFile();
        options.sourceFiles = ImmutableMap.of("default", ImmutableList.of(Paths.get(Objects.requireNonNull(this.getClass().getResource("multiple")).toURI()).toFile()));
        Path tempDir = Files.createTempDirectory("audit-default-ordered");
        options.destinationFile = tempDir.resolve("audit").toFile();

        try {
            OfflineTaskContext offlineTaskContext = new OfflineTaskContext(
                    new URLClassLoader(new URL[0], this.getClass().getClassLoader()),
                    new SimpleMeterRegistry(),
                    options,
                    algorithmDefinition()
            );

            AuditTask<? extends Example<?, ?>, ?> testSubject = new AuditTask<>(offlineTaskContext) {
                @Override
                protected Map<String, Object> callOrderedFileMapper(OrderedFileMapper processor) {
                    return Map.of("total_record_count", 1L);
                }

                @Override
                protected Map<String, Object> callUnorderedFileMapper(UnorderedFileMapper<String> mapper) {
                    throw new AssertionError("Unordered path should not be used by default");
                }
            };

            var result = testSubject.perform();
            assertEquals("ordered", result.get("audit_output_ordering"));
            assertEquals(1, result.get("audit_writer_num_shards"));
        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Test
    void orderedAuditWritesSinglePartFile() throws Exception {
        Options options = new Options();
        options.parameters = Paths.get(Objects.requireNonNull(this.getClass().getResource("test-algorithm-parameter.zip")).toURI()).toFile();
        options.sourceFiles = ImmutableMap.of("default", ImmutableList.of(Paths.get(Objects.requireNonNull(this.getClass().getResource("multiple")).toURI()).toFile()));
        Path tempDir = Files.createTempDirectory("audit-ordered-layout");
        Path destinationPath = tempDir.resolve("audit");
        options.destinationFile = destinationPath.toFile();
        options.ordered = true;

        try {
            OfflineTaskContext offlineTaskContext = new OfflineTaskContext(
                    new URLClassLoader(new URL[0], this.getClass().getClassLoader()),
                    new SimpleMeterRegistry(),
                    options,
                    algorithmDefinition()
            );

            AuditTask<? extends Example<?, ?>, ?> testSubject = new AuditTask<>(offlineTaskContext);
            var result = testSubject.perform();

            List<String> outputFiles;
            try (var partFiles = Files.list(destinationPath)) {
                outputFiles = partFiles
                        .map(path -> path.getFileName().toString())
                        .sorted()
                        .toList();
            }

            assertEquals(List.of("part-00000.jsonl"), outputFiles);
            assertEquals("ordered", result.get("audit_output_ordering"));
            assertEquals(1, result.get("audit_writer_num_shards"));
            assertTrue(!Files.exists(destinationPath.resolve("shard_0.jsonl")));
        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Test
    void unorderedAuditShouldWriteShardedOutputDirectory() throws Exception {
        Options options = new Options();
        options.parameters = Paths.get(Objects.requireNonNull(this.getClass().getResource("test-algorithm-parameter.zip")).toURI()).toFile();
        options.sourceFiles = ImmutableMap.of("default", ImmutableList.of(Paths.get(Objects.requireNonNull(this.getClass().getResource("multiple")).toURI()).toFile()));
        Path tempDir = Files.createTempDirectory("audit-unordered");
        Path destinationPath = tempDir.resolve("audit");
        options.destinationFile = destinationPath.toFile();
        options.unordered = true;
        options.writerNumShards = 2;

        try {
            OfflineTaskContext offlineTaskContext = new OfflineTaskContext(
                    new URLClassLoader(new URL[0], this.getClass().getClassLoader()),
                    new SimpleMeterRegistry(),
                    options,
                    algorithmDefinition()
            );

            AuditTask<? extends Example<?, ?>, ?> testSubject = new AuditTask<>(offlineTaskContext);
            var result = testSubject.perform();

            List<String> outputFiles;
            List<String> lines;
            try (var partFiles = Files.list(destinationPath)) {
                outputFiles = partFiles
                        .map(path -> path.getFileName().toString())
                        .sorted()
                        .toList();
            }
            try (var partFiles = Files.list(destinationPath)) {
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
            assertEquals("unordered", result.get("audit_output_ordering"));
            assertEquals(2, result.get("audit_writer_num_shards"));
            assertTrue(destinationPath.toFile().isDirectory());
            assertEquals(result.get("total_record_count"), (long) lines.size());
            assertEquals(result.get("lines_written"), (long) lines.size());
        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Test
    void algorithmDefinitionCanEnableUnorderedAudit() throws Exception {
        Options options = new Options();
        options.parameters = Paths.get(Objects.requireNonNull(this.getClass().getResource("test-algorithm-parameter.zip")).toURI()).toFile();
        options.sourceFiles = ImmutableMap.of("default", ImmutableList.of(Paths.get(Objects.requireNonNull(this.getClass().getResource("multiple")).toURI()).toFile()));
        Path tempDir = Files.createTempDirectory("audit-algodef-unordered");
        options.destinationFile = tempDir.resolve("audit").toFile();

        ObjectNode rawAlgorithmDefinition = JsonNodeFactory.instance.objectNode();
        rawAlgorithmDefinition.putObject("hotvect_execution_parameters")
                .putObject("audit")
                .put("ordered", false)
                .put("writer_num_shards", 2);

        OfflineTaskContext offlineTaskContext = new OfflineTaskContext(
                new URLClassLoader(new URL[0], this.getClass().getClassLoader()),
                new SimpleMeterRegistry(),
                options,
                algorithmDefinition(rawAlgorithmDefinition)
        );

        AuditTask<? extends Example<?, ?>, ?> testSubject = new AuditTask<>(offlineTaskContext) {
            @Override
            protected Map<String, Object> callOrderedFileMapper(OrderedFileMapper processor) {
                throw new AssertionError("Ordered path should not be used");
            }

            @Override
            protected Map<String, Object> callUnorderedFileMapper(UnorderedFileMapper<String> mapper) {
                return Map.of("lines_written", 1L);
            }
        };

        var result = testSubject.perform();
        assertEquals("unordered", result.get("audit_output_ordering"));
        assertEquals(2, result.get("audit_writer_num_shards"));
        deleteRecursively(tempDir);
    }

    @Test
    void forwardsLegacyQueueLengthToUnorderedMapper() throws Exception {
        Options options = new Options();
        options.parameters = Paths.get(Objects.requireNonNull(this.getClass().getResource("test-algorithm-parameter.zip")).toURI()).toFile();
        options.sourceFiles = ImmutableMap.of("default", ImmutableList.of(Paths.get(Objects.requireNonNull(this.getClass().getResource("multiple")).toURI()).toFile()));
        Path tempDir = Files.createTempDirectory("audit-unordered-legacy-queue");
        options.destinationFile = tempDir.resolve("audit").toFile();
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
            AuditTask<? extends Example<?, ?>, ?> testSubject = new AuditTask<>(offlineTaskContext) {
                @Override
                protected Map<String, Object> callUnorderedFileMapper(UnorderedFileMapper<String> mapper) {
                    captured[0] = mapper;
                    return Map.of("lines_written", 1L);
                }
            };

            testSubject.perform();

            assertNotNull(captured[0]);
            assertEquals(7, readIntField(captured[0], "readQueueSize"));
            assertEquals(7, readIntField(captured[0], "writeQueueSize"));
        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Test
    void forwardsSplitQueueLengthsToUnorderedMapper() throws Exception {
        Options options = new Options();
        options.parameters = Paths.get(Objects.requireNonNull(this.getClass().getResource("test-algorithm-parameter.zip")).toURI()).toFile();
        options.sourceFiles = ImmutableMap.of("default", ImmutableList.of(Paths.get(Objects.requireNonNull(this.getClass().getResource("multiple")).toURI()).toFile()));
        Path tempDir = Files.createTempDirectory("audit-unordered-split-queue");
        options.destinationFile = tempDir.resolve("audit").toFile();
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
            AuditTask<? extends Example<?, ?>, ?> testSubject = new AuditTask<>(offlineTaskContext) {
                @Override
                protected Map<String, Object> callUnorderedFileMapper(UnorderedFileMapper<String> mapper) {
                    captured[0] = mapper;
                    return Map.of("lines_written", 1L);
                }
            };

            testSubject.perform();

            assertNotNull(captured[0]);
            assertEquals(11, readIntField(captured[0], "readQueueSize"));
            assertEquals(13, readIntField(captured[0], "writeQueueSize"));
        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Test
    void shouldRejectConflictingAuditOrderingFlags() throws Exception {
        Options options = new Options();
        options.parameters = Paths.get(Objects.requireNonNull(this.getClass().getResource("test-algorithm-parameter.zip")).toURI()).toFile();
        options.sourceFiles = ImmutableMap.of("default", ImmutableList.of(Paths.get(Objects.requireNonNull(this.getClass().getResource("multiple")).toURI()).toFile()));
        Path tempDir = Files.createTempDirectory("audit-conflicting-ordering");
        options.destinationFile = tempDir.resolve("audit").toFile();
        options.ordered = true;
        options.unordered = true;

        try {
            OfflineTaskContext offlineTaskContext = new OfflineTaskContext(
                    new URLClassLoader(new URL[0], this.getClass().getClassLoader()),
                    new SimpleMeterRegistry(),
                    options,
                    algorithmDefinition()
            );
            AuditTask<? extends Example<?, ?>, ?> testSubject = new AuditTask<>(offlineTaskContext);

            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, testSubject::perform);
            assertEquals("At most one of ordered or unordered audit modes may be enabled.", exception.getMessage());
        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Test
    void shouldRejectMultipleWriterShardsForOrderedAudit() throws Exception {
        Options options = new Options();
        options.parameters = Paths.get(Objects.requireNonNull(this.getClass().getResource("test-algorithm-parameter.zip")).toURI()).toFile();
        options.sourceFiles = ImmutableMap.of("default", ImmutableList.of(Paths.get(Objects.requireNonNull(this.getClass().getResource("multiple")).toURI()).toFile()));
        Path tempDir = Files.createTempDirectory("audit-ordered-multiple-shards");
        options.destinationFile = tempDir.resolve("audit").toFile();
        options.writerNumShards = 2;

        try {
            OfflineTaskContext offlineTaskContext = new OfflineTaskContext(
                    new URLClassLoader(new URL[0], this.getClass().getClassLoader()),
                    new SimpleMeterRegistry(),
                    options,
                    algorithmDefinition()
            );
            AuditTask<? extends Example<?, ?>, ?> testSubject = new AuditTask<>(offlineTaskContext);

            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, testSubject::perform);
            assertEquals("writer-num-shards > 1 may only be used with unordered audit output.", exception.getMessage());
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

    private static int readIntField(Object target, String fieldName) throws Exception {
        Field field = UnorderedFileMapper.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        Object value = field.get(target);
        return ((Integer) value).intValue();
    }
}
