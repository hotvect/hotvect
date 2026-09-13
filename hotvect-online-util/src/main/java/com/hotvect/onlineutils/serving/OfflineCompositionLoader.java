package com.hotvect.onlineutils.serving;

import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.algodefinition.AlgorithmDependencies;
import com.hotvect.api.execution.ExecutionContext;
import com.hotvect.api.execution.InputSemantic;
import com.hotvect.onlineutils.experimentmanagement.algodownload.AlgorithmDownloadClient;
import com.hotvect.onlineutils.experimentmanagement.algodownload.AlgorithmDownloader;
import com.hotvect.onlineutils.util.Closeables;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** Shared inspection, execution-context resolution, construction, and cleanup for offline compositions. */
final class OfflineCompositionLoader {

    /** Inspects definitions, resolves execution options, and constructs the owning runtime. */
    @FunctionalInterface
    interface Preparation<RUNTIME> {
        RUNTIME prepare(
                AlgorithmRepository repository,
                AlgorithmDownloadClient downloadClient,
                Function<List<AlgorithmDefinition>, ExecutionContext> resolveExecutionContext) throws Exception;
    }

    static final class Options {
        private AlgorithmDownloadClient downloadClient;
        private Path scratchDirectory;
        private ClassLoader algorithmParentClassLoader = Thread.currentThread().getContextClassLoader();
        private ExecutionContext executionContext = ExecutionContext.batch(InputSemantic.OFFLINE);
        private Function<List<AlgorithmDefinition>, ExecutionContext> executionContextResolver;
        private Optional<Path> localStateRoot = Optional.empty();
        private boolean enableFeatureLogging;
        private MeterRegistry meterRegistry;

        Options downloadClient(AlgorithmDownloadClient downloadClient) {
            this.downloadClient = Objects.requireNonNull(downloadClient, "downloadClient must not be null");
            return this;
        }

        Options scratchDirectory(Path scratchDirectory) {
            this.scratchDirectory = Objects.requireNonNull(scratchDirectory, "scratchDirectory must not be null");
            return this;
        }

        Options algorithmParentClassLoader(ClassLoader algorithmParentClassLoader) {
            this.algorithmParentClassLoader = Objects.requireNonNull(
                    algorithmParentClassLoader,
                    "algorithmParentClassLoader must not be null");
            return this;
        }

        Options executionContext(ExecutionContext executionContext) {
            this.executionContext = Objects.requireNonNull(executionContext, "executionContext must not be null");
            return this;
        }

        Options resolveExecutionContext(
                Function<List<AlgorithmDefinition>, ExecutionContext> executionContextResolver) {
            this.executionContextResolver = Objects.requireNonNull(
                    executionContextResolver,
                    "executionContextResolver must not be null");
            return this;
        }

        Options localStateRoot(Path localStateRoot) {
            this.localStateRoot = Optional.of(Objects.requireNonNull(
                    localStateRoot,
                    "localStateRoot must not be null"));
            return this;
        }

        Options enableFeatureLogging(boolean enableFeatureLogging) {
            this.enableFeatureLogging = enableFeatureLogging;
            return this;
        }

        Options meterRegistry(MeterRegistry meterRegistry) {
            this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
            return this;
        }

        private AlgorithmDownloadClient requireDownloadClient() {
            return Objects.requireNonNull(downloadClient, "downloadClient is required");
        }

        private Path scratchDirectory() {
            return scratchDirectory == null
                    ? Path.of(System.getProperty("java.io.tmpdir"))
                    : scratchDirectory;
        }

        private Path localStateRoot() {
            return localStateRoot.orElseGet(() -> scratchDirectory().resolve("algorithm-state"));
        }

        private ExecutionContext resolveExecutionContext(List<AlgorithmDefinition> definitions) {
            if (executionContextResolver == null) {
                return executionContext;
            }
            ExecutionContext resolved = Objects.requireNonNull(
                    executionContextResolver.apply(definitions),
                    "executionContextResolver must return an execution context");
            if (resolved.inputSemantic() != executionContext.inputSemantic()) {
                throw new IllegalArgumentException(
                        "Resolved execution context must retain input semantic " + executionContext.inputSemantic());
            }
            return resolved;
        }
    }

    private OfflineCompositionLoader() {
    }

    static <RUNTIME> RUNTIME load(
            Options options,
            Preparation<RUNTIME> preparation) throws Exception {
        Options selected = Objects.requireNonNull(options, "options must not be null");
        Objects.requireNonNull(preparation, "preparation must not be null");
        AlgorithmDownloadClient downloadClient = selected.requireDownloadClient();
        AlgorithmRepository repository = null;
        try {
            Path scratch = selected.scratchDirectory();
            Files.createDirectories(scratch);
            AlgorithmDownloader downloader = new AlgorithmDownloader(
                    downloadClient,
                    scratch,
                    selected.algorithmParentClassLoader,
                    new AlgorithmDownloader.Options(
                            selected.executionContext.inputSemantic(),
                            true,
                            selected.enableFeatureLogging,
                            Optional.of(selected.localStateRoot())));
            repository = new AlgorithmRepository(
                    downloader,
                    selected.meterRegistry,
                    AlgorithmDependencies.empty());
            return preparation.prepare(repository, downloadClient, selected::resolveExecutionContext);
        } catch (Exception | Error failure) {
            Closeables.closeAfterFailure(failure, repository, downloadClient);
            throw failure;
        }
    }
}
