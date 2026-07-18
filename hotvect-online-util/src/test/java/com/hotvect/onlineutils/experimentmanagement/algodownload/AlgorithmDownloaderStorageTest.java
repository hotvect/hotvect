package com.hotvect.onlineutils.experimentmanagement.algodownload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.execution.ExecutionContext;
import com.hotvect.api.execution.InputSemantic;
import com.hotvect.onlineutils.experimentmanagement.models.AlgorithmMetadata;
import com.hotvect.onlineutils.hotdeploy.AlgorithmInstanceFactory;
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
    void keepsAlgorithmJarsInScratchAndExposesStorageRequirement() throws Exception {
        Path scratch = tempDir.resolve("scratch");
        Path featureState = tempDir.resolve("feature-state");
        RecordingDownloadClient client = new RecordingDownloadClient();
        AlgorithmDownloader downloader = downloader(client, scratch, Optional.of(featureState));

        AlgorithmInstanceFactory factory = downloader.downloadAlgorithmJar(metadata());

        assertEquals(scratch.toAbsolutePath(), client.jarDestination.getParent());
        assertTrue(Files.exists(client.jarDestination));
        assertTrue(factory.requiresLocalStateStorage("local-state-storage-test"));
    }

    @Test
    void routesFlaggedParametersDirectlyToFeatureStateAndCleansThem() throws Exception {
        Path featureState = tempDir.resolve("feature-state");
        RecordingDownloadClient client = new RecordingDownloadClient();
        AlgorithmDownloader downloader = downloader(client, tempDir.resolve("scratch"), Optional.of(featureState));

        downloader.loadAlgorithmInstance(metadata(), new StubFactory(true), Map.of());

        assertEquals(featureState.resolve("downloads").toAbsolutePath(), client.parameterDestination.getParent());
        assertTrue(client.parameterDestination.getFileName().toString().endsWith(".part"));
        assertFalse(Files.exists(client.parameterDestination));
    }

    @Test
    void keepsLegacyParametersInScratch() throws Exception {
        Path scratch = tempDir.resolve("scratch");
        RecordingDownloadClient client = new RecordingDownloadClient();
        AlgorithmDownloader downloader = downloader(client, scratch, Optional.of(tempDir.resolve("feature-state")));

        downloader.loadAlgorithmInstance(metadata(), new StubFactory(false), Map.of());

        assertEquals(scratch.toAbsolutePath(), client.parameterDestination.getParent());
    }

    @Test
    void rejectsFlaggedAlgorithmBeforeDownloadingParametersWhenLocalStateStorageIsUnavailable() throws Exception {
        RecordingDownloadClient client = new RecordingDownloadClient();
        AlgorithmDownloader downloader = downloader(client, tempDir.resolve("scratch"), Optional.empty());

        assertThrows(
                UnsupportedRuntimeStorageException.class,
                () -> downloader.loadAlgorithmInstance(metadata(), new StubFactory(true), Map.of()));
        assertEquals(0, client.parameterDownloads);
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

        assertThrows(
                RuntimeException.class,
                () -> downloader.loadAlgorithmInstance(metadata(), new StubFactory(true), Map.of()));

        assertFalse(Files.exists(client.parameterDestination));
    }

    @Test
    void cleansPublishedParametersAfterFailedLoad() throws Exception {
        Path featureState = tempDir.resolve("feature-state");
        RecordingDownloadClient client = new RecordingDownloadClient();
        AlgorithmDownloader downloader = downloader(client, tempDir.resolve("scratch"), Optional.of(featureState));

        assertThrows(
                RuntimeException.class,
                () -> downloader.loadAlgorithmInstance(metadata(), new StubFactory(true, true), Map.of()));

        try (var files = Files.list(featureState.resolve("downloads"))) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void oldConstructorRemainsScratchOnly() throws Exception {
        Path scratch = tempDir.resolve("scratch");
        RecordingDownloadClient client = new RecordingDownloadClient();
        AlgorithmDownloader downloader = new AlgorithmDownloader(
                client,
                scratch.toString(),
                getClass().getClassLoader(),
                true);

        downloader.loadAlgorithmInstance(metadata(), new StubFactory(false), Map.of());

        assertEquals(scratch.toAbsolutePath(), client.parameterDestination.getParent());
    }

    private AlgorithmDownloader downloader(
            RecordingDownloadClient client,
            Path scratch,
            Optional<Path> featureStateRoot) {
        return new AlgorithmDownloader(
                client,
                scratch,
                featureStateRoot,
                getClass().getClassLoader(),
                true);
    }

    private static AlgorithmMetadata metadata() {
        return new AlgorithmMetadata("test", "1", "p1", "/test.jar", "/parameters.zip");
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
    }

    private static final class StubFactory extends AlgorithmInstanceFactory {
        private final boolean requiresLocalStateStorage;
        private final boolean failLoad;

        private StubFactory(boolean requiresLocalStateStorage) {
            this(requiresLocalStateStorage, false);
        }

        private StubFactory(boolean requiresLocalStateStorage, boolean failLoad) {
            super(
                    AlgorithmDownloaderStorageTest.class.getClassLoader(),
                    ExecutionContext.realtime(InputSemantic.ONLINE),
                    true);
            this.requiresLocalStateStorage = requiresLocalStateStorage;
            this.failLoad = failLoad;
        }

        @Override
        public boolean requiresLocalStateStorage(String algorithmName) {
            return requiresLocalStateStorage;
        }

        @Override
        public <ALGO extends com.hotvect.api.algorithms.Algorithm> AlgorithmInstance<ALGO> load(
                String algorithmName,
                File parameterFile,
                Map<String, AlgorithmInstance<?>> dependencyOverrides) {
            if (failLoad) {
                throw new RuntimeException("load failed");
            }
            return null;
        }
    }
}
