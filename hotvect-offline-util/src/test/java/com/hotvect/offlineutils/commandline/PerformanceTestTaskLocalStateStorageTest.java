package com.hotvect.offlineutils.commandline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableMap;
import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.algodefinition.AlgorithmDependencyDeclaration;
import com.hotvect.api.algodefinition.AlgorithmId;
import com.hotvect.api.algodefinition.AlgorithmDependencies;
import com.hotvect.api.algodefinition.common.CompositeAlgorithmFactory;
import com.hotvect.api.algodefinition.ranking.RankerFactory;
import com.hotvect.api.algodefinition.ranking.RankingTransformer;
import com.hotvect.api.algodefinition.state.NonCompositeStateFactory;
import com.hotvect.api.algodefinition.storage.LocalStateStorage;
import com.hotvect.api.algorithms.Algorithm;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.data.ranking.RankingDecision;
import com.hotvect.api.data.ranking.RankingExample;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.ranking.RankingResponse;
import com.hotvect.api.execution.ExecutionContext;
import com.hotvect.api.execution.InputSemantic;
import com.hotvect.api.execution.WorkloadMode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PerformanceTestTaskLocalStateStorageTest {
    private static final String STORAGE_STATE_NAME = "performance-test-storage-state";
    private static final AtomicReference<Path> ALLOCATED_STATE_DIRECTORY = new AtomicReference<>();
    private static final AtomicBoolean STORAGE_STATE_CLOSED = new AtomicBoolean();
    private static final AtomicBoolean LEGACY_FACTORY_RECEIVED_STORAGE = new AtomicBoolean();

    @TempDir
    Path tempDir;

    @BeforeEach
    void resetFactoryObservations() {
        ALLOCATED_STATE_DIRECTORY.set(null);
        STORAGE_STATE_CLOSED.set(false);
        LEGACY_FACTORY_RECEIVED_STORAGE.set(false);
    }

    @Test
    void realtimePerformanceTestLoadsNestedStateAndCleansPrivateDirectories() throws Exception {
        Path metadataDirectory = tempDir.resolve("metadata");
        Map<String, Object> result = task(true, metadataDirectory).perform();

        Path allocatedStateDirectory = ALLOCATED_STATE_DIRECTORY.get();
        assertNotNull(allocatedStateDirectory);
        assertTrue(allocatedStateDirectory.getFileName().toString()
                .startsWith("performance-test-storage-state-1.0.0-state-"));
        assertEquals(
                Path.of(System.getProperty("java.io.tmpdir"), "algorithm-state").toAbsolutePath(),
                allocatedStateDirectory.getParent());
        assertTrue(STORAGE_STATE_CLOSED.get());
        assertFalse(Files.exists(allocatedStateDirectory));
        assertTrue(isEmpty(metadataDirectory));
        assertEquals("realtime", result.get("workload_mode"));
    }

    @Test
    void realtimePerformanceTestMakesStorageAvailableWithoutAllocatingIt() throws Exception {
        Path metadataDirectory = tempDir.resolve("legacy-metadata");
        Map<String, Object> result = task(false, metadataDirectory).perform();

        assertTrue(LEGACY_FACTORY_RECEIVED_STORAGE.get());
        assertNull(ALLOCATED_STATE_DIRECTORY.get());
        assertTrue(isEmpty(metadataDirectory));
        assertEquals("realtime", result.get("workload_mode"));
    }

    private PerformanceTestTask<
            RankingExample<ParityFixtureAlgorithm.SharedContext, ParityFixtureAlgorithm.ActionContext, Double>,
            Ranker<ParityFixtureAlgorithm.SharedContext, ParityFixtureAlgorithm.ActionContext>> task(
                    boolean usesNestedState,
                    Path metadataDirectory) throws Exception {
        Files.createDirectories(metadataDirectory);
        Path source = tempDir.resolve(usesNestedState ? "local-state.jsonl" : "legacy.jsonl");
        Files.writeString(source, "example|1.0\n");

        Options options = OfflineTaskTestOptions.direct();
        options.metadataLocation = metadataDirectory.toFile();
        options.sourceFiles = Map.of("default", List.of(source.toFile()));
        options.samples = 1;
        options.samplePoolSize = 1;
        options.targetThroughputFraction = 0.0;
        options.maxThreads = 1;
        options.batchSize = 1;

        OfflineTaskContext context = new OfflineTaskContext(
                new URLClassLoader(new URL[0], getClass().getClassLoader()),
                new SimpleMeterRegistry(),
                options,
                algorithmDefinition(usesNestedState));
        return new PerformanceTestTask<>(context);
    }

    private static AlgorithmDefinition algorithmDefinition(boolean usesNestedState) {
        ObjectNode rawDefinition = JsonNodeFactory.instance.objectNode();
        Map<String, AlgorithmDependencyDeclaration> dependencyDeclarations = usesNestedState
                ? ImmutableMap.of(
                        STORAGE_STATE_NAME,
                        new AlgorithmDependencyDeclaration.Private(
                                STORAGE_STATE_NAME,
                                Optional.empty()))
                : ImmutableMap.of();
        return new AlgorithmDefinition(
                rawDefinition,
                new AlgorithmId(usesNestedState ? "local-state-ranker" : "legacy-ranker", "1.0.0"),
                dependencyDeclarations,
                null,
                ParityFixtureAlgorithm.ExampleDecoderFactory.class.getName(),
                usesNestedState ? null : ParityFixtureAlgorithm.TransformerFactory.class.getName(),
                null,
                ParityFixtureAlgorithm.TestRewardFunctionFactory.class.getName(),
                null,
                (usesNestedState ? StorageDependentRankerFactory.class : LegacyRankerFactory.class).getName(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static boolean isEmpty(Path directory) throws IOException {
        try (var entries = Files.list(directory)) {
            return entries.findAny().isEmpty();
        }
    }

    public static final class StorageDependentRankerFactory implements CompositeAlgorithmFactory<Ranker<
            ParityFixtureAlgorithm.SharedContext, ParityFixtureAlgorithm.ActionContext>> {
        @Override
        public Ranker<ParityFixtureAlgorithm.SharedContext, ParityFixtureAlgorithm.ActionContext> create(
                ExecutionContext executionContext,
                Optional<LocalStateStorage> localStateStorage,
                Optional<JsonNode> hyperparameters,
                Map<String, InputStream> parameters,
                AlgorithmDependencies dependencies) {
            assertEquals(ExecutionContext.of(WorkloadMode.REALTIME, InputSemantic.OFFLINE), executionContext);
            assertTrue(localStateStorage.isPresent());
            Algorithm state = dependencies.only(STORAGE_STATE_NAME);
            assertNotNull(state);
            return new TestRanker();
        }
    }

    public static final class StorageRequiredStateFactory implements NonCompositeStateFactory<CloseableTestState> {
        @Override
        public CloseableTestState apply(
                Map<String, InputStream> parameters,
                Optional<JsonNode> hyperparameter) {
            throw new AssertionError("Runtime-aware state factory overload should be used");
        }

        @Override
        public CloseableTestState create(
                ExecutionContext executionContext,
                Optional<LocalStateStorage> localStateStorage,
                Map<String, InputStream> parameters,
                Optional<JsonNode> hyperparameter) {
            assertEquals(ExecutionContext.of(WorkloadMode.REALTIME, InputSemantic.OFFLINE), executionContext);
            Path stateDirectory = localStateStorage
                    .orElseThrow(() -> new IllegalStateException(
                            "Realtime LMDB config feature state requires local state storage"))
                    .allocateDirectory();
            try {
                Files.writeString(stateDirectory.resolve("state.marker"), "loaded");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            ALLOCATED_STATE_DIRECTORY.set(stateDirectory);
            return new CloseableTestState(stateDirectory);
        }
    }

    public static final class LegacyRankerFactory implements RankerFactory<
            RankingTransformer<ParityFixtureAlgorithm.SharedContext, ParityFixtureAlgorithm.ActionContext>,
            ParityFixtureAlgorithm.SharedContext,
            ParityFixtureAlgorithm.ActionContext> {
        @Override
        public Ranker<ParityFixtureAlgorithm.SharedContext, ParityFixtureAlgorithm.ActionContext> apply(
                RankingTransformer<ParityFixtureAlgorithm.SharedContext, ParityFixtureAlgorithm.ActionContext> dependency,
                Map<String, InputStream> parameters,
                Optional<JsonNode> hyperparameter) {
            return new TestRanker();
        }

        @Override
        public Ranker<ParityFixtureAlgorithm.SharedContext, ParityFixtureAlgorithm.ActionContext> create(
                ExecutionContext executionContext,
                Optional<LocalStateStorage> localStateStorage,
                RankingTransformer<ParityFixtureAlgorithm.SharedContext, ParityFixtureAlgorithm.ActionContext> dependency,
                Map<String, InputStream> parameters,
                Optional<JsonNode> hyperparameter) {
            LEGACY_FACTORY_RECEIVED_STORAGE.set(localStateStorage.isPresent());
            return apply(dependency, parameters, hyperparameter);
        }
    }

    private static class TestRanker implements Ranker<
            ParityFixtureAlgorithm.SharedContext,
            ParityFixtureAlgorithm.ActionContext> {
        @Override
        public RankingResponse<ParityFixtureAlgorithm.ActionContext> rank(
                RankingRequest<
                        ParityFixtureAlgorithm.SharedContext,
                        ParityFixtureAlgorithm.ActionContext> request) {
            List<RankingDecision<ParityFixtureAlgorithm.ActionContext>> decisions = request.actions().stream()
                    .map(action -> RankingDecision.builder(action.actionId(), 0, action.action()).build())
                    .toList();
            return RankingResponse.newResponse(decisions);
        }
    }

    public static final class CloseableTestState implements Algorithm {
        private final Path stateDirectory;

        private CloseableTestState(Path stateDirectory) {
            this.stateDirectory = stateDirectory;
        }

        @Override
        public void close() throws IOException {
            Files.delete(stateDirectory.resolve("state.marker"));
            Files.delete(stateDirectory);
            STORAGE_STATE_CLOSED.set(true);
        }
    }
}
