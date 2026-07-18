package com.hotvect.algorithmserver;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.onlineutils.experimentmanagement.algodownload.AlgorithmDownloader;
import com.hotvect.onlineutils.experimentmanagement.algodownload.AlgorithmRepository;
import com.hotvect.onlineutils.experimentmanagement.algodownload.S3AlgorithmDownloadClient;
import com.hotvect.onlineutils.experimentmanagement.experimentation.DefaultExperimentationManager;
import com.hotvect.onlineutils.experimentmanagement.experimentation.ExperimentationManager;
import com.hotvect.onlineutils.experimentmanagement.httpclient.ExperimentManagementServiceClient;
import com.hotvect.onlineutils.experimentmanagement.models.VariantConfiguration;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

final class EmsAlgorithmRuntimeProvider implements AlgorithmRuntimeProvider {
    private final URI emsUri;
    private final String slotName;
    private final String assignmentKey;
    private final Path scratchDir;
    private final Duration refreshPeriod;
    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final String tokenEnv;
    private final AutoCloseable emsClient;
    private final AutoCloseable downloadClient;
    private final ExperimentationManager experimentationManager;
    private AlgorithmInstance<?> cachedAlgorithmInstance;
    private AlgorithmRuntime cachedRuntime;

    static EmsAlgorithmRuntimeProvider create(final ServerOptions opts) throws Exception {
        final URI emsUri = URI.create(opts.emsUrl);
        final Path scratchDir = resolveEmsScratchDir(opts.emsScratchDir);
        Files.createDirectories(scratchDir);
        final Duration refreshPeriod = Duration.ofSeconds(opts.emsRefreshPeriodSeconds);
        final Duration connectTimeout = Duration.ofSeconds(opts.emsConnectTimeoutSeconds);
        final Duration readTimeout = Duration.ofSeconds(opts.emsReadTimeoutSeconds);
        final S3AlgorithmDownloadClient downloadClient = new S3AlgorithmDownloadClient();
        ExperimentManagementServiceClient emsClient = null;
        ExperimentationManager experimentationManager = null;
        try {
            final AlgorithmDownloader downloader = new AlgorithmDownloader(
                    downloadClient,
                    scratchDir.toString(),
                    Thread.currentThread().getContextClassLoader(),
                    true);
            final AlgorithmRepository algorithmRepository = new AlgorithmRepository(downloader);
            emsClient = new ExperimentManagementServiceClient(
                    emsUri,
                    connectTimeout,
                    readTimeout,
                    () -> System.getenv(opts.emsTokenEnv));
            experimentationManager = new DefaultExperimentationManager(
                    algorithmRepository,
                    refreshPeriod,
                    emsClient,
                    Set.of(opts.emsSlot));
            experimentationManager.startAsync().awaitRunning();
            return new EmsAlgorithmRuntimeProvider(
                    emsUri,
                    opts.emsSlot,
                    opts.emsAssignmentKey,
                    scratchDir,
                    refreshPeriod,
                    connectTimeout,
                    readTimeout,
                    opts.emsTokenEnv,
                    emsClient,
                    downloadClient,
                    experimentationManager);
        } catch (Exception e) {
            closeFailedSetup(experimentationManager, emsClient, downloadClient, e);
            throw e;
        }
    }

    private static void closeFailedSetup(
            final ExperimentationManager experimentationManager,
            final AutoCloseable emsClient,
            final AutoCloseable downloadClient,
            final Exception failure) {
        final RuntimeException closeFailure = closeResources(experimentationManager, emsClient, downloadClient);
        if (closeFailure != null) {
            failure.addSuppressed(closeFailure);
        }
    }

    EmsAlgorithmRuntimeProvider(
            final URI emsUri,
            final String slotName,
            final String assignmentKey,
            final Path scratchDir,
            final Duration refreshPeriod,
            final Duration connectTimeout,
            final Duration readTimeout,
            final String tokenEnv,
            final AutoCloseable emsClient,
            final AutoCloseable downloadClient,
            final ExperimentationManager experimentationManager) {
        this.emsUri = emsUri;
        this.slotName = slotName;
        this.assignmentKey = assignmentKey;
        this.scratchDir = scratchDir;
        this.refreshPeriod = refreshPeriod;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
        this.tokenEnv = tokenEnv;
        this.emsClient = emsClient;
        this.downloadClient = downloadClient;
        this.experimentationManager = experimentationManager;
    }

    @Override
    public RuntimeSelection selectRuntime(String algorithmRuntimeIdOrNull) {
        if (algorithmRuntimeIdOrNull != null && !algorithmRuntimeIdOrNull.isBlank()) {
            throw new ContractViolationException(
                    "algorithm_runtime_id selection is only supported in local algorithm mode",
                    null);
        }
        final VariantConfiguration variantConfiguration =
                experimentationManager.assignVariant(slotName, assignmentKey);
        final AlgorithmRuntime runtime = runtimeFor(variantConfiguration.algorithmInstance());
        return RuntimeSelection.ems(
                runtime,
                Integer.toString(variantConfiguration.variant().variantId()),
                assignmentKey,
                variantConfiguration.variant().isDefault(),
                variantConfiguration.variant().isControl(),
                variantConfiguration.variant().shardAllocationRatio());
    }

    @Override
    public void addMetadata(ObjectNode root, RuntimeSelection selection) {
        AlgorithmServerApp.addRuntimesMetadata(root, java.util.List.of(selection.runtime()));

        ObjectNode emsNode = root.putObject("ems");
        emsNode.put("url", emsUri.toString());
        emsNode.put("slot", slotName);
        emsNode.put("assignment_key", assignmentKey);
        emsNode.put("token_env", tokenEnv);
        emsNode.put("scratch_dir", scratchDir.toString());
        emsNode.put("refresh_period_seconds", refreshPeriod.toSeconds());
        emsNode.put("connect_timeout_seconds", connectTimeout.toSeconds());
        emsNode.put("read_timeout_seconds", readTimeout.toSeconds());
        emsNode.put("total_number_of_shards", experimentationManager.totalNumberOfShards(slotName));
        emsNode.put("state_updated_at", experimentationManager.currentState(slotName).updatedAt().toString());

        ObjectNode variantNode = root.putObject("variant");
        selection.variantId().ifPresentOrElse(
                variantId -> variantNode.put("variant_id", variantId),
                () -> variantNode.putNull("variant_id"));
        putBooleanOrNull(variantNode, "is_default", selection.variantDefault().orElse(null));
        putBooleanOrNull(variantNode, "is_control", selection.variantControl().orElse(null));
        selection.shardAllocationRatio().ifPresentOrElse(
                ratio -> variantNode.put("shard_allocation_ratio", ratio),
                () -> variantNode.putNull("shard_allocation_ratio"));
    }

    @Override
    public void close() {
        final RuntimeException closeFailure = closeResources(experimentationManager, emsClient, downloadClient);
        if (closeFailure != null) {
            throw closeFailure;
        }
    }

    private synchronized AlgorithmRuntime runtimeFor(final AlgorithmInstance<?> algorithmInstance) {
        if (cachedAlgorithmInstance == algorithmInstance && cachedRuntime != null) {
            return cachedRuntime;
        }
        try {
            cachedRuntime = new AlgorithmRuntime(algorithmInstance);
            cachedAlgorithmInstance = algorithmInstance;
            return cachedRuntime;
        } catch (final Exception e) {
            throw new RuntimeException("Failed to create EMS algorithm runtime", e);
        }
    }

    private static Path resolveEmsScratchDir(final File configuredScratchDir) {
        if (configuredScratchDir != null) {
            return configuredScratchDir.toPath();
        }
        return Path.of(
                System.getProperty("java.io.tmpdir"),
                "hv-serve-ems-" + ProcessHandle.current().pid());
    }

    private static void putBooleanOrNull(ObjectNode node, String fieldName, Boolean value) {
        if (value == null) {
            node.putNull(fieldName);
        } else {
            node.put(fieldName, value);
        }
    }

    private static RuntimeException closeResources(final AutoCloseable... resources) {
        RuntimeException failure = null;
        for (final AutoCloseable resource : resources) {
            failure = closeResource(failure, resource);
        }
        return failure;
    }

    private static RuntimeException closeResource(
            final RuntimeException accumulated,
            final AutoCloseable resource) {
        if (resource == null) {
            return accumulated;
        }
        try {
            resource.close();
            return accumulated;
        } catch (RuntimeException e) {
            return appendFailure(accumulated, e);
        } catch (Exception e) {
            return appendFailure(
                    accumulated,
                    new RuntimeException("Failed to close " + resource.getClass().getSimpleName(), e)
            );
        }
    }

    private static RuntimeException appendFailure(
            final RuntimeException accumulated,
            final RuntimeException nextFailure) {
        if (accumulated == null) {
            return nextFailure;
        }
        accumulated.addSuppressed(nextFailure);
        return accumulated;
    }
}
