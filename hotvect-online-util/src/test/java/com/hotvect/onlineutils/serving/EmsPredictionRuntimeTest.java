package com.hotvect.onlineutils.serving;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.AlgorithmDependencies;
import com.hotvect.api.algodefinition.AlgorithmId;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algodefinition.HyperparameterizedAlgorithmId;
import com.hotvect.api.algodefinition.ParameterizedAlgorithmId;
import com.hotvect.api.algodefinition.common.CompositeAlgorithmFactory;
import com.hotvect.api.algodefinition.common.SimpleAlgorithmFactory;
import com.hotvect.api.algodefinition.ranking.SimpleRankerFactory;
import com.hotvect.api.algodefinition.storage.LocalStateStorage;
import com.hotvect.api.algorithms.BulkScorer;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.algorithms.ThemedTopK;
import com.hotvect.api.algorithms.TopK;
import com.hotvect.api.data.FeatureStoreResponseContainer;
import com.hotvect.api.data.Response;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.ranking.RankingResponse;
import com.hotvect.api.data.scoring.BulkScoreResponse;
import com.hotvect.api.data.topk.ThemedTopKResponse;
import com.hotvect.api.data.topk.TopKRequest;
import com.hotvect.api.data.topk.TopKResponse;
import com.hotvect.api.execution.ExecutionContext;
import com.hotvect.api.execution.InputSemantic;
import com.hotvect.api.execution.WorkloadMode;
import com.hotvect.onlineutils.experimentmanagement.ExperimentManagementStateSource;
import com.hotvect.onlineutils.experimentmanagement.algodownload.AlgorithmDownloadClient;
import com.hotvect.onlineutils.experimentmanagement.generated.SlotActiveInfo;
import com.hotvect.onlineutils.experimentmanagement.generated.SlotActiveInfoAlgorithmResponse;
import com.hotvect.onlineutils.experimentmanagement.generated.SlotActiveInfoVariantResponse;
import com.hotvect.onlineutils.experimentmanagement.httpclient.EmsStateSnapshotDocument;
import com.hotvect.onlineutils.experimentmanagement.httpclient.FileExperimentManagementStateSource;
import com.hotvect.onlineutils.experimentmanagement.models.AlgorithmMetadata;
import com.hotvect.onlineutils.experimentmanagement.models.Experiment;
import com.hotvect.onlineutils.experimentmanagement.models.Shard;
import com.hotvect.onlineutils.experimentmanagement.models.Slot;
import com.hotvect.onlineutils.experimentmanagement.models.UserForcedAssignment;
import com.hotvect.onlineutils.experimentmanagement.models.Variant;
import com.hotvect.onlineutils.experimentmanagement.variantassignment.VariantAssigner;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EmsPredictionRuntimeTest {
    private static final Instant CREATED_AT = Instant.parse("2026-08-01T00:00:00Z");

    @TempDir
    Path tempDir;

    @BeforeEach
    void clearFactoryObservations() {
        RootFactory.observedContexts.clear();
        ParameterizedRootFactory.observedContexts.clear();
        ParameterizedRootFactory.observedParameterEntries.clear();
        CompositeRootFactory.observedContexts.clear();
        CompositeRootFactory.observedDependencyNames.clear();
    }

    @Test
    void preparesAllRootVariantsOnceAndRoutesDefaultExperimentRampUpAndForcedAssignments() throws Exception {
        AlgorithmMetadata defaultRoot = parameterlessArtifact(
                "default-root", "1", RootFactory.class, null);
        AlgorithmMetadata treatmentRoot = parameterizedArtifact(
                "treatment-root", "2", "parameters-2", ParameterizedRootFactory.class, null);
        Slot rootSlot = slotWithExperiment(
                "root-salt",
                variant(1, defaultRoot),
                variant(2, treatmentRoot),
                50,
                List.of(new UserForcedAssignment("forced-treatment", 2)));
        VariantAssigner assigner = new VariantAssigner(rootSlot);
        String defaultKey = findKey(assigner, 1);
        String treatmentKey = findKey(assigner, 2);
        TestStateSource stateSource = new TestStateSource(Map.of("product-ranking", rootSlot));
        CopyingDownloadClient downloadClient = new CopyingDownloadClient();

        try (EmsPredictionRuntime runtime = runtime("product-ranking", stateSource, downloadClient)) {
            assertEquals(2, runtime.rootDefinitions().size());
            assertEquals(2, downloadClient.jarDownloads.get());
            assertEquals(1, downloadClient.parameterDownloads.get());
            assertEquals(List.of(ExecutionContext.batch(InputSemantic.OFFLINE)), RootFactory.observedContexts);
            assertEquals(
                    List.of(ExecutionContext.batch(InputSemantic.OFFLINE)),
                    ParameterizedRootFactory.observedContexts);
            assertEquals(
                    List.of(Set.of("algorithm-parameters.json")),
                    ParameterizedRootFactory.observedParameterEntries);

            AlgorithmExecution<Response<String>> defaultExecution = invokeTag(runtime, defaultKey);
            AlgorithmExecution<Response<String>> treatmentExecution = invokeTag(runtime, treatmentKey);
            AlgorithmExecution<Response<String>> forcedExecution = invokeTag(runtime, "forced-treatment");

            assertEquals("default-root", defaultExecution.result().additionalProperties().get("tag"));
            assertEquals("1", defaultExecution.selection().assignments().get("product-ranking").variantId());
            assertEquals(
                    defaultExecution.selection().runtimeId().algorithm(),
                    defaultExecution.selection().assignments().get("product-ranking").algorithm());
            assertEquals("treatment-root", treatmentExecution.result().additionalProperties().get("tag"));
            assertEquals("2", treatmentExecution.selection().assignments().get("product-ranking").variantId());
            assertEquals(
                    treatmentExecution.selection().runtimeId().algorithm(),
                    treatmentExecution.selection().assignments().get("product-ranking").algorithm());
            assertEquals("treatment-root", forcedExecution.result().additionalProperties().get("tag"));
            assertEquals("2", forcedExecution.selection().assignments().get("product-ranking").variantId());
            assertNotEquals(
                    defaultExecution.selection().runtimeId(),
                    treatmentExecution.selection().runtimeId());

            assertEquals(1, stateSource.requests.get("product-ranking").get());
            assertEquals(2, downloadClient.jarDownloads.get());
            assertEquals(1, downloadClient.parameterDownloads.get());
        }

        assertTrue(stateSource.closed.get());
        assertTrue(downloadClient.closed.get());
    }

    @Test
    void preparesNestedSlotCompositionsWithoutRecordTimeIo() throws Exception {
        AlgorithmMetadata root = parameterlessArtifact(
                "composite-root",
                "1",
                CompositeRootFactory.class,
                "\"dependencies\": {\"policy-slot\": {\"scope\": \"slot\"}}");
        AlgorithmMetadata policyA = parameterlessArtifact("policy-a", "1", LeafFactory.class, null);
        AlgorithmMetadata policyC = parameterlessArtifact("policy-c", "2", LeafFactory.class, null);
        Slot rootSlot = defaultOnlySlot("root-salt", variant(1, root));
        Slot policySlot = slotWithExperiment(
                "policy-salt",
                variant(10, policyA),
                variant(11, policyC),
                100,
                List.of(new UserForcedAssignment("forced-default-policy", 10)));
        TestStateSource stateSource = new TestStateSource(Map.of(
                "product-ranking", rootSlot,
                "policy-slot", policySlot));
        CopyingDownloadClient downloadClient = new CopyingDownloadClient();

        try (EmsPredictionRuntime runtime = runtime("product-ranking", stateSource, downloadClient)) {
            assertEquals(3, downloadClient.jarDownloads.get());
            assertEquals(2, CompositeRootFactory.observedDependencyNames.size());
            assertTrue(CompositeRootFactory.observedDependencyNames.contains(Set.of("policy-a")));
            assertTrue(CompositeRootFactory.observedDependencyNames.contains(Set.of("policy-c")));

            AlgorithmExecution<Response<String>> defaultPolicy = invokeTag(runtime, "forced-default-policy");
            AlgorithmExecution<Response<String>> experimentPolicy = invokeTag(runtime, "ordinary-customer");

            assertEquals("policy-a", defaultPolicy.result().additionalProperties().get("tag"));
            assertEquals(
                    Map.of("policy-slot", "10", "product-ranking", "1"),
                    variantIds(defaultPolicy.selection()));
            assertEquals(
                    parameterizedAlgorithm("policy-a", "1", ParameterizedAlgorithmId.NO_PARAMETER_ID),
                    defaultPolicy.selection().assignments().get("policy-slot").algorithm());
            assertEquals("policy-c", experimentPolicy.result().additionalProperties().get("tag"));
            assertEquals(
                    Map.of("policy-slot", "11", "product-ranking", "1"),
                    variantIds(experimentPolicy.selection()));

            assertEquals(1, stateSource.requests.get("product-ranking").get());
            assertEquals(1, stateSource.requests.get("policy-slot").get());
            assertEquals(3, downloadClient.jarDownloads.get());
        }
    }

    @Test
    void rejectsInvocationAfterClose() throws Exception {
        AlgorithmMetadata root = parameterlessArtifact("default-root", "1", RootFactory.class, null);
        TestStateSource stateSource = new TestStateSource(Map.of(
                "product-ranking", defaultOnlySlot("root-salt", variant(1, root))));
        CopyingDownloadClient downloadClient = new CopyingDownloadClient();
        EmsPredictionRuntime runtime = runtime("product-ranking", stateSource, downloadClient);

        runtime.close();

        assertThrows(IllegalStateException.class, () -> invokeTag(runtime, "customer"));
    }

    @Test
    void borrowsThePreparedCompositionAndClosesSynchronouslyAtTaskEnd() throws Exception {
        AlgorithmMetadata root = parameterlessArtifact(
                "close-tracking-root",
                "1",
                CloseTrackingRankerFactory.class,
                null);
        TestStateSource stateSource = new TestStateSource(Map.of(
                "product-ranking",
                defaultOnlySlot("root-salt", variant(1, root))));
        CopyingDownloadClient downloadClient = new CopyingDownloadClient();
        EmsPredictionRuntime runtime = runtime("product-ranking", stateSource, downloadClient);
        SelectedAlgorithmRuntime selected = runtime.select("customer");
        CloseTrackingRanker algorithm = (CloseTrackingRanker) selected.context().algorithmInstance().algorithm();
        try (runtime) {
            SelectedAlgorithmRuntime another = runtime.select("another-customer");
            assertSame(selected.context().algorithmInstance(), another.context().algorithmInstance());
            assertEquals(selected.selection(), another.selection());
            assertEquals(0, algorithm.closeCalls);
            assertFalse(stateSource.closed.get());
            assertFalse(downloadClient.closed.get());
        }

        assertEquals(1, algorithm.closeCalls);
        assertSame(Thread.currentThread(), algorithm.closedOn);
        assertTrue(stateSource.closed.get());
        assertTrue(downloadClient.closed.get());
        assertThrows(IllegalStateException.class, () -> runtime.select("customer"));
        assertThrows(IllegalStateException.class, selected.context()::algorithmInstance);
        runtime.close();
        assertEquals(1, algorithm.closeCalls);
    }

    @Test
    void shutdownSurfacesAlgorithmCleanupFailureAndStillClosesClients() throws Exception {
        AlgorithmMetadata root = parameterlessArtifact("failing-close-root", "1", FailingCloseFactory.class, null);
        TestStateSource stateSource = new TestStateSource(Map.of(
                "product-ranking", defaultOnlySlot("root-salt", variant(1, root))));
        CopyingDownloadClient downloadClient = new CopyingDownloadClient();
        EmsPredictionRuntime runtime = runtime("product-ranking", stateSource, downloadClient);

        IllegalStateException failure = assertThrows(IllegalStateException.class, runtime::close);

        assertEquals("algorithm cleanup failed", failure.getMessage());
        assertTrue(stateSource.closed.get());
        assertTrue(downloadClient.closed.get());
    }

    @Test
    void invokesTopKDirectly() throws Exception {
        AlgorithmMetadata root = parameterlessArtifact("top-k-root", "1", TopKRootFactory.class, null);
        TestStateSource stateSource = new TestStateSource(Map.of(
                "product-ranking", defaultOnlySlot("root-salt", variant(1, root))));

        try (EmsPredictionRuntime runtime = runtime(
                "product-ranking",
                stateSource,
                new CopyingDownloadClient())) {
            AlgorithmExecution<Response<String>> execution = runtime.invoke(
                    "customer",
                    new TopKRequest<>("example", CREATED_AT, "shared", 10));

            assertTrue(execution.result() instanceof TopKResponse<?>);
            assertEquals("1", execution.selection().assignments().get("product-ranking").variantId());
            assertThrows(IllegalArgumentException.class, () -> invokeTag(runtime, "customer"));
        }
    }

    @Test
    void invokesBulkScorerAndPreservesItsResponseAndAttribution() throws Exception {
        AlgorithmMetadata root = parameterlessArtifact("scorer-root", "1", BulkScorerRootFactory.class, null);
        TestStateSource stateSource = new TestStateSource(Map.of(
                "product-ranking", defaultOnlySlot("root-salt", variant(1, root))));

        try (EmsPredictionRuntime runtime = runtime(
                "product-ranking", stateSource, new CopyingDownloadClient())) {
            AlgorithmExecution<Response<String>> execution = invokeTag(runtime, "customer");

            assertInstanceOf(BulkScoreResponse.class, execution.result());
            assertEquals("scorer", execution.result().additionalProperties().get("tag"));
            assertEquals("scorer-root@1", execution.selection().runtimeId().algorithm().algorithmId().value());
            assertEquals("1", execution.selection().assignments().get("product-ranking").variantId());
        }
    }

    @Test
    void invokesThemedTopKAndPreservesThemeMetadataAndAttribution() throws Exception {
        AlgorithmMetadata root = parameterlessArtifact("themed-root", "1", ThemedTopKRootFactory.class, null);
        TestStateSource stateSource = new TestStateSource(Map.of(
                "recommendations", defaultOnlySlot("root-salt", variant(1, root))));

        try (EmsPredictionRuntime runtime = runtime(
                "recommendations", stateSource, new CopyingDownloadClient())) {
            AlgorithmExecution<Response<String>> execution = runtime.invoke(
                    "customer", new TopKRequest<>("example", CREATED_AT, "shared", 10));

            ThemedTopKResponse<?> response = assertInstanceOf(ThemedTopKResponse.class, execution.result());
            assertEquals("theme", response.getActionListId());
            assertEquals(Map.of("title", "Recommendations"), response.getActionListMetadata());
            assertEquals("themed-root@1", execution.selection().runtimeId().algorithm().algorithmId().value());
            assertEquals("1", execution.selection().assignments().get("recommendations").variantId());
        }
    }

    @Test
    void rejectsRootVariantsWithDifferentInvocationContractsDuringPreparation() throws Exception {
        AlgorithmMetadata ranker = parameterlessArtifact("ranker-root", "1", RootFactory.class, null);
        AlgorithmMetadata topK = parameterlessArtifact("top-k-root", "1", TopKRootFactory.class, null);
        Slot rootSlot = slotWithExperiment(
                "root-salt",
                variant(1, ranker),
                variant(2, topK),
                100,
                List.of());
        TestStateSource stateSource = new TestStateSource(Map.of("product-ranking", rootSlot));
        CopyingDownloadClient downloadClient = new CopyingDownloadClient();

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> runtime("product-ranking", stateSource, downloadClient));

        assertTrue(failure.getMessage().contains("variants must share one invocation contract"));
        assertTrue(stateSource.closed.get());
        assertTrue(downloadClient.closed.get());
    }

    @Test
    void closesInspectedArtifactsAndClientsWhenExecutionContextResolutionFails() throws Exception {
        AlgorithmMetadata root = parameterlessArtifact("default-root", "1", RootFactory.class, null);
        TestStateSource stateSource = new TestStateSource(Map.of(
                "product-ranking", defaultOnlySlot("root-salt", variant(1, root))));
        CopyingDownloadClient downloadClient = new CopyingDownloadClient();
        Path scratch = tempDir.resolve("scratch");
        IllegalArgumentException expected = new IllegalArgumentException("Invalid execution options");

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> EmsPredictionRuntime.builder()
                .rootSlot("product-ranking")
                .stateSource(stateSource)
                .downloadClient(downloadClient)
                .scratchDirectory(scratch)
                .algorithmParentClassLoader(getClass().getClassLoader())
                .resolveExecutionContext(definitions -> {
                    assertEquals(
                            List.of(root.algorithmId()),
                            definitions.stream().map(definition -> definition.algorithmId()).toList());
                    throw expected;
                })
                .build());

        assertSame(expected, failure);
        assertTrue(RootFactory.observedContexts.isEmpty(), "Algorithms must be constructed only after options resolve");
        assertEquals(1, downloadClient.jarDownloads.get());
        assertTrue(stateSource.closed.get());
        assertTrue(downloadClient.closed.get());
        try (var files = Files.walk(scratch)) {
            assertFalse(files.anyMatch(path -> path.toString().endsWith(".jar")));
        }
    }

    @Test
    void rejectsPinnedStateContainingSlotsOutsideTheReachableClosure() throws Exception {
        AlgorithmMetadata root = parameterlessArtifact("default-root", "1", RootFactory.class, null);
        AlgorithmMetadata unused = parameterlessArtifact("unused", "1", LeafFactory.class, null);
        Path statePath = writePinnedState(
                Map.of("product-ranking", root, "unused-slot", unused),
                null);
        FileExperimentManagementStateSource stateSource = new FileExperimentManagementStateSource(statePath);
        CopyingDownloadClient downloadClient = new CopyingDownloadClient();

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> runtime("product-ranking", stateSource, downloadClient));

        assertTrue(failure.getMessage().contains("captured=[product-ranking, unused-slot]"));
        assertTrue(failure.getMessage().contains("reachable=[product-ranking]"));
        assertTrue(downloadClient.closed.get());
    }

    @Test
    void rejectsPinnedStateWhoseProvenanceNamesAnotherRoot() throws Exception {
        AlgorithmMetadata root = parameterlessArtifact("default-root", "1", RootFactory.class, null);
        Path statePath = writePinnedState(Map.of("product-ranking", root), "other-root");
        FileExperimentManagementStateSource stateSource = new FileExperimentManagementStateSource(statePath);
        CopyingDownloadClient downloadClient = new CopyingDownloadClient();

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> runtime("product-ranking", stateSource, downloadClient));

        assertEquals(
                "EMS state was captured for root slot other-root but prediction requested root slot product-ranking",
                failure.getMessage());
        assertTrue(downloadClient.closed.get());
    }

    private EmsPredictionRuntime runtime(
            String rootSlot,
            ExperimentManagementStateSource stateSource,
            CopyingDownloadClient downloadClient) throws Exception {
        return EmsPredictionRuntime.builder()
                .rootSlot(rootSlot)
                .stateSource(stateSource)
                .downloadClient(downloadClient)
                .scratchDirectory(tempDir.resolve("scratch"))
                .algorithmParentClassLoader(getClass().getClassLoader())
                .build();
    }

    private Path writePinnedState(
            Map<String, AlgorithmMetadata> algorithmsBySlot,
            String requestedRootSlot) throws IOException {
        Map<String, SlotActiveInfo> slots = new LinkedHashMap<>();
        algorithmsBySlot.forEach((slotName, metadata) -> slots.put(
                slotName,
                new SlotActiveInfo(
                        slotName + "-salt",
                        1,
                        new SlotActiveInfoVariantResponse(
                                1,
                                activeAlgorithm(metadata),
                                CREATED_AT,
                                true,
                                null,
                                null),
                        List.of(),
                        List.of())));
        EmsStateSnapshotDocument.CaptureProvenance provenance = requestedRootSlot == null
                ? null
                : new EmsStateSnapshotDocument.CaptureProvenance(
                        1,
                        "https://ems.example",
                        requestedRootSlot,
                        CREATED_AT,
                        CREATED_AT,
                        algorithmsBySlot.keySet().stream()
                                .collect(java.util.stream.Collectors.toMap(name -> name, name -> CREATED_AT)),
                        "independently_fetched_per_slot_non_transactional");
        Path statePath = tempDir.resolve("ems-state-" + algorithmsBySlot.size() + "-" + requestedRootSlot + ".json");
        EmsStateSnapshotDocument.writeAtomically(
                statePath,
                new EmsStateSnapshotDocument(slots, provenance));
        return statePath;
    }

    private static SlotActiveInfoAlgorithmResponse activeAlgorithm(AlgorithmMetadata metadata) {
        return new SlotActiveInfoAlgorithmResponse(
                metadata.algorithmName(),
                metadata.algorithmVersion(),
                metadata.latestAlgorithmParameter(),
                metadata.absoluteS3AlgorithmJarPath(),
                metadata.absoluteS3AlgorithmParameterPath());
    }

    private static AlgorithmExecution<Response<String>> invokeTag(
            EmsPredictionRuntime runtime,
            String assignmentKey) {
        RankingRequest<String, String> request = RankingRequest.ofAvailableActions(
                "example",
                "shared",
                List.of());
        return runtime.invoke(assignmentKey, request);
    }

    private static Map<String, String> variantIds(AlgorithmSelection selection) {
        return selection.assignments().entrySet().stream().collect(java.util.stream.Collectors.toMap(
                Map.Entry::getKey,
                entry -> entry.getValue().variantId()));
    }

    private AlgorithmMetadata parameterlessArtifact(
            String name,
            String version,
            Class<?> factory,
            String additionalDefinitionField) throws IOException {
        Path jar = writeAlgorithmJar(name, version, factory, additionalDefinitionField);
        return new AlgorithmMetadata(name, version, null, jar.toUri().toString(), null);
    }

    private static ParameterizedAlgorithmId parameterizedAlgorithm(
            String name,
            String version,
            String parameterId) {
        return new ParameterizedAlgorithmId(
                new HyperparameterizedAlgorithmId(new AlgorithmId(name, version), null),
                parameterId);
    }

    private AlgorithmMetadata parameterizedArtifact(
            String name,
            String version,
            String parameterId,
            Class<?> factory,
            String additionalDefinitionField) throws IOException {
        Path jar = writeAlgorithmJar(name, version, factory, additionalDefinitionField);
        Path parameters = tempDir.resolve(name + "-" + parameterId + ".zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(parameters))) {
            output.putNextEntry(new ZipEntry(name + "/algorithm-parameters.json"));
            output.write("""
                    {"algorithm_name":"%s","algorithm_version":"%s",\
                    "parameter_id":"%s","ran_at":"2026-08-01T00:00:00Z"}
                    """.formatted(name, version, parameterId).getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return new AlgorithmMetadata(
                name,
                version,
                parameterId,
                jar.toUri().toString(),
                parameters.toUri().toString());
    }

    private Path writeAlgorithmJar(
            String name,
            String version,
            Class<?> factory,
            String additionalDefinitionField) throws IOException {
        Path jar = tempDir.resolve(name + "-" + version + ".jar");
        String extra = additionalDefinitionField == null ? "" : "," + additionalDefinitionField;
        String definition = """
                {
                  "algorithm_name": "%s",
                  "algorithm_version": "%s",
                  "algorithm_factory_classname": "%s"%s
                }
                """.formatted(name, version, factory.getName(), extra);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry(name + "-algorithm-definition.json"));
            output.write(definition.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return jar;
    }

    private static Slot defaultOnlySlot(String salt, Variant defaultVariant) {
        return new Slot(salt, 1, defaultVariant, List.of(), List.of());
    }

    private static Slot slotWithExperiment(
            String salt,
            Variant defaultVariant,
            Variant experimentVariant,
            int rampUpPercentage,
            List<UserForcedAssignment> forcedAssignments) {
        return new Slot(
                salt,
                1,
                defaultVariant,
                List.of(new Experiment(
                        42,
                        "experiment-42",
                        List.of(experimentVariant),
                        rampUpPercentage,
                        List.of(new Shard(1, CREATED_AT)))),
                forcedAssignments);
    }

    private static Variant variant(int id, AlgorithmMetadata algorithm) {
        return new Variant(id, algorithm, CREATED_AT, false, id == 1, 100);
    }

    private static String findKey(VariantAssigner assigner, int expectedVariantId) {
        for (int index = 0; index < 100_000; index++) {
            String key = "customer-" + index;
            if (assigner.assign(key).variantId() == expectedVariantId) {
                return key;
            }
        }
        throw new AssertionError("Could not find assignment key for variant " + expectedVariantId);
    }

    private static final class TestStateSource implements ExperimentManagementStateSource {
        private final Map<String, Slot> slots;
        private final Map<String, AtomicInteger> requests = new LinkedHashMap<>();
        private final AtomicBoolean closed = new AtomicBoolean();

        private TestStateSource(Map<String, Slot> slots) {
            this.slots = Map.copyOf(slots);
            slots.keySet().forEach(slot -> requests.put(slot, new AtomicInteger()));
        }

        @Override
        public Slot getDefaultVariantAndActiveExperiments(String slotName) {
            requests.get(slotName).incrementAndGet();
            return slots.get(slotName);
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    private static final class CopyingDownloadClient implements AlgorithmDownloadClient {
        private final AtomicInteger jarDownloads = new AtomicInteger();
        private final AtomicInteger parameterDownloads = new AtomicInteger();
        private final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public void downloadAlgorithmJar(AlgorithmMetadata algorithm, Path destination) {
            jarDownloads.incrementAndGet();
            copy(Path.of(java.net.URI.create(algorithm.absoluteS3AlgorithmJarPath())), destination);
        }

        @Override
        public void downloadAlgorithmParameter(AlgorithmMetadata algorithm, Path destination) {
            parameterDownloads.incrementAndGet();
            copy(Path.of(java.net.URI.create(algorithm.absoluteS3AlgorithmParameterPath())), destination);
        }

        private static void copy(Path source, Path destination) {
            try {
                Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException error) {
                throw new RuntimeException(error);
            }
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    public static final class RootFactory implements SimpleRankerFactory<String, String> {
        private static final List<ExecutionContext> observedContexts = new ArrayList<>();

        @Override
        @SuppressWarnings("removal")
        public Ranker<String, String> apply(Optional<JsonNode> hyperparameter) {
            throw new AssertionError("Execution-context factory method must be used");
        }

        @Override
        public Ranker<String, String> create(
                ExecutionContext executionContext,
                Optional<LocalStateStorage> localStateStorage,
                Optional<JsonNode> hyperparameter) {
            observedContexts.add(executionContext);
            return new TaggedRanker("default-root");
        }
    }

    public static final class ParameterizedRootFactory implements CompositeAlgorithmFactory<Ranker<String, String>> {
        private static final List<ExecutionContext> observedContexts = new ArrayList<>();
        private static final List<Set<String>> observedParameterEntries = new ArrayList<>();

        @Override
        public Ranker<String, String> create(
                ExecutionContext executionContext,
                Optional<LocalStateStorage> localStateStorage,
                Optional<JsonNode> hyperparameters,
                Map<String, InputStream> parameters,
                AlgorithmDependencies dependencies) {
            observedContexts.add(executionContext);
            observedParameterEntries.add(Set.copyOf(parameters.keySet()));
            assertTrue(dependencies.isEmpty());
            return new TaggedRanker("treatment-root");
        }
    }

    public static final class CompositeRootFactory implements CompositeAlgorithmFactory<Ranker<String, String>> {
        private static final List<ExecutionContext> observedContexts = new ArrayList<>();
        private static final List<Set<String>> observedDependencyNames = new ArrayList<>();

        @Override
        public Ranker<String, String> create(
                ExecutionContext executionContext,
                Optional<LocalStateStorage> localStateStorage,
                Optional<JsonNode> hyperparameters,
                Map<String, InputStream> parameters,
                AlgorithmDependencies dependencies) {
            observedContexts.add(executionContext);
            String name = dependencies.asMap().get("policy-slot")
                    .algorithmDefinition()
                    .algorithmId()
                    .algorithmName();
            observedDependencyNames.add(Set.of(name));
            return new TaggedRanker(name);
        }
    }

    public static final class LeafFactory implements SimpleRankerFactory<String, String> {
        @Override
        @SuppressWarnings("removal")
        public Ranker<String, String> apply(Optional<JsonNode> hyperparameter) {
            return new TaggedRanker("leaf");
        }
    }

    public static final class TopKRootFactory implements SimpleAlgorithmFactory<TopK<String, String>> {
        @Override
        @SuppressWarnings("removal")
        public TopK<String, String> apply(Optional<JsonNode> hyperparameter) {
            return request -> TopKResponse.newResponse(List.of());
        }
    }

    public static final class BulkScorerRootFactory implements SimpleAlgorithmFactory<BulkScorer<String, String>> {
        @Override
        @SuppressWarnings("removal")
        public BulkScorer<String, String> apply(Optional<JsonNode> hyperparameter) {
            return new BulkScorer<>() {
                @Override
                public BulkScoreResponse<String> score(RankingRequest<String, String> request) {
                    return BulkScoreResponse.of(
                            List.of(), FeatureStoreResponseContainer.empty(), Map.of("tag", "scorer"));
                }
            };
        }
    }

    public static final class ThemedTopKRootFactory implements SimpleAlgorithmFactory<ThemedTopK<String, String>> {
        @Override
        @SuppressWarnings("removal")
        public ThemedTopK<String, String> apply(Optional<JsonNode> hyperparameter) {
            return request -> ThemedTopKResponse.newResponse(
                    "theme", List.of(), Map.of("title", "Recommendations"));
        }
    }

    public static final class FailingCloseFactory implements SimpleRankerFactory<String, String> {
        @Override
        @SuppressWarnings("removal")
        public Ranker<String, String> apply(Optional<JsonNode> hyperparameter) {
            return new Ranker<>() {
                @Override
                public RankingResponse<String> rank(RankingRequest<String, String> request) {
                    return RankingResponse.newResponse(List.of());
                }

                @Override
                public void close() {
                    throw new IllegalStateException("algorithm cleanup failed");
                }
            };
        }
    }

    public static final class CloseTrackingRankerFactory implements SimpleRankerFactory<String, String> {
        @Override
        @SuppressWarnings("removal")
        public Ranker<String, String> apply(Optional<JsonNode> hyperparameter) {
            return new CloseTrackingRanker();
        }
    }

    private static final class CloseTrackingRanker implements Ranker<String, String> {
        private int closeCalls;
        private Thread closedOn;

        @Override
        public RankingResponse<String> rank(RankingRequest<String, String> request) {
            return RankingResponse.newResponse(List.of());
        }

        @Override
        public void close() {
            closeCalls++;
            closedOn = Thread.currentThread();
        }
    }

    private record TaggedRanker(String tag) implements Ranker<String, String> {
        @Override
        public RankingResponse<String> rank(RankingRequest<String, String> request) {
            return RankingResponse.newResponse(List.of(), Map.of("tag", tag));
        }
    }
}
