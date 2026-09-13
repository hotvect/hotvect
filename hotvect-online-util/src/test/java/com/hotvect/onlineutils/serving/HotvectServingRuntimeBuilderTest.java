package com.hotvect.onlineutils.serving;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.reflect.TypeToken;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.algorithms.ThemedTopK;
import com.hotvect.api.algorithms.TopK;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.ranking.RankingResponse;
import com.hotvect.api.data.topk.ThemedTopKResponse;
import com.hotvect.api.data.topk.TopKRequest;
import com.hotvect.api.data.topk.TopKResponse;
import com.hotvect.onlineutils.experimentmanagement.algodownload.AlgorithmDownloadClient;
import com.hotvect.onlineutils.experimentmanagement.httpclient.ExperimentManagementServiceClient;
import com.hotvect.onlineutils.experimentmanagement.models.AlgorithmMetadata;
import com.hotvect.onlineutils.experimentmanagement.models.Slot;
import com.hotvect.onlineutils.experimentmanagement.models.Variant;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HotvectServingRuntimeBuilderTest {
    @TempDir
    Path tempDir;

    @Test
    void buildsLoadsArtifactsAndClosesOwnedClients() throws Exception {
        ArtifactFixture artifact = createArtifactFixture();
        RecordingDownloadClient downloadClient = new RecordingDownloadClient(artifact);
        RecordingEmsClient emsClient = new RecordingEmsClient(slot(artifact.metadata()), false);

        try (HotvectServingRuntime runtime = runtimeBuilder(emsClient, downloadClient).build()) {
            ServingRuntimeStatus initialStatus = runtime.status();
            assertTrue(initialStatus.ready());
            assertEquals(1, initialStatus.generation());
            RankingRequest<String, String> request = RankingRequest.ofAvailableActions(
                    "request-1",
                    "shared",
                    List.of());
            AlgorithmExecution<RankingResponse<String>> execution = runtime.invoke(
                    "search",
                    "customer-1",
                    request);
            assertEquals(List.of(), execution.result().decisions());
            assertEquals("root-slot", execution.selection().rootSlot());
            assertEquals(
                    "1",
                    execution.selection().assignments().get("root-slot").variantId());
            AlgorithmExecution<RankingResponse<String>> secondExecution = runtime.invoke(
                    "search",
                    "customer-1",
                    request);
            assertSame(execution.selection().runtimeId(), secondExecution.selection().runtimeId());
            assertEquals(1, downloadClient.jarDownloads);
            assertEquals(1, downloadClient.parameterDownloads);
            runtime.refreshNow();
            assertEquals(initialStatus.generation() + 1, runtime.status().generation());
            assertEquals(1, downloadClient.jarDownloads);
            assertEquals(1, downloadClient.parameterDownloads);
        }

        assertTrue(emsClient.closed);
        assertTrue(downloadClient.closed);
    }

    @Test
    void stagesParametersUnderTheConfiguredLocalStateRoot() throws Exception {
        ArtifactFixture artifact = createArtifactFixture();
        RecordingDownloadClient downloadClient = new RecordingDownloadClient(artifact);
        RecordingEmsClient emsClient = new RecordingEmsClient(slot(artifact.metadata()), false);
        Path localStateRoot = tempDir.resolve("local-state");

        try (HotvectServingRuntime ignored = runtimeBuilder(emsClient, downloadClient)
                .localStateRoot(localStateRoot)
                .build()) {
            assertEquals(
                    localStateRoot.toAbsolutePath().resolve("downloads"),
                    downloadClient.parameterDestination.getParent());
        }
    }

    @Test
    void defaultsRuntimeStateBelowScratch() throws Exception {
        ArtifactFixture artifact = createStorageUsingArtifactFixture();
        RecordingDownloadClient downloadClient = new RecordingDownloadClient(artifact);
        RecordingEmsClient emsClient = new RecordingEmsClient(slot(artifact.metadata()), false);

        try (HotvectServingRuntime ignored = runtimeBuilder(emsClient, downloadClient).build();
                var directories = Files.list(tempDir.resolve("scratch/algorithm-state"))) {
            assertTrue(directories.anyMatch(path -> path.getFileName().toString()
                    .startsWith("builder-ranker-1-state-")));
        }
    }

    @Test
    void invokesTopKRequestsDirectly() throws Exception {
        ArtifactFixture artifact = createTopKArtifactFixture(false);
        RecordingDownloadClient downloadClient = new RecordingDownloadClient(artifact);
        RecordingEmsClient emsClient = new RecordingEmsClient(slot(artifact.metadata()), false);

        try (HotvectServingRuntime runtime = runtimeBuilder(
                emsClient,
                downloadClient,
                topKServingSlot("root-slot", "recommendations", false))
                .build()) {
            TopKRequest<String> request = new TopKRequest<>(
                    "request-1",
                    Instant.parse("2026-01-01T00:00:00Z"),
                    "shared",
                    10);

            AlgorithmExecution<TopKResponse<String>> execution = runtime.invoke(
                    "recommendations",
                    "customer-1",
                    request);

            assertEquals(List.of(), execution.result().decisions());
            assertEquals("root-slot", execution.selection().rootSlot());

            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> runtime.invokeThemedTopK("recommendations", "customer-1", request));
            assertEquals(
                    "Touchpoint recommendations serves TopK, not ThemedTopK",
                    failure.getMessage());
        }
    }

    @Test
    void invokesThemedTopKThroughGeneralAndTypedApis() throws Exception {
        ArtifactFixture artifact = createTopKArtifactFixture(true);
        RecordingDownloadClient downloadClient = new RecordingDownloadClient(artifact);
        RecordingEmsClient emsClient = new RecordingEmsClient(slot(artifact.metadata()), false);

        try (HotvectServingRuntime runtime = runtimeBuilder(
                emsClient,
                downloadClient,
                topKServingSlot("root-slot", "recommendations", true))
                .build()) {
            TopKRequest<String> request = new TopKRequest<>(
                    "request-1",
                    Instant.parse("2026-01-01T00:00:00Z"),
                    "shared",
                    10);

            AlgorithmExecution<TopKResponse<String>> generalExecution = runtime.invoke(
                    "recommendations",
                    "customer-1",
                    request);
            ThemedTopKResponse<?> response = assertInstanceOf(
                    ThemedTopKResponse.class,
                    generalExecution.result());
            AlgorithmExecution<ThemedTopKResponse<String>> themedExecution = runtime.invokeThemedTopK(
                    "recommendations",
                    "customer-1",
                    request);

            assertEquals("action-list-1", response.getActionListId());
            assertEquals("sale", response.getActionListMetadata().get("theme"));
            assertEquals(response, themedExecution.result());
        }
    }

    @Test
    void rejectsARequestTypeThatDoesNotMatchTheTouchpoint() throws Exception {
        ArtifactFixture artifact = createArtifactFixture();
        RecordingDownloadClient downloadClient = new RecordingDownloadClient(artifact);
        RecordingEmsClient emsClient = new RecordingEmsClient(slot(artifact.metadata()), false);

        try (HotvectServingRuntime runtime = runtimeBuilder(emsClient, downloadClient).build()) {
            TopKRequest<String> request = new TopKRequest<>(
                    "request-1",
                    Instant.parse("2026-01-01T00:00:00Z"),
                    "shared",
                    10);

            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> runtime.invoke("search", "customer-1", request));

            assertEquals("Touchpoint search serves Ranker, not TopK", failure.getMessage());
        }
    }

    @Test
    void closesOwnedClientsAfterStartupFailure() {
        RecordingDownloadClient downloadClient = new RecordingDownloadClient(null);
        RecordingEmsClient emsClient = new RecordingEmsClient(null, true);

        assertThrows(Exception.class, () -> runtimeBuilder(emsClient, downloadClient).build());

        assertTrue(emsClient.closed);
        assertTrue(downloadClient.closed);
        assertEquals(0, downloadClient.jarDownloads);
        assertEquals(0, downloadClient.parameterDownloads);
    }

    @Test
    void closesTheEmsClientWhenDownloadClientConstructionFails() {
        RecordingEmsClient emsClient = new RecordingEmsClient(null, false);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> configuredBuilder()
                        .emsClientFactory((emsUri, connectTimeout, readTimeout, tokenSupplier) -> emsClient)
                        .algorithmDownloadClientFactory(() -> {
                            throw new IllegalStateException("download client construction failed");
                        })
                        .build());

        assertEquals("download client construction failed", failure.getMessage());
        assertTrue(emsClient.closed);
    }

    @Test
    void requiresAtLeastOneRootSlot() {
        HotvectServingRuntime.Builder builder = HotvectServingRuntime.builder()
                .ems(URI.create("http://ems.invalid/"));

        assertThrows(IllegalStateException.class, builder::build);
    }

    @Test
    void requiresAnExplicitParentClassLoaderWhenTheThreadContextClassLoaderIsNull() {
        Thread thread = Thread.currentThread();
        ClassLoader originalClassLoader = thread.getContextClassLoader();
        HotvectServingRuntime.Builder builder;
        try {
            thread.setContextClassLoader(null);
            builder = HotvectServingRuntime.builder();
        } finally {
            thread.setContextClassLoader(originalClassLoader);
        }

        IllegalStateException error = assertThrows(IllegalStateException.class, builder::build);

        assertEquals(
                "No thread context class loader was available when this builder was created;"
                        + " call algorithmParentClassLoader(...) explicitly",
                error.getMessage());
    }

    @Test
    void rejectsDuplicateRootSlotNames() {
        HotvectServingRuntime.Builder builder = HotvectServingRuntime.builder()
                .ems(URI.create("http://ems.invalid/"))
                .slot(servingSlot("root-slot", "search"));

        assertThrows(
                IllegalArgumentException.class,
                () -> builder.slot(servingSlot("root-slot", "homepage")));
    }

    @Test
    void rejectsDuplicateTouchpointsAcrossRootSlots() {
        HotvectServingRuntime.Builder builder = HotvectServingRuntime.builder()
                .ems(URI.create("http://ems.invalid/"))
                .slot(servingSlot("search-slot", "search"))
                .slot(servingSlot("homepage-slot", "search"));

        assertThrows(IllegalStateException.class, builder::build);
    }

    @Test
    void requiresAtLeastOneTouchpoint() {
        assertThrows(IllegalArgumentException.class, () -> servingSlot("root-slot"));
    }

    @Test
    void rejectsSubMillisecondRefreshPeriodBeforeCreatingClients() {
        HotvectServingRuntime.Builder builder = HotvectServingRuntime.builder();

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> builder.refreshPeriod(Duration.ofNanos(999_999)));

        assertEquals("refreshPeriod must be at least one millisecond", error.getMessage());
    }

    private HotvectServingRuntime.Builder runtimeBuilder(
            RecordingEmsClient emsClient,
            RecordingDownloadClient downloadClient) {
        return runtimeBuilder(emsClient, downloadClient, servingSlot("root-slot", "search"));
    }

    private HotvectServingRuntime.Builder runtimeBuilder(
            RecordingEmsClient emsClient,
            RecordingDownloadClient downloadClient,
            ServingSlot servingSlot) {
        return configuredBuilder(servingSlot)
                .emsClientFactory((emsUri, connectTimeout, readTimeout, tokenSupplier) -> emsClient)
                .algorithmDownloadClientFactory(() -> downloadClient);
    }

    private HotvectServingRuntime.Builder configuredBuilder() {
        return configuredBuilder(servingSlot("root-slot", "search"));
    }

    private HotvectServingRuntime.Builder configuredBuilder(ServingSlot servingSlot) {
        return HotvectServingRuntime.builder()
                .ems(URI.create("http://ems.invalid/"))
                .slot(servingSlot)
                .scratchDirectory(tempDir.resolve("scratch"))
                .algorithmParentClassLoader(getClass().getClassLoader());
    }

    private static ServingSlot servingSlot(String name, String... touchpoints) {
        return ServingSlot.builder(name, new TypeToken<Ranker<String, String>>() {})
                .touchpoints(Set.of(touchpoints))
                .build();
    }

    private static ServingSlot topKServingSlot(
            String name,
            String touchpoint,
            boolean themed) {
        return themed
                ? ServingSlot.builder(name, new TypeToken<ThemedTopK<String, String>>() {})
                        .touchpoints(Set.of(touchpoint))
                        .build()
                : ServingSlot.builder(name, new TypeToken<TopK<String, String>>() {})
                        .touchpoints(Set.of(touchpoint))
                        .build();
    }

    private static Slot slot(AlgorithmMetadata metadata) {
        return new Slot(
                "test-salt",
                100,
                new Variant(1, metadata, Instant.now(), false, true, 100),
                List.of(),
                List.of());
    }

    private ArtifactFixture createArtifactFixture() throws Exception {
        return createArtifactFixture(
                "builder-ranker",
                """
                package fixtures;

                import com.fasterxml.jackson.databind.JsonNode;
                import com.hotvect.api.algodefinition.ranking.SimpleRankerFactory;
                import com.hotvect.api.algorithms.Ranker;
                import com.hotvect.api.data.ranking.RankingResponse;
                import java.util.List;
                import java.util.Optional;

                public final class BuilderAlgorithmFactory implements SimpleRankerFactory<String, String> {
                    @Override
                    public Ranker<String, String> apply(Optional<JsonNode> parameters) {
                        return request -> RankingResponse.newResponse(List.of());
                    }
                }
                """);
    }

    private ArtifactFixture createStorageUsingArtifactFixture() throws Exception {
        return createArtifactFixture(
                "builder-ranker",
                """
                package fixtures;

                import com.fasterxml.jackson.databind.JsonNode;
                import com.hotvect.api.algodefinition.common.SimpleAlgorithmFactory;
                import com.hotvect.api.algodefinition.storage.LocalStateStorage;
                import com.hotvect.api.algorithms.Ranker;
                import com.hotvect.api.data.ranking.RankingResponse;
                import com.hotvect.api.execution.ExecutionContext;
                import java.util.List;
                import java.util.Optional;

                public final class BuilderAlgorithmFactory implements SimpleAlgorithmFactory<Ranker<String, String>> {
                    @Override
                    public Ranker<String, String> apply(Optional<JsonNode> parameters) {
                        throw new AssertionError("Runtime-aware factory overload should be used");
                    }

                    @Override
                    public Ranker<String, String> create(
                            ExecutionContext executionContext,
                            Optional<LocalStateStorage> localStateStorage,
                            Optional<JsonNode> parameters) {
                        localStateStorage.orElseThrow().allocateDirectory();
                        return request -> RankingResponse.newResponse(List.of());
                    }
                }
                """);
    }

    private ArtifactFixture createTopKArtifactFixture(boolean themed) throws Exception {
        String algorithmName = themed ? "builder-themed-topk" : "builder-topk";
        String source = themed
                ? """
                package fixtures;

                import com.fasterxml.jackson.databind.JsonNode;
                import com.hotvect.api.algodefinition.common.SimpleAlgorithmFactory;
                import com.hotvect.api.algorithms.ThemedTopK;
                import com.hotvect.api.data.topk.ThemedTopKResponse;
                import java.util.List;
                import java.util.Map;
                import java.util.Optional;

                public final class BuilderAlgorithmFactory
                        implements SimpleAlgorithmFactory<ThemedTopK<String, String>> {
                    @Override
                    public ThemedTopK<String, String> apply(Optional<JsonNode> parameters) {
                        return request -> ThemedTopKResponse.newResponse(
                                "action-list-1",
                                List.of(),
                                Map.of("theme", "sale"));
                    }
                }
                """
                : """
                package fixtures;

                import com.fasterxml.jackson.databind.JsonNode;
                import com.hotvect.api.algodefinition.common.SimpleAlgorithmFactory;
                import com.hotvect.api.algorithms.TopK;
                import com.hotvect.api.data.topk.TopKResponse;
                import java.util.List;
                import java.util.Optional;

                public final class BuilderAlgorithmFactory implements SimpleAlgorithmFactory<TopK<String, String>> {
                    @Override
                    public TopK<String, String> apply(Optional<JsonNode> parameters) {
                        return request -> TopKResponse.newResponse(List.of());
                    }
                }
                """;
        return createArtifactFixture(algorithmName, source);
    }

    private ArtifactFixture createArtifactFixture(
            String algorithmName,
            String factorySource) throws Exception {
        Path sourceRoot = tempDir.resolve("source");
        Path classes = tempDir.resolve("classes");
        Path source = sourceRoot.resolve("fixtures/BuilderAlgorithmFactory.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, factorySource);
        int exitCode = ToolProvider.getSystemJavaCompiler().run(
                null,
                null,
                null,
                "-classpath",
                System.getProperty("java.class.path"),
                "-d",
                classes.toString(),
                source.toString());
        assertEquals(0, exitCode, "fixture algorithm must compile");

        Path jar = tempDir.resolve(algorithmName + ".jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Path classFile : Files.walk(classes).filter(Files::isRegularFile).toList()) {
                String name = classes.relativize(classFile).toString().replace('\\', '/');
                output.putNextEntry(new JarEntry(name));
                output.write(Files.readAllBytes(classFile));
                output.closeEntry();
            }
            output.putNextEntry(new JarEntry(algorithmName + "-algorithm-definition.json"));
            output.write("""
                    {
                      "algorithm_name": "%s",
                      "algorithm_version": "1",
                      "algorithm_factory_classname": "fixtures.BuilderAlgorithmFactory"
                    }
                    """.formatted(algorithmName).getBytes());
            output.closeEntry();
        }

        Path parameters = tempDir.resolve(algorithmName + "-parameters.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(parameters))) {
            output.putNextEntry(new ZipEntry(algorithmName + "/algorithm-parameters.json"));
            output.write("""
                    {
                      "algorithm_name": "%s",
                      "algorithm_version": "1",
                      "parameter_id": "parameters-1",
                      "ran_at": "2026-01-01T00:00:00Z"
                    }
                    """.formatted(algorithmName).getBytes());
            output.closeEntry();
        }

        return new ArtifactFixture(
                jar,
                parameters,
                new AlgorithmMetadata(
                        algorithmName,
                        "1",
                        "parameters-1",
                        "s3://test/" + algorithmName + ".jar",
                        "s3://test/" + algorithmName + "-parameters.zip"));
    }

    private record ArtifactFixture(Path jar, Path parameters, AlgorithmMetadata metadata) {
    }

    private static final class RecordingEmsClient extends ExperimentManagementServiceClient {
        private final Slot slot;
        private final boolean fail;
        private boolean closed;

        private RecordingEmsClient(Slot slot, boolean fail) {
            super(
                    URI.create("http://ems.invalid/"),
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1),
                    () -> null);
            this.slot = slot;
            this.fail = fail;
        }

        @Override
        public Slot getDefaultVariantAndActiveExperiments(String slotName) throws IOException {
            if (fail) {
                throw new IOException("EMS startup failure");
            }
            return slot;
        }

        @Override
        public void close() {
            closed = true;
            super.close();
        }
    }

    private static final class RecordingDownloadClient implements AlgorithmDownloadClient {
        private final ArtifactFixture artifact;
        private int jarDownloads;
        private int parameterDownloads;
        private Path parameterDestination;
        private boolean closed;

        private RecordingDownloadClient(ArtifactFixture artifact) {
            this.artifact = artifact;
        }

        @Override
        public void downloadAlgorithmJar(AlgorithmMetadata algorithm, Path destination) {
            if (artifact == null) {
                throw new AssertionError("Artifact download is not expected");
            }
            copy(artifact.jar(), destination);
            jarDownloads++;
        }

        @Override
        public void downloadAlgorithmParameter(AlgorithmMetadata algorithm, Path destination) {
            if (artifact == null) {
                throw new AssertionError("Parameter download is not expected");
            }
            copy(artifact.parameters(), destination);
            parameterDestination = destination.toAbsolutePath();
            parameterDownloads++;
        }

        @Override
        public void close() {
            closed = true;
        }

        private static void copy(Path source, Path destination) {
            try {
                Files.copy(source, destination);
            } catch (IOException error) {
                throw new RuntimeException(error);
            }
        }
    }
}
