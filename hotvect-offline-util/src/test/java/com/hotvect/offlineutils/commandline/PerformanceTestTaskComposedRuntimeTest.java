package com.hotvect.offlineutils.commandline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.algodefinition.common.SimpleAlgorithmFactory;
import com.hotvect.api.algodefinition.ranking.RankingExampleDecoderFactory;
import com.hotvect.api.algodefinition.ranking.SimpleRankerFactory;
import com.hotvect.api.algodefinition.storage.LocalStateStorage;
import com.hotvect.api.algorithms.BulkScorer;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.codec.ranking.RankingExampleDecoder;
import com.hotvect.api.data.FeatureStoreResponseContainer;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.ranking.RankingResponse;
import com.hotvect.api.data.scoring.BulkScoreResponse;
import com.hotvect.api.data.scoring.ScoringDecision;
import com.hotvect.api.execution.ExecutionContext;
import com.hotvect.api.execution.InputSemantic;
import com.hotvect.api.execution.WorkloadMode;
import com.hotvect.onlineutils.experimentmanagement.ExperimentManagementStateSource;
import com.hotvect.onlineutils.experimentmanagement.algodownload.ArtifactUriAlgorithmDownloadClient;
import com.hotvect.onlineutils.experimentmanagement.models.AlgorithmMetadata;
import com.hotvect.onlineutils.experimentmanagement.models.Slot;
import com.hotvect.onlineutils.experimentmanagement.models.Variant;
import com.hotvect.onlineutils.serving.EmsPredictionRuntime;
import com.hotvect.onlineutils.serving.FixedCompositionRuntime;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PerformanceTestTaskComposedRuntimeTest {
    private static final String ALGORITHM_NAME = "composed-perf-ranker";
    private static final String ALGORITHM_VERSION = "1";
    private static final ExecutionContext REALTIME_OFFLINE =
            ExecutionContext.of(WorkloadMode.REALTIME, InputSemantic.OFFLINE);
    private static final ExecutionContext BATCH_OFFLINE =
            ExecutionContext.of(WorkloadMode.BATCH, InputSemantic.OFFLINE);

    @TempDir
    Path tempDir;

    @BeforeEach
    void resetObservations() {
        CountingRankerFactory.invocations.set(0);
        CountingRankerFactory.constructions.set(0);
        CountingRankerFactory.executionContext.set(null);
        CountingRankerFactory.localStateStorageAvailable.set(false);
    }

    @Test
    void fixedCompositionPerformanceTestInvokesTheManagedRuntime() throws Exception {
        Path algorithmJar = writeAlgorithmJar();
        Path composition = writeComposition(algorithmJar);
        Options options = performanceOptions("fixed-input.jsonl", "fixture|1.0\n");

        try (FixedCompositionRuntime runtime = FixedCompositionRuntime.builder()
                     .composition(composition)
                     .downloadClient(new ArtifactUriAlgorithmDownloadClient())
                     .scratchDirectory(tempDir.resolve("fixed-scratch"))
                     .algorithmParentClassLoader(getClass().getClassLoader())
                     .executionContext(REALTIME_OFFLINE)
                     .meterRegistry(new SimpleMeterRegistry())
                     .build();
             OfflineTaskContext context = OfflineTaskContext.forFixedComposition(
                     new SimpleMeterRegistry(),
                     options,
                     runtime.rootDefinition(),
                     runtime,
                     null)) {
            Map<String, Object> result = new PerformanceTestTask<>(context).perform();

            assertEquals("realtime", result.get("workload_mode"));
            assertEquals(runtime.runtimeId(), result.get("algorithm_runtime_id"));
        }

        assertEquals(6, CountingRankerFactory.invocations.get());
        assertEquals(1, CountingRankerFactory.constructions.get());
        assertEquals(REALTIME_OFFLINE, CountingRankerFactory.executionContext.get());
        assertTrue(CountingRankerFactory.localStateStorageAvailable.get());
    }

    @Test
    void emsPerformanceTestRoutesEveryMeasuredInvocationThroughTheManagedRuntime() throws Exception {
        Path algorithmJar = writeAlgorithmJar();
        AlgorithmMetadata algorithm = new AlgorithmMetadata(
                ALGORITHM_NAME,
                ALGORITHM_VERSION,
                null,
                algorithmJar.toUri().toString(),
                null);
        Slot slot = new Slot(
                "performance-slot-salt",
                1,
                new Variant(1, algorithm, Instant.parse("2026-08-17T00:00:00Z"), false, true, 100),
                List.of(),
                List.of());
        Options options = performanceOptions("ems-input.jsonl", "{\"customer_id\":\"customer-1\"}\n");
        String rootSlot = "performance-slot";
        String assignmentKeyJsonPointer = "/customer_id";

        try (EmsPredictionRuntime runtime = EmsPredictionRuntime.builder()
                     .rootSlot(rootSlot)
                     .stateSource(new StaticStateSource(rootSlot, slot))
                     .downloadClient(new ArtifactUriAlgorithmDownloadClient())
                     .scratchDirectory(tempDir.resolve("ems-scratch"))
                     .algorithmParentClassLoader(getClass().getClassLoader())
                     .executionContext(REALTIME_OFFLINE)
                     .meterRegistry(new SimpleMeterRegistry())
                     .build();
             OfflineTaskContext context = OfflineTaskContext.forEmsPrediction(
                     new SimpleMeterRegistry(),
                     options,
                     runtime.rootDefinitions().get(0),
                     runtime,
                     null,
                     "test://performance-state",
                     rootSlot,
                     assignmentKeyJsonPointer)) {
            Map<String, Object> result = new PerformanceTestTask<>(context).perform();

            assertEquals("realtime", result.get("workload_mode"));
            assertEquals("performance-slot", result.get("ems_root_slot"));
            assertEquals(1, ((List<?>) result.get("ems_compositions")).size());
        }

        assertEquals(6, CountingRankerFactory.invocations.get());
        assertEquals(1, CountingRankerFactory.constructions.get());
        assertEquals(REALTIME_OFFLINE, CountingRankerFactory.executionContext.get());
        assertTrue(CountingRankerFactory.localStateStorageAvailable.get());
    }

    @Test
    void fixedCompositionExecutesBulkScorerForPredictionAndPerformanceTest() throws Exception {
        Path algorithmJar = writeBulkScorerJar();
        Path composition = writeComposition(algorithmJar);

        Options predictOptions = performanceOptions("fixed-bulk-predict.jsonl", "fixture|1.0\n");
        predictOptions.destinationFile = tempDir.resolve("fixed-bulk-output").toFile();
        try (FixedCompositionRuntime runtime = FixedCompositionRuntime.builder()
                     .composition(composition)
                     .downloadClient(new ArtifactUriAlgorithmDownloadClient())
                     .algorithmParentClassLoader(getClass().getClassLoader())
                     .executionContext(BATCH_OFFLINE)
                     .build();
             OfflineTaskContext context = OfflineTaskContext.forFixedComposition(
                     new SimpleMeterRegistry(),
                     predictOptions,
                     runtime.rootDefinition(),
                     runtime,
                     null)) {
            Map<String, Object> result = new PredictTask<>(context).perform();

            assertEquals(1L, result.get("lines_written"));
        }

        Options performanceOptions = performanceOptions("fixed-bulk-performance.jsonl", "fixture|1.0\n");
        try (FixedCompositionRuntime runtime = FixedCompositionRuntime.builder()
                     .composition(composition)
                     .downloadClient(new ArtifactUriAlgorithmDownloadClient())
                     .algorithmParentClassLoader(getClass().getClassLoader())
                     .executionContext(REALTIME_OFFLINE)
                     .build();
             OfflineTaskContext context = OfflineTaskContext.forFixedComposition(
                     new SimpleMeterRegistry(),
                     performanceOptions,
                     runtime.rootDefinition(),
                     runtime,
                     null)) {
            Map<String, Object> result = new PerformanceTestTask<>(context).perform();

            assertEquals("realtime", result.get("workload_mode"));
        }
    }

    @Test
    void pinnedEmsCompositionExecutesBulkScorerForPredictionAndPerformanceTest() throws Exception {
        Path algorithmJar = writeBulkScorerJar();
        AlgorithmMetadata algorithm = new AlgorithmMetadata(
                ALGORITHM_NAME,
                ALGORITHM_VERSION,
                null,
                algorithmJar.toUri().toString(),
                null);
        String rootSlot = "bulk-scorer-slot";
        Slot slot = new Slot(
                "bulk-scorer-slot-salt",
                1,
                new Variant(1, algorithm, Instant.parse("2026-08-17T00:00:00Z"), false, true, 100),
                List.of(),
                List.of());

        Options predictOptions = performanceOptions(
                "ems-bulk-predict.jsonl",
                "{\"customer_id\":\"customer-1\"}\n");
        predictOptions.destinationFile = tempDir.resolve("ems-bulk-output").toFile();
        try (EmsPredictionRuntime runtime = EmsPredictionRuntime.builder()
                     .rootSlot(rootSlot)
                     .stateSource(new StaticStateSource(rootSlot, slot))
                     .downloadClient(new ArtifactUriAlgorithmDownloadClient())
                     .algorithmParentClassLoader(getClass().getClassLoader())
                     .executionContext(BATCH_OFFLINE)
                     .build();
             OfflineTaskContext context = OfflineTaskContext.forEmsPrediction(
                     new SimpleMeterRegistry(),
                     predictOptions,
                     runtime.rootDefinitions().getFirst(),
                     runtime,
                     null,
                     "test://bulk-scorer-state",
                     rootSlot,
                     "/customer_id")) {
            Map<String, Object> result = new PredictTask<>(context).perform();

            assertEquals(1L, result.get("lines_written"));
            assertEquals(rootSlot, result.get("ems_root_slot"));
        }

        Options performanceOptions = performanceOptions(
                "ems-bulk-performance.jsonl",
                "{\"customer_id\":\"customer-1\"}\n");
        try (EmsPredictionRuntime runtime = EmsPredictionRuntime.builder()
                     .rootSlot(rootSlot)
                     .stateSource(new StaticStateSource(rootSlot, slot))
                     .downloadClient(new ArtifactUriAlgorithmDownloadClient())
                     .algorithmParentClassLoader(getClass().getClassLoader())
                     .executionContext(REALTIME_OFFLINE)
                     .build();
             OfflineTaskContext context = OfflineTaskContext.forEmsPrediction(
                     new SimpleMeterRegistry(),
                     performanceOptions,
                     runtime.rootDefinitions().getFirst(),
                     runtime,
                     null,
                     "test://bulk-scorer-state",
                     rootSlot,
                     "/customer_id")) {
            Map<String, Object> result = new PerformanceTestTask<>(context).perform();

            assertEquals("realtime", result.get("workload_mode"));
            assertEquals(rootSlot, result.get("ems_root_slot"));
        }
    }

    @Test
    void fixedCompositionResolvesDefinitionContextBeforeConstructingGraph() throws Exception {
        Path algorithmJar = writeBatchAlgorithmJar();
        Path composition = writeComposition(algorithmJar);

        try (FixedCompositionRuntime ignored = FixedCompositionRuntime.builder()
                .composition(composition)
                .downloadClient(new ArtifactUriAlgorithmDownloadClient())
                .scratchDirectory(tempDir.resolve("fixed-context-scratch"))
                .algorithmParentClassLoader(getClass().getClassLoader())
                .executionContext(REALTIME_OFFLINE)
                .resolveExecutionContext(this::definitionExecutionContext)
                .meterRegistry(new SimpleMeterRegistry())
                .build()) {
            assertEquals(1, CountingRankerFactory.constructions.get());
            assertEquals(BATCH_OFFLINE, CountingRankerFactory.executionContext.get());
        }
    }

    @Test
    void emsCompositionResolvesDefinitionContextBeforeConstructingGraph() throws Exception {
        Path algorithmJar = writeBatchAlgorithmJar();
        AlgorithmMetadata algorithm = new AlgorithmMetadata(
                ALGORITHM_NAME,
                ALGORITHM_VERSION,
                null,
                algorithmJar.toUri().toString(),
                null);
        Slot slot = new Slot(
                "performance-slot-salt",
                1,
                new Variant(1, algorithm, Instant.parse("2026-08-17T00:00:00Z"), false, true, 100),
                List.of(),
                List.of());

        try (EmsPredictionRuntime ignored = EmsPredictionRuntime.builder()
                .rootSlot("performance-slot")
                .stateSource(new StaticStateSource("performance-slot", slot))
                .downloadClient(new ArtifactUriAlgorithmDownloadClient())
                .scratchDirectory(tempDir.resolve("ems-context-scratch"))
                .algorithmParentClassLoader(getClass().getClassLoader())
                .executionContext(REALTIME_OFFLINE)
                .resolveExecutionContext(this::definitionExecutionContext)
                .meterRegistry(new SimpleMeterRegistry())
                .build()) {
            assertEquals(1, CountingRankerFactory.constructions.get());
            assertEquals(BATCH_OFFLINE, CountingRankerFactory.executionContext.get());
        }
    }

    @Test
    void fixedCompositionRejectsOrderedPredictionWhenUnorderedOutputIsRequired() throws Exception {
        Path algorithmJar = writeOrderedPredictionAlgorithmJar();
        Path composition = writeComposition(algorithmJar);
        Options options = parallelPredictionOptions();

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> FixedCompositionRuntime.builder()
                        .composition(composition)
                        .downloadClient(new ArtifactUriAlgorithmDownloadClient())
                        .algorithmParentClassLoader(getClass().getClassLoader())
                        .executionContext(BATCH_OFFLINE)
                        .resolveExecutionContext(definitions -> {
                            Main.applyManagedExecutionOptions(options, definitions, "predict");
                            return BATCH_OFFLINE;
                        })
                        .meterRegistry(new SimpleMeterRegistry())
                        .build());

        assertTrue(failure.getMessage().contains("hotvect_execution_parameters.predict.ordered=true"));
    }

    @Test
    void emsCompositionRejectsOrderedPredictionWhenUnorderedOutputIsRequired() throws Exception {
        Path algorithmJar = writeOrderedPredictionAlgorithmJar();
        AlgorithmMetadata algorithm = new AlgorithmMetadata(
                ALGORITHM_NAME,
                ALGORITHM_VERSION,
                null,
                algorithmJar.toUri().toString(),
                null);
        String rootSlot = "prediction-slot";
        Slot slot = new Slot(
                "prediction-slot-salt",
                1,
                new Variant(1, algorithm, Instant.parse("2026-08-17T00:00:00Z"), false, true, 100),
                List.of(),
                List.of());
        Options options = parallelPredictionOptions();

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> EmsPredictionRuntime.builder()
                        .rootSlot(rootSlot)
                        .stateSource(new StaticStateSource(rootSlot, slot))
                        .downloadClient(new ArtifactUriAlgorithmDownloadClient())
                        .algorithmParentClassLoader(getClass().getClassLoader())
                        .executionContext(BATCH_OFFLINE)
                        .resolveExecutionContext(definitions -> {
                            Main.applyManagedExecutionOptions(options, definitions, "predict");
                            return BATCH_OFFLINE;
                        })
                        .meterRegistry(new SimpleMeterRegistry())
                        .build());

        assertTrue(failure.getMessage().contains("hotvect_execution_parameters.predict.ordered=true"));
    }

    private Options performanceOptions(String fileName, String input) throws IOException {
        Path inputPath = tempDir.resolve(fileName);
        Files.writeString(inputPath, input);
        Options options = new Options();
        options.sourceFiles = Map.of("default", List.of(inputPath.toFile()));
        options.samples = 1;
        options.samplePoolSize = 1;
        options.targetThroughputFraction = 0.0;
        options.maxThreads = 1;
        options.batchSize = 1;
        options.performanceTestWorkloadMode = "realtime";
        return options;
    }

    private Path writeAlgorithmJar() throws IOException {
        return writeAlgorithmJar("");
    }

    private Path writeBatchAlgorithmJar() throws IOException {
        return writeAlgorithmJar("""
                ,
                  "hotvect_execution_parameters": {
                    "performance-test": {"workload_mode": "batch"}
                  }
                """);
    }

    private Path writeOrderedPredictionAlgorithmJar() throws IOException {
        return writeAlgorithmJar("""
                ,
                  "hotvect_execution_parameters": {
                    "predict": {"ordered": true}
                  }
                """);
    }

    private Path writeBulkScorerJar() throws IOException {
        Path jar = tempDir.resolve(ALGORITHM_NAME + "-bulk-scorer.jar");
        String definition = """
                {
                  "algorithm_name": "%s",
                  "algorithm_version": "%s",
                  "decoder_factory_classname": "%s",
                  "reward_function_factory_classname": "%s",
                  "algorithm_factory_classname": "%s"
                }
                """.formatted(
                ALGORITHM_NAME,
                ALGORITHM_VERSION,
                JsonFixtureDecoderFactory.class.getName(),
                ParityFixtureAlgorithm.TestRewardFunctionFactory.class.getName(),
                BulkScorerFactory.class.getName());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry(ALGORITHM_NAME + "-algorithm-definition.json"));
            output.write(definition.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return jar;
    }

    private static Options parallelPredictionOptions() {
        Options options = new Options();
        options.unordered = true;
        options.requireUnorderedOutput = true;
        return options;
    }

    private Path writeAlgorithmJar(String executionParameters) throws IOException {
        Path jar = tempDir.resolve(ALGORITHM_NAME + ".jar");
        String definition = """
                {
                  "algorithm_name": "%s",
                  "algorithm_version": "%s",
                  "decoder_factory_classname": "%s",
                  "algorithm_factory_classname": "%s"%s
                }
                """.formatted(
                ALGORITHM_NAME,
                ALGORITHM_VERSION,
                JsonFixtureDecoderFactory.class.getName(),
                CountingRankerFactory.class.getName(),
                executionParameters);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry(ALGORITHM_NAME + "-algorithm-definition.json"));
            output.write(definition.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return jar;
    }

    private ExecutionContext definitionExecutionContext(List<AlgorithmDefinition> definitions) {
        assertEquals(0, CountingRankerFactory.constructions.get());
        assertEquals(1, definitions.size());
        String configured = Main.resolvePerformanceTestWorkloadMode(
                Optional.of(definitions.getFirst().rawAlgorithmDefinition()),
                null);
        return ExecutionContext.of(
                PerformanceTestTask.resolveWorkloadMode(configured),
                InputSemantic.OFFLINE);
    }

    private Path writeComposition(Path algorithmJar) throws IOException {
        Path composition = tempDir.resolve("composition.json");
        Files.writeString(
                composition,
                """
                        {
                          "root": "%1$s@%2$s",
                          "algorithms": {
                            "%1$s@%2$s": {"jar_uri": "%3$s"}
                          },
                          "slot_bindings": {}
                        }
                        """.formatted(ALGORITHM_NAME, ALGORITHM_VERSION, algorithmJar.toUri()));
        return composition;
    }

    private record StaticStateSource(String rootSlot, Slot slot) implements ExperimentManagementStateSource {
        @Override
        public Slot getDefaultVariantAndActiveExperiments(String requestedSlot) {
            if (!rootSlot.equals(requestedSlot)) {
                throw new IllegalArgumentException("Unexpected EMS slot: " + requestedSlot);
            }
            return slot;
        }

        @Override
        public void close() {
        }
    }

    public static final class JsonFixtureDecoderFactory implements RankingExampleDecoderFactory<
            ParityFixtureAlgorithm.SharedContext,
            ParityFixtureAlgorithm.ActionContext,
            Double> {
        @Override
        @SuppressWarnings("removal")
        public RankingExampleDecoder<
                ParityFixtureAlgorithm.SharedContext,
                ParityFixtureAlgorithm.ActionContext,
                Double> apply(Optional<JsonNode> hyperparameter) {
            RankingExampleDecoder<
                    ParityFixtureAlgorithm.SharedContext,
                    ParityFixtureAlgorithm.ActionContext,
                    Double> fixtureDecoder = new ParityFixtureAlgorithm.ExampleDecoderFactory().apply(hyperparameter);
            return input -> fixtureDecoder.apply("fixture|1.0");
        }
    }

    public static final class CountingRankerFactory implements SimpleRankerFactory<
            ParityFixtureAlgorithm.SharedContext,
            ParityFixtureAlgorithm.ActionContext> {
        private static final AtomicInteger invocations = new AtomicInteger();
        private static final AtomicInteger constructions = new AtomicInteger();
        private static final AtomicReference<ExecutionContext> executionContext = new AtomicReference<>();
        private static final AtomicBoolean localStateStorageAvailable = new AtomicBoolean();

        @Override
        @SuppressWarnings("removal")
        public Ranker<ParityFixtureAlgorithm.SharedContext, ParityFixtureAlgorithm.ActionContext> apply(
                Optional<JsonNode> hyperparameter) {
            throw new AssertionError("Runtime-aware factory method must be used");
        }

        @Override
        public Ranker<ParityFixtureAlgorithm.SharedContext, ParityFixtureAlgorithm.ActionContext> create(
                ExecutionContext observedExecutionContext,
                Optional<LocalStateStorage> localStateStorage,
                Optional<JsonNode> hyperparameter) {
            constructions.incrementAndGet();
            executionContext.set(observedExecutionContext);
            localStateStorageAvailable.set(localStateStorage.isPresent());
            return new CountingRanker();
        }
    }

    private static final class CountingRanker implements Ranker<
            ParityFixtureAlgorithm.SharedContext,
            ParityFixtureAlgorithm.ActionContext> {
        @Override
        public RankingResponse<ParityFixtureAlgorithm.ActionContext> rank(
                RankingRequest<
                        ParityFixtureAlgorithm.SharedContext,
                        ParityFixtureAlgorithm.ActionContext> request) {
            CountingRankerFactory.invocations.incrementAndGet();
            return RankingResponse.newResponse(List.of());
        }
    }

    public static final class BulkScorerFactory implements SimpleAlgorithmFactory<BulkScorer<
            ParityFixtureAlgorithm.SharedContext,
            ParityFixtureAlgorithm.ActionContext>> {
        @Override
        @SuppressWarnings("removal")
        public BulkScorer<ParityFixtureAlgorithm.SharedContext, ParityFixtureAlgorithm.ActionContext> apply(
                Optional<JsonNode> hyperparameter) {
            return new FixtureBulkScorer();
        }
    }

    private static final class FixtureBulkScorer implements BulkScorer<
            ParityFixtureAlgorithm.SharedContext,
            ParityFixtureAlgorithm.ActionContext> {
        @Override
        public BulkScoreResponse<ParityFixtureAlgorithm.ActionContext> score(
                RankingRequest<
                        ParityFixtureAlgorithm.SharedContext,
                        ParityFixtureAlgorithm.ActionContext> request) {
            List<ScoringDecision<ParityFixtureAlgorithm.ActionContext>> decisions = request.actions().stream()
                    .map(action -> ScoringDecision.of(action.actionId(), action.action(), 1.0))
                    .toList();
            return BulkScoreResponse.of(decisions, FeatureStoreResponseContainer.empty());
        }
    }
}
