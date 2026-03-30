package com.hotvect.offlineutils.commandline;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.algodefinition.AlgorithmId;
import com.hotvect.api.algodefinition.common.ExampleDecoderFactory;
import com.hotvect.api.algodefinition.common.ExampleEncoderFactory;
import com.hotvect.api.algodefinition.common.RewardFunction;
import com.hotvect.api.algodefinition.common.RewardFunctionFactory;
import com.hotvect.api.algodefinition.ranking.RankingExampleDecoderFactory;
import com.hotvect.api.algodefinition.ranking.RankingExampleEncoderFactory;
import com.hotvect.api.algodefinition.ranking.RankingTransformer;
import com.hotvect.api.algodefinition.ranking.RankingTransformerFactory;
import com.hotvect.api.codec.common.ExampleDecoder;
import com.hotvect.api.codec.common.ExampleEncoder;
import com.hotvect.api.codec.ranking.RankingExampleEncoder;
import com.hotvect.api.data.common.Example;
import com.hotvect.api.data.ranking.RankingExample;
import com.hotvect.api.data.ranking.RankingOutcome;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.onlineutils.concurrency.fileutils.UnorderedFileMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EncodeTaskTest {
    public static class NullExtensionDecoderFactory implements ExampleDecoderFactory<RankingExample<String, String, String>> {
        @Override
        public ExampleDecoder<RankingExample<String, String, String>> apply(Optional<JsonNode> hyperparameter) {
            return ignored -> List.of();
        }
    }

    public static class NullExtensionDependencyFactory implements BiFunction<Optional<JsonNode>, Map<String, InputStream>, Object> {
        @Override
        public Object apply(Optional<JsonNode> hyperparameter, Map<String, InputStream> parameters) {
            return new Object();
        }
    }

    public static class NullExtensionEncoderFactory implements ExampleEncoderFactory<RankingExample<String, String, String>, Object, String> {
        @Override
        public ExampleEncoder<RankingExample<String, String, String>> apply(Object dependency, RewardFunction<String> rewardFunction) {
            return new ExampleEncoder<>() {
                @Override
                public ByteBuffer apply(RankingExample<String, String, String> rankingExample) {
                    return ByteBuffer.allocate(0);
                }

                @Override
                public String encodedFileExtension() {
                    return null;
                }
            };
        }
    }

    public static class QueueLengthDecoderFactory implements RankingExampleDecoderFactory<String, String, String> {
        @Override
        public com.hotvect.api.codec.ranking.RankingExampleDecoder<String, String, String> apply(Optional<JsonNode> hyperparameter) {
            return ignored -> ImmutableList.of(
                    new RankingExample<>(
                            "example",
                            new RankingRequest<>(
                                    "example",
                                    "shared",
                                    ImmutableList.of("action1", "action2")
                            ),
                            ImmutableList.of(
                                    new RankingOutcome<>(
                                            com.hotvect.api.data.ranking.RankingDecision.builder(0, "action1").build(),
                                            "outcome"
                                    )
                            )
                    )
            );
        }
    }

    public static class QueueLengthTransformerFactory implements RankingTransformerFactory<String, String> {
        @Override
        public RankingTransformer<String, String> apply(Optional<JsonNode> hyperparameter, Map<String, InputStream> parameter) {
            return null;
        }
    }

    public static class QueueLengthEncoderFactory implements RankingExampleEncoderFactory<RankingTransformer<String, String>, String, String, String> {
        @Override
        public RankingExampleEncoder<String, String, String> apply(RankingTransformer<String, String> dependency, RewardFunction<String> rewardFunction) {
            return new RankingExampleEncoder<>() {
                @Override
                public ByteBuffer apply(RankingExample<String, String, String> example) {
                    return ByteBuffer.wrap("encoded\n".getBytes(StandardCharsets.UTF_8));
                }

                @Override
                public String encodedFileExtension() {
                    return ".txt";
                }
            };
        }
    }

    public static class TestRewardFunctionFactory implements RewardFunctionFactory<String> {
        @Override
        public RewardFunction<String> get() {
            return ignored -> 1.0;
        }
    }

    private static AlgorithmDefinition nullExtensionAlgorithmDefinition() {
        String nestedClassPrefix = EncodeTaskTest.class.getCanonicalName() + "$";
        return new AlgorithmDefinition(
                null,
                new AlgorithmId("test-algorithm", "1.2.3"),
                ImmutableMap.of(),
                ImmutableMap.of(),
                null,
                nestedClassPrefix + NullExtensionDecoderFactory.class.getSimpleName(),
                nestedClassPrefix + NullExtensionDependencyFactory.class.getSimpleName(),
                null,
                nestedClassPrefix + TestRewardFunctionFactory.class.getSimpleName(),
                nestedClassPrefix + NullExtensionEncoderFactory.class.getSimpleName(),
                null,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()
        );
    }

    private static AlgorithmDefinition queueLengthAlgorithmDefinition() {
        String nestedClassPrefix = EncodeTaskTest.class.getCanonicalName() + "$";
        return new AlgorithmDefinition(
                null,
                new AlgorithmId("test-algorithm", "1.2.3"),
                ImmutableMap.of(),
                ImmutableMap.of(),
                null,
                nestedClassPrefix + QueueLengthDecoderFactory.class.getSimpleName(),
                nestedClassPrefix + QueueLengthTransformerFactory.class.getSimpleName(),
                null,
                nestedClassPrefix + TestRewardFunctionFactory.class.getSimpleName(),
                nestedClassPrefix + QueueLengthEncoderFactory.class.getSimpleName(),
                null,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()
        );
    }

    @Test
    void shouldFailWhenEncoderReturnsNullExtension() throws Exception {
        Options options = new Options();

        try (URLClassLoader classLoader = new URLClassLoader(new URL[0], this.getClass().getClassLoader())) {
            OfflineTaskContext offlineTaskContext = new OfflineTaskContext(
                    classLoader,
                    new SimpleMeterRegistry(),
                    options,
                    nullExtensionAlgorithmDefinition()
            );

            EncodeTask<RankingExample<String, String, String>> testSubject = new EncodeTask<>(offlineTaskContext);

            IllegalStateException exception = assertThrows(IllegalStateException.class, testSubject::perform);
            assertTrue(exception.getMessage().contains("returned null from encodedFileExtension()"));
        }
    }

    @Test
    void forwardsQueueLengthToUnorderedMapper() throws Exception {
        Options options = new Options();
        options.parameters = Paths.get(Objects.requireNonNull(this.getClass().getResource("test-algorithm-parameter.zip")).toURI()).toFile();
        options.sourceFiles = ImmutableMap.of(
                "default",
                ImmutableList.of(Paths.get(Objects.requireNonNull(this.getClass().getResource("multiple")).toURI()).toFile())
        );
        options.queueLength = 7;
        options.maxThreads = 3;
        options.batchSize = 5;

        File tempDir = Files.createTempDirectory("encode-task-test").toFile();
        options.destinationFile = tempDir;

        try (URLClassLoader classLoader = new URLClassLoader(new URL[0], this.getClass().getClassLoader())) {
            OfflineTaskContext offlineTaskContext = new OfflineTaskContext(
                    classLoader,
                    new SimpleMeterRegistry(),
                    options,
                    queueLengthAlgorithmDefinition()
            );

            final UnorderedFileMapper<String>[] captured = new UnorderedFileMapper[1];
            EncodeTask<? extends Example<?, ?>> testSubject = new EncodeTask<>(offlineTaskContext) {
                @Override
                protected Map<String, Object> callUnorderedFileMapper(UnorderedFileMapper<String> mapper) {
                    captured[0] = mapper;
                    return new HashMap<>(Map.of("lines_written", 1L));
                }
            };

            testSubject.perform();

            assertNotNull(captured[0]);
            assertEquals(7, readIntField(captured[0], "readQueueSize"));
            assertEquals(7, readIntField(captured[0], "writeQueueSize"));
        } finally {
            tempDir.delete();
        }
    }

    @Test
    void shouldAllowNullQueueLengthForUnorderedMapper() throws Exception {
        Options options = new Options();
        options.parameters = Paths.get(Objects.requireNonNull(this.getClass().getResource("test-algorithm-parameter.zip")).toURI()).toFile();
        options.sourceFiles = ImmutableMap.of(
                "default",
                ImmutableList.of(Paths.get(Objects.requireNonNull(this.getClass().getResource("multiple")).toURI()).toFile())
        );
        options.maxThreads = 3;
        options.batchSize = 5;

        File tempDir = Files.createTempDirectory("encode-task-test-null-queue").toFile();
        options.destinationFile = tempDir;

        try (URLClassLoader classLoader = new URLClassLoader(new URL[0], this.getClass().getClassLoader())) {
            OfflineTaskContext offlineTaskContext = new OfflineTaskContext(
                    classLoader,
                    new SimpleMeterRegistry(),
                    options,
                    queueLengthAlgorithmDefinition()
            );

            final UnorderedFileMapper<String>[] captured = new UnorderedFileMapper[1];
            EncodeTask<? extends Example<?, ?>> testSubject = new EncodeTask<>(offlineTaskContext) {
                @Override
                protected Map<String, Object> callUnorderedFileMapper(UnorderedFileMapper<String> mapper) {
                    captured[0] = mapper;
                    return new HashMap<>(Map.of("lines_written", 1L));
                }
            };

            testSubject.perform();

            assertNotNull(captured[0]);
            assertNull(readIntegerField(captured[0], "readQueueSize"));
            assertNull(readIntegerField(captured[0], "writeQueueSize"));
        } finally {
            tempDir.delete();
        }
    }

    private static int readIntField(Object target, String fieldName) throws Exception {
        Field field = UnorderedFileMapper.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        Object value = field.get(target);
        return ((Integer) value).intValue();
    }

    private static Integer readIntegerField(Object target, String fieldName) throws Exception {
        Field field = UnorderedFileMapper.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (Integer) field.get(target);
    }
}
