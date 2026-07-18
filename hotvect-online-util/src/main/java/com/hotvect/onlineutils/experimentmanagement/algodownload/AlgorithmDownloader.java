package com.hotvect.onlineutils.experimentmanagement.algodownload;

import static com.google.common.base.Preconditions.checkState;

import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.execution.ExecutionContext;
import com.hotvect.api.execution.InputSemantic;
import com.hotvect.onlineutils.experimentmanagement.models.AlgorithmMetadata;
import com.hotvect.onlineutils.hotdeploy.AlgorithmInstanceFactory;
import com.hotvect.onlineutils.hotdeploy.util.MalformedAlgorithmException;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AlgorithmDownloader {
    private static final Logger LOG = LoggerFactory.getLogger(AlgorithmDownloader.class);
    private static final String DOWNLOADS_DIRECTORY = "downloads";
    private static final String STATES_DIRECTORY = "states";

    private final AlgorithmDownloadClient algorithmDownloadClient;
    private final ClassLoader classLoader;
    private final Path scratchDirectory;
    private final Optional<Path> featureStateRoot;

    private final boolean strictAlgorithmVersionCheck;

    public AlgorithmDownloader(
            final AlgorithmDownloadClient algorithmDownloadClient,
            final String scratchDir,
            final ClassLoader classLoader,
            final boolean strictAlgorithmVersionCheck
        ) {
        this(
                algorithmDownloadClient,
                Path.of(scratchDir),
                Optional.empty(),
                classLoader,
                strictAlgorithmVersionCheck);
    }

    public AlgorithmDownloader(
            final AlgorithmDownloadClient algorithmDownloadClient,
            final Path scratchDirectory,
            final Optional<Path> featureStateRoot,
            final ClassLoader classLoader,
            final boolean strictAlgorithmVersionCheck
        ) {
        this.algorithmDownloadClient = algorithmDownloadClient;
        this.classLoader = classLoader;
        this.scratchDirectory = Objects.requireNonNull(scratchDirectory, "scratchDirectory").toAbsolutePath();
        this.featureStateRoot = Objects.requireNonNull(featureStateRoot, "featureStateRoot").map(Path::toAbsolutePath);
        this.strictAlgorithmVersionCheck = strictAlgorithmVersionCheck;
    }


    public AlgorithmInstanceFactory downloadAlgorithmJar(AlgorithmMetadata algorithm) {
        final String randomPrefix = UUID.randomUUID().toString();
        final String fileName = algorithm.algorithmJarFileName();
        final Path downloadDestination = scratchDirectory.resolve(randomPrefix + "-" + fileName);
        try {
            Files.createDirectories(downloadDestination.getParent());
            algorithmDownloadClient.downloadAlgorithmJar(algorithm, downloadDestination);
            checkState(downloadDestination.toFile().exists() && downloadDestination.toFile().length() > 0,
                    "File download failed");
            // Not strictly necessary but friendly to local execution
            // Delete only happens on orderly shutdown
            downloadDestination.toFile().deleteOnExit();

            return new AlgorithmInstanceFactory(
                downloadDestination.toFile(),
                classLoader,
                ExecutionContext.realtime(InputSemantic.ONLINE),
                strictAlgorithmVersionCheck,
                featureStateRoot.map(root -> root.resolve(STATES_DIRECTORY))
            );
        } catch (final Exception e) {
            attemptCleanup(downloadDestination);
            throw new RuntimeException("Algorithm Jar couldn't get downloaded : " + fileName, e);
        }
    }

    public AlgorithmInstance<?> loadAlgorithmInstance(AlgorithmMetadata algorithm,
            final AlgorithmInstanceFactory algorithmHolder,
            final Map<String, AlgorithmInstance<?>> dependencyOverrides) throws MalformedAlgorithmException {
        final String algoParameterName = algorithm.latestAlgorithmParameterFileName();
        final String randomPrefix = UUID.randomUUID().toString();
        final Path parameterDirectory = parameterDirectory(algorithm, algorithmHolder);
        final Path downloadDestination = parameterDirectory.resolve(randomPrefix + "-" + algoParameterName);
        final Path partialDestination = downloadDestination.resolveSibling(downloadDestination.getFileName() + ".part");
        try {
            Files.createDirectories(parameterDirectory);
            algorithmDownloadClient.downloadAlgorithmParameter(algorithm, partialDestination);
            checkState(partialDestination.toFile().exists() && partialDestination.toFile().length() > 0,
                    "File download failed");
            moveCompletedDownload(partialDestination, downloadDestination);
            return algorithmHolder.load(algorithm.algorithmName(), downloadDestination.toFile(), dependencyOverrides);
        } catch (IOException e) {
            throw new RuntimeException("Algorithm parameters couldn't get downloaded: " + algoParameterName, e);
        } finally {
            attemptCleanup(partialDestination);
            attemptCleanup(downloadDestination);
        }
    }

    private Path parameterDirectory(AlgorithmMetadata algorithm, AlgorithmInstanceFactory algorithmHolder) {
        if (!algorithmHolder.requiresLocalStateStorage(algorithm.algorithmName())) {
            return scratchDirectory;
        }
        return featureStateRoot
                .map(root -> root.resolve(DOWNLOADS_DIRECTORY))
                .orElseThrow(() -> new UnsupportedRuntimeStorageException(
                        "Algorithm " + algorithm.algorithmId()
                                + " requires local state storage but this runtime only provides scratch storage; scratchRoot="
                                + scratchDirectory));
    }

    private static void moveCompletedDownload(Path partialDestination, Path downloadDestination) throws IOException {
        try {
            Files.move(partialDestination, downloadDestination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(partialDestination, downloadDestination);
        }
    }

    private void attemptCleanup(final Path downloadDestination) {
        try {
            Files.deleteIfExists(downloadDestination);
        } catch (final IOException e1) {
            LOG.error("Unable to clean up download:" + downloadDestination, e1);
        }
    }


}
