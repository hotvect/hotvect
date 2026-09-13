package com.hotvect.onlineutils.experimentmanagement.algodownload;

import static com.google.common.base.Preconditions.checkState;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.algodefinition.AlgorithmDependencies;
import com.hotvect.api.algodefinition.AlgorithmId;
import com.hotvect.api.execution.ExecutionContext;
import com.hotvect.api.execution.InputSemantic;
import com.hotvect.onlineutils.experimentmanagement.models.AlgorithmMetadata;
import com.hotvect.onlineutils.hotdeploy.AlgorithmArtifactProvider;
import com.hotvect.onlineutils.hotdeploy.AlgorithmGraph;
import com.hotvect.onlineutils.hotdeploy.AlgorithmGraphDependencies;
import com.hotvect.onlineutils.hotdeploy.AlgorithmInstanceFactory;
import com.hotvect.onlineutils.hotdeploy.SharedNodeInterner;
import com.hotvect.onlineutils.hotdeploy.util.MalformedAlgorithmException;
import com.hotvect.utils.AlgorithmDefinitionOverrideUtils;
import com.hotvect.utils.AlgorithmDefinitionReader;
import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AlgorithmDownloader {
    private static final Logger LOG = LoggerFactory.getLogger(AlgorithmDownloader.class);
    private static final String DOWNLOADS_DIRECTORY = "downloads";

    private final AlgorithmDownloadClient algorithmDownloadClient;
    private final ClassLoader classLoader;
    private final Path scratchDirectory;
    private final Path localStateRoot;
    private final InputSemantic inputSemantic;
    private final boolean strictAlgorithmVersionCheck;
    private final boolean enableFeatureLogging;
    private final AlgorithmDefinitionReader definitionReader;

    public record Options(
            InputSemantic inputSemantic,
            boolean strictAlgorithmVersionCheck,
            boolean enableFeatureLogging,
            Optional<Path> localStateRoot) {
        public Options {
            inputSemantic = Objects.requireNonNull(inputSemantic, "inputSemantic must not be null");
            localStateRoot = Objects.requireNonNull(localStateRoot, "localStateRoot must not be null")
                    .map(Path::toAbsolutePath);
        }
    }

    public AlgorithmDownloader(
            final AlgorithmDownloadClient algorithmDownloadClient,
            final Path scratchDirectory,
            final ClassLoader classLoader,
            final Options options
        ) {
        this.algorithmDownloadClient = algorithmDownloadClient;
        this.classLoader = classLoader;
        this.scratchDirectory = Objects.requireNonNull(scratchDirectory, "scratchDirectory").toAbsolutePath();
        Options resolved = Objects.requireNonNull(options, "options must not be null");
        this.localStateRoot = resolved.localStateRoot()
                .orElseGet(() -> this.scratchDirectory.resolve("algorithm-state"));
        this.inputSemantic = resolved.inputSemantic();
        this.strictAlgorithmVersionCheck = resolved.strictAlgorithmVersionCheck();
        this.enableFeatureLogging = resolved.enableFeatureLogging();
        this.definitionReader = new AlgorithmDefinitionReader(inputSemantic);
    }

    public DownloadedAlgorithmArtifact downloadAlgorithmArtifact(AlgorithmMetadata algorithm) {
        final String randomPrefix = UUID.randomUUID().toString();
        final String fileName = algorithm.algorithmJarFileName();
        final Path downloadDestination = scratchDirectory.resolve(randomPrefix + "-" + fileName);
        try {
            Files.createDirectories(downloadDestination.getParent());
            final long downloadStartNanos = System.nanoTime();
            algorithmDownloadClient.downloadAlgorithmJar(algorithm, downloadDestination);
            checkState(downloadDestination.toFile().exists() && downloadDestination.toFile().length() > 0,
                    "File download failed");
            LOG.info("Downloaded algorithm jar {} ({} bytes) in {} ms",
                    fileName, downloadDestination.toFile().length(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - downloadStartNanos));
            AlgorithmInstanceFactory factory = new AlgorithmInstanceFactory(
                    downloadDestination.toFile(),
                    classLoader,
                    algorithmInstanceOptions());
            return new DownloadedAlgorithmArtifact(factory, downloadDestination);
        } catch (final Exception e) {
            attemptCleanup(downloadDestination);
            throw new RuntimeException("Algorithm Jar couldn't get downloaded : " + fileName, e);
        }
    }

    /** Stages one current parameter archive for graph construction and shared-parameter selection. */
    public DownloadedAlgorithmParameters downloadAlgorithmParameters(AlgorithmMetadata algorithm) {
        Objects.requireNonNull(algorithm, "algorithm must not be null");
        if (!algorithm.hasParameter()) {
            throw new IllegalArgumentException("Algorithm " + algorithm.algorithmId() + " has no parameter archive");
        }
        String parameterName = algorithm.latestAlgorithmParameterFileName();
        Path parameterDirectory = localStateRoot.resolve(DOWNLOADS_DIRECTORY);
        Path destination = parameterDirectory.resolve(UUID.randomUUID() + "-" + parameterName);
        Path partialDestination = destination.resolveSibling(destination.getFileName() + ".part");
        try {
            Files.createDirectories(parameterDirectory);
            long downloadStartNanos = System.nanoTime();
            algorithmDownloadClient.downloadAlgorithmParameter(algorithm, partialDestination);
            checkState(partialDestination.toFile().exists() && partialDestination.toFile().length() > 0,
                    "File download failed");
            moveCompletedDownload(partialDestination, destination);
            LOG.info("Downloaded algorithm parameter {} ({} bytes) in {} ms",
                    parameterName,
                    destination.toFile().length(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - downloadStartNanos));
            return new DownloadedAlgorithmParameters(destination);
        } catch (Exception error) {
            attemptCleanup(destination);
            throw new RuntimeException("Algorithm parameters couldn't get downloaded: " + parameterName, error);
        } finally {
            attemptCleanup(partialDestination);
        }
    }

    /** Loads one graph with distinct application and named EMS slot bindings. */
    public AlgorithmGraph<?> loadAlgorithmGraph(
            AlgorithmMetadata algorithm,
            DownloadedAlgorithmArtifact artifact,
            File parameterFile,
            AlgorithmDependencies dependencyOverrides,
            AlgorithmGraphDependencies slotBindings,
            String rootProviderIdentity,
            Map<AlgorithmId, AlgorithmArtifactProvider> sharedProviders,
            SharedNodeInterner sharedNodeInterner,
            ExecutionContext executionContext) throws MalformedAlgorithmException {
        Objects.requireNonNull(executionContext, "executionContext must not be null");
        requireCompatibleInputSemantic(executionContext);
        AlgorithmDefinition definition = readAlgorithmDefinition(artifact, algorithm.algorithmName());
        if (algorithm.hasParameter() != (parameterFile != null)) {
            throw new IllegalArgumentException(
                    "Algorithm " + algorithm.algorithmId() + (algorithm.hasParameter()
                            ? " requires a staged parameter archive"
                            : " must not receive a parameter archive"));
        }
        final long buildStartNanos = System.nanoTime();
        final AlgorithmGraph<?> graph = artifact.loadGraph(
                definition,
                parameterFile,
                dependencyOverrides,
                slotBindings,
                rootProviderIdentity,
                sharedProviders,
                sharedNodeInterner,
                executionContext);
        LOG.info(
                algorithm.hasParameter()
                        ? "Constructed algorithm graph for {} in {} ms"
                        : "Constructed parameterless algorithm graph for {} in {} ms",
                algorithm.algorithmName(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - buildStartNanos));
        return graph;
    }

    /** Rejects any execution context that departs from this downloader's fixed input semantic. */
    private void requireCompatibleInputSemantic(ExecutionContext executionContext) {
        if (executionContext.inputSemantic() != inputSemantic) {
            throw new IllegalArgumentException(
                    "Execution context must retain input semantic " + inputSemantic);
        }
    }

    public AlgorithmDefinition readAlgorithmDefinition(
            DownloadedAlgorithmArtifact artifact,
            String algorithmName) {
        return Objects.requireNonNull(artifact, "artifact must not be null")
                .readAlgorithmDefinition(algorithmName);
    }

    /** Applies one dependency override using this downloader's execution semantics. */
    public AlgorithmDefinition applyDefinitionOverride(
            AlgorithmDefinition baseDefinition,
            JsonNode override) {
        JsonNode merged = AlgorithmDefinitionOverrideUtils.applyOverride(
                Objects.requireNonNull(baseDefinition, "baseDefinition must not be null").rawAlgorithmDefinition(),
                Objects.requireNonNull(override, "override must not be null"),
                definitionReader.dependencyResolution());
        return parseAlgorithmDefinition(merged);
    }

    /** Parses an effective definition using this downloader's execution semantics. */
    public AlgorithmDefinition parseAlgorithmDefinition(JsonNode definition) {
        try {
            return definitionReader.parse(Objects.requireNonNull(definition, "definition must not be null"));
        } catch (IOException error) {
            throw new MalformedAlgorithmException(error);
        }
    }

    private AlgorithmInstanceFactory.Options algorithmInstanceOptions() {
        return new AlgorithmInstanceFactory.Options(
                inputSemantic,
                strictAlgorithmVersionCheck,
                enableFeatureLogging,
                Optional.of(localStateRoot));
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
