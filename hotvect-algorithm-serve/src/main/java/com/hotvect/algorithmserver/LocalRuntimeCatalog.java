package com.hotvect.algorithmserver;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class LocalRuntimeCatalog implements AutoCloseable {
    private final List<LoadedLocalRuntime> runtimes;
    private final Map<String, LoadedLocalRuntime> runtimesById;

    private LocalRuntimeCatalog(List<LoadedLocalRuntime> runtimes, Map<String, LoadedLocalRuntime> runtimesById) {
        this.runtimes = runtimes;
        this.runtimesById = runtimesById;
    }

    static LocalRuntimeCatalog create(ServerOptions opts) throws Exception {
        ArrayList<AlgorithmRuntime> loadedRuntimes = new ArrayList<>();
        try {
            if (opts.localRuntimeConfig != null) {
                LocalRuntimeConfig config = LocalRuntimeConfig.load(opts.localRuntimeConfig);
                for (LocalRuntimeConfig.RuntimeSpec runtimeSpec : config.runtimes()) {
                    loadedRuntimes.add(loadRuntime(runtimeSpec.algorithmJar(), runtimeSpec.algorithmName(), runtimeSpec.algorithmOverride(), runtimeSpec.parameterPath()));
                }
            } else {
                loadedRuntimes.add(loadRuntime(opts.algorithmJar, opts.algorithmName, opts.algorithmOverride, opts.parameterPath));
            }
            return fromLoadedRuntimes(loadedRuntimes);
        } catch (Exception e) {
            closeLoadedRuntimes(loadedRuntimes, e);
            throw e;
        }
    }

    static LocalRuntimeCatalog fromLoadedRuntimes(List<AlgorithmRuntime> loadedRuntimes) {
        if (loadedRuntimes == null || loadedRuntimes.isEmpty()) {
            throw new IllegalArgumentException("At least one local runtime is required");
        }
        ArrayList<LoadedLocalRuntime> sorted = loadedRuntimes.stream()
                .map(LoadedLocalRuntime::new)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        sorted.sort(Comparator.comparing(LoadedLocalRuntime::algorithmRuntimeId));
        LinkedHashMap<String, LoadedLocalRuntime> runtimesById = new LinkedHashMap<>();
        for (LoadedLocalRuntime runtime : sorted) {
            LoadedLocalRuntime previous = runtimesById.put(runtime.algorithmRuntimeId(), runtime);
            if (previous != null) {
                throw new IllegalArgumentException(
                        "Duplicate local algorithm_runtime_id in local runtime config: " + runtime.algorithmRuntimeId());
            }
        }
        return new LocalRuntimeCatalog(
                List.copyOf(sorted),
                Collections.unmodifiableMap(new LinkedHashMap<>(runtimesById)));
    }

    List<AlgorithmRuntime> runtimes() {
        return runtimes.stream().map(LoadedLocalRuntime::runtime).toList();
    }

    void addMetadata(ObjectNode root) {
        AlgorithmServerApp.addRuntimesMetadata(root, runtimes());
    }

    LoadedLocalRuntime selectOrDefault(String algorithmRuntimeIdOrNull) {
        if (algorithmRuntimeIdOrNull == null || algorithmRuntimeIdOrNull.isBlank()) {
            return runtimes.getFirst();
        }
        LoadedLocalRuntime runtime = runtimesById.get(algorithmRuntimeIdOrNull);
        if (runtime != null) {
            return runtime;
        }
        throw new ContractViolationException(
                "Unknown algorithm_runtime_id: " + algorithmRuntimeIdOrNull,
                "Available local runtimes: " + String.join(", ", runtimesById.keySet()));
    }

    @Override
    public void close() {
        for (LoadedLocalRuntime runtime : runtimes.reversed()) {
            runtime.runtime().close();
        }
    }

    private static AlgorithmRuntime loadRuntime(
            File algorithmJar,
            String algorithmName,
            File algorithmOverride,
            File parameterPath) throws Exception {
        ValidationSupport.requireArgument(
                algorithmJar != null && algorithmJar.exists() && algorithmJar.isFile(),
                "--algorithm-jar not found: %s",
                absolutePathOrNull(algorithmJar));
        ValidationSupport.requireArgument(
                parameterPath != null && parameterPath.exists() && parameterPath.isFile(),
                "--parameter-path not found: %s",
                absolutePathOrNull(parameterPath));
        if (algorithmOverride != null) {
            ValidationSupport.requireArgument(
                    algorithmOverride.exists() && algorithmOverride.isFile(),
                    "--algorithm-override not found: %s",
                    algorithmOverride.getAbsolutePath());
        }
        return new AlgorithmRuntime(algorithmJar, algorithmName, algorithmOverride, parameterPath);
    }

    private static String absolutePathOrNull(File file) {
        return file == null ? null : file.getAbsolutePath();
    }

    private static void closeLoadedRuntimes(List<AlgorithmRuntime> loadedRuntimes, Exception failure) {
        for (AlgorithmRuntime runtime : loadedRuntimes.reversed()) {
            try {
                runtime.close();
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }
    }

    record LoadedLocalRuntime(AlgorithmRuntime runtime) {
        String algorithmRuntimeId() {
            return runtime.identity().algorithmRuntimeId();
        }
    }
}
