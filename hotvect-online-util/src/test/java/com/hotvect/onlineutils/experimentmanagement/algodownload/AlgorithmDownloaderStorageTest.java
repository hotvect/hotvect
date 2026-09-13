package com.hotvect.onlineutils.experimentmanagement.algodownload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.algodefinition.AlgorithmDependencies;
import com.hotvect.api.algodefinition.AlgorithmId;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algodefinition.AlgorithmRuntimeId;
import com.hotvect.api.algodefinition.common.SimpleAlgorithmFactory;
import com.hotvect.api.algorithms.Algorithm;
import com.hotvect.api.execution.ExecutionContext;
import com.hotvect.api.execution.InputSemantic;
import com.hotvect.onlineutils.experimentmanagement.models.AlgorithmMetadata;
import com.hotvect.onlineutils.hotdeploy.AlgorithmGraph;
import com.hotvect.onlineutils.hotdeploy.AlgorithmGraphDependencies;
import com.hotvect.onlineutils.hotdeploy.AlgorithmArtifactProvider;
import com.hotvect.onlineutils.hotdeploy.AlgorithmInstanceFactory;
import com.hotvect.onlineutils.hotdeploy.SharedNodeInterner;
import com.hotvect.onlineutils.util.Closeables;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AlgorithmDownloaderStorageTest {
    @TempDir
    Path tempDir;

    @Test
    void keepsAlgorithmJarsInScratch() throws Exception {
        Path scratch = tempDir.resolve("scratch");
        Path featureState = tempDir.resolve("feature-state");
        RecordingDownloadClient client = new RecordingDownloadClient();
        AlgorithmDownloader downloader = downloader(client, scratch, Optional.of(featureState));
        Path stagedJar;

        try (DownloadedAlgorithmArtifact artifact = downloader.downloadAlgorithmArtifact(metadata())) {
            stagedJar = client.jarDestination;

            assertEquals(scratch.toAbsolutePath(), client.jarDestination.getParent());
            assertTrue(Files.exists(stagedJar));
        }

        assertTrue(Files.notExists(stagedJar));
    }

    @Test
    void stagesParametersUnderTheConfiguredStateRoot() throws Exception {
        Path featureState = tempDir.resolve("feature-state");
        RecordingDownloadClient client = new RecordingDownloadClient();
        AlgorithmDownloader downloader = downloader(client, tempDir.resolve("scratch"), Optional.of(featureState));

        try (DownloadedAlgorithmArtifact artifact = artifact(new StubFactory());
                AlgorithmGraph<?> graph = loadGraph(downloader, metadata(), artifact)) {
            assertEquals(featureState.resolve("downloads").toAbsolutePath(), client.parameterDestination.getParent());
            assertTrue(client.parameterDestination.getFileName().toString().endsWith(".part"));
            assertFalse(Files.exists(client.parameterDestination));
        }
    }

    @Test
    void defaultsStateRootBelowScratch() throws Exception {
        Path scratch = tempDir.resolve("scratch");
        RecordingDownloadClient client = new RecordingDownloadClient();
        AlgorithmDownloader downloader = downloader(client, scratch, Optional.empty());

        try (DownloadedAlgorithmArtifact artifact = artifact(new StubFactory());
                AlgorithmGraph<?> graph = loadGraph(downloader, metadata(), artifact)) {
            assertEquals(
                    scratch.resolve("algorithm-state/downloads").toAbsolutePath(),
                    client.parameterDestination.getParent());
        }
    }

    @Test
    void ignoresTheRemovedStorageRequirement() throws Exception {
        RecordingDownloadClient client = new RecordingDownloadClient();
        AlgorithmDownloader downloader = downloader(client, tempDir.resolve("scratch"), Optional.empty());

        try (DownloadedAlgorithmArtifact artifact = artifact(new StubFactory(true));
                AlgorithmGraph<?> graph = loadGraph(downloader, metadata(), artifact)) {
            assertEquals(1, client.parameterDownloads);
        }
    }

    @Test
    void cleansPartialParametersAfterFailedDownload() throws Exception {
        RecordingDownloadClient client = new RecordingDownloadClient() {
            @Override
            public void downloadAlgorithmParameter(AlgorithmMetadata algorithm, Path destination) {
                super.downloadAlgorithmParameter(algorithm, destination);
                throw new RuntimeException("download failed");
            }
        };
        AlgorithmDownloader downloader = downloader(
                client,
                tempDir.resolve("scratch"),
                Optional.of(tempDir.resolve("feature-state")));

        try (DownloadedAlgorithmArtifact artifact = artifact(new StubFactory())) {
            assertThrows(
                    RuntimeException.class,
                    () -> loadGraph(downloader, metadata(), artifact));
        }

        assertTrue(Files.notExists(client.parameterDestination));
    }

    @Test
    void cleansPublishedParametersAfterFailedLoad() throws Exception {
        Path featureState = tempDir.resolve("feature-state");
        RecordingDownloadClient client = new RecordingDownloadClient();
        AlgorithmDownloader downloader = downloader(client, tempDir.resolve("scratch"), Optional.of(featureState));

        try (DownloadedAlgorithmArtifact artifact = artifact(new StubFactory(false, true))) {
            assertThrows(
                    RuntimeException.class,
                    () -> loadGraph(downloader, metadata(), artifact));
        }

        try (var files = Files.list(featureState.resolve("downloads"))) {
            assertEquals(0, files.count());
        }
    }

    private DownloadedAlgorithmArtifact artifact(final AlgorithmInstanceFactory factory) throws Exception {
        Path stagedJar = Files.createTempFile(tempDir, "algorithm-", ".jar");
        return new DownloadedAlgorithmArtifact(factory, stagedJar);
    }

    @Test
    void loadsParameterlessAlgorithmWithoutDownloadingParameters() throws Exception {
        RecordingDownloadClient client = new RecordingDownloadClient();
        AlgorithmDownloader downloader = downloader(client, tempDir.resolve("scratch"), Optional.empty());
        StubFactory factory = new StubFactory(false);

        try (DownloadedAlgorithmArtifact artifact = artifact(factory);
                AlgorithmGraph<?> graph = loadGraph(downloader, parameterlessMetadata(), artifact)) {
            assertNull(factory.parameterFile);
        }

        assertEquals(0, client.parameterDownloads);
    }

    @Test
    void loadsParameterlessAlgorithmWhenLegacyRequirementIsPresent() throws Exception {
        RecordingDownloadClient client = new RecordingDownloadClient();
        AlgorithmDownloader downloader = downloader(client, tempDir.resolve("scratch"), Optional.empty());

        try (DownloadedAlgorithmArtifact artifact = artifact(new StubFactory(true));
                AlgorithmGraph<?> graph = loadGraph(downloader, parameterlessMetadata(), artifact)) {
        }
        assertEquals(0, client.parameterDownloads);
    }

    @Test
    void permitsArchiveIdentityToBeResolvedFromTheArchive() {
        assertThrows(IllegalArgumentException.class,
                () -> new AlgorithmMetadata("test", "1", "p1", "/test.jar", null));
        AlgorithmMetadata unresolved = new AlgorithmMetadata(
                "test", "1", null, "/test.jar", "/parameters.zip");
        assertTrue(unresolved.hasParameter());
        assertNull(unresolved.latestAlgorithmParameter());
    }

    private AlgorithmDownloader downloader(
            RecordingDownloadClient client,
            Path scratch,
            Optional<Path> localStateRoot) {
        return new AlgorithmDownloader(
                client,
                scratch,
                getClass().getClassLoader(),
                new AlgorithmDownloader.Options(
                        InputSemantic.ONLINE,
                        true,
                        false,
                        localStateRoot));
    }

    private static AlgorithmGraph<?> loadGraph(
            AlgorithmDownloader downloader,
            AlgorithmMetadata metadata,
            DownloadedAlgorithmArtifact artifact) throws Exception {
        try (DownloadedAlgorithmParameters parameters = metadata.hasParameter()
                ? downloader.downloadAlgorithmParameters(metadata)
                : null) {
            return downloader.loadAlgorithmGraph(
                    metadata,
                    artifact,
                    parameters == null ? null : parameters.file(),
                    AlgorithmDependencies.empty(),
                    AlgorithmGraphDependencies.empty(),
                    "test-source",
                    Map.of(),
                    (identity, graphFactory) -> {
                        AlgorithmGraph<?> graph = graphFactory.get();
                        return new SharedNodeInterner.SharedNode() {
                            @Override
                            public AlgorithmInstance<?> instance() {
                                return graph.root();
                            }

                            @Override
                            public AlgorithmRuntimeId runtimeId() {
                                return graph.runtimeId();
                            }

                            @Override
                            public void close() {
                                Closeables.closeAll("Could not close shared test graph", graph);
                            }
                        };
                    },
                    ExecutionContext.realtime(InputSemantic.ONLINE));
        }
    }

    private static AlgorithmMetadata metadata() {
        return new AlgorithmMetadata("test", "1", "p1", "/test.jar", "/parameters.zip");
    }

    private static AlgorithmMetadata parameterlessMetadata() {
        return new AlgorithmMetadata("test", "1", null, "/test.jar", null);
    }

    private static AlgorithmDefinition definition(boolean includesLegacyStorageRequirement) {
        var rawDefinition = JsonNodeFactory.instance.objectNode();
        if (includesLegacyStorageRequirement) {
            rawDefinition.put("requires_local_state_storage", true);
        }
        return new AlgorithmDefinition(
                rawDefinition,
                new AlgorithmId("test", "1"),
                Map.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                StubAlgorithmFactory.class.getName(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static class RecordingDownloadClient implements AlgorithmDownloadClient {
        private Path jarDestination;
        private Path parameterDestination;
        private int parameterDownloads;

        @Override
        public void downloadAlgorithmJar(AlgorithmMetadata algorithm, Path destination) {
            try {
                jarDestination = destination.toAbsolutePath();
                Files.writeString(destination, "jar");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void downloadAlgorithmParameter(AlgorithmMetadata algorithm, Path destination) {
            try {
                parameterDownloads++;
                parameterDestination = destination.toAbsolutePath();
                Files.writeString(destination, "parameters");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void close() {
        }
    }

    private static final class StubFactory extends AlgorithmInstanceFactory {
        private final boolean includesLegacyStorageRequirement;
        private final boolean failLoad;
        private File parameterFile;

        private StubFactory() {
            this(false, false);
        }

        private StubFactory(boolean includesLegacyStorageRequirement) {
            this(includesLegacyStorageRequirement, false);
        }

        private StubFactory(boolean includesLegacyStorageRequirement, boolean failLoad) {
            super(
                    AlgorithmDownloaderStorageTest.class.getClassLoader(),
                    new Options(
                            InputSemantic.ONLINE,
                            true,
                            false,
                            Optional.empty()));
            this.includesLegacyStorageRequirement = includesLegacyStorageRequirement;
            this.failLoad = failLoad;
        }

        @Override
        public AlgorithmDefinition readAlgorithmDefinition(String algorithmName) {
            return definition(includesLegacyStorageRequirement);
        }

        @Override
        public <ALGO extends Algorithm> AlgorithmGraph<ALGO> loadGraphFromArtifactSet(
                AlgorithmDefinition algorithmDefinition,
                File parameterFile,
                AlgorithmDependencies dependencyOverrides,
                AlgorithmGraphDependencies slotBindings,
                String rootProviderIdentity,
                Map<AlgorithmId, AlgorithmArtifactProvider> sharedProviders,
                SharedNodeInterner sharedNodeInterner,
                ExecutionContext executionContext) {
            this.parameterFile = parameterFile;
            if (failLoad) {
                throw new RuntimeException("load failed");
            }
            return super.loadGraphFromArtifactSet(
                    algorithmDefinition,
                    null,
                    dependencyOverrides,
                    slotBindings,
                    rootProviderIdentity,
                    sharedProviders,
                    sharedNodeInterner,
                    ExecutionContext.realtime(InputSemantic.ONLINE));
        }
    }

    public static final class StubAlgorithmFactory implements SimpleAlgorithmFactory<StubAlgorithm> {
        @Override
        public StubAlgorithm apply(final Optional<JsonNode> hyperparameter) {
            return new StubAlgorithm();
        }
    }

    private static final class StubAlgorithm implements Algorithm {
    }
}
