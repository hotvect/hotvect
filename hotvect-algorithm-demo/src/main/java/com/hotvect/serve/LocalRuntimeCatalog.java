package com.hotvect.serve;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class LocalRuntimeCatalog implements AutoCloseable {
    private final List<AlgorithmRuntime> runtimes;
    private final Map<String, AlgorithmRuntime> runtimesById;

    private LocalRuntimeCatalog(List<AlgorithmRuntime> runtimes, Map<String, AlgorithmRuntime> runtimesById) {
        this.runtimes = runtimes;
        this.runtimesById = runtimesById;
    }

    static LocalRuntimeCatalog create(ServerOptions opts) throws Exception {
        ArrayList<AlgorithmRuntime> loadedRuntimes = new ArrayList<>();
        try {
            if (opts.localRuntimeConfig != null) {
                validateConfigMode(opts);
                LocalRuntimeConfig config = LocalRuntimeConfig.load(opts.localRuntimeConfig);
                for (LocalRuntimeConfig.RuntimeSpec runtimeSpec : config.runtimes()) {
                    loadedRuntimes.add(loadRuntime(
                            runtimeSpec.algorithmJar(),
                            runtimeSpec.algorithmName(),
                            runtimeSpec.algorithmOverride(),
                            runtimeSpec.parameterPath()));
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
        ArrayList<AlgorithmRuntime> sorted = new ArrayList<>(loadedRuntimes);
        sorted.sort(Comparator.comparing(runtime -> runtime.identity().value()));
        LinkedHashMap<String, AlgorithmRuntime> runtimesById = new LinkedHashMap<>();
        for (AlgorithmRuntime runtime : sorted) {
            String algorithmRuntimeId = runtime.identity().value();
            AlgorithmRuntime previous = runtimesById.put(algorithmRuntimeId, runtime);
            if (previous != null) {
                throw new IllegalArgumentException(
                        "Duplicate local algorithm_runtime_id in local runtime config: " + algorithmRuntimeId);
            }
        }
        return new LocalRuntimeCatalog(
                List.copyOf(sorted),
                Collections.unmodifiableMap(new LinkedHashMap<>(runtimesById)));
    }

    List<AlgorithmRuntime> runtimes() {
        return runtimes;
    }

    AlgorithmRuntime selectOrDefault(String algorithmRuntimeIdOrNull) {
        if (algorithmRuntimeIdOrNull == null || algorithmRuntimeIdOrNull.isBlank()) {
            return runtimes.getFirst();
        }
        AlgorithmRuntime runtime = runtimesById.get(algorithmRuntimeIdOrNull);
        if (runtime != null) {
            return runtime;
        }
        throw new ContractViolationException(
                "Unknown algorithm_runtime_id: " + algorithmRuntimeIdOrNull,
                "Available local runtimes: " + String.join(", ", runtimesById.keySet()));
    }

    @Override
    public void close() {
        for (AlgorithmRuntime runtime : runtimes.reversed()) {
            runtime.close();
        }
    }

    private static AlgorithmRuntime loadRuntime(
            File algorithmJar,
            String algorithmName,
            File algorithmOverride,
            File parameterPath) throws Exception {
        ValidationSupport.requireArgument(
                algorithmJar != null,
                "--algorithm-jar is required");
        ValidationSupport.requireArgument(
                algorithmName != null && !algorithmName.isBlank(),
                "--algorithm-name is required");
        ValidationSupport.requireArgument(
                parameterPath != null,
                "--parameter-path is required");
        ValidationSupport.requireArgument(
                algorithmJar.exists() && algorithmJar.isFile(),
                "--algorithm-jar not found: %s",
                absolutePathOrNull(algorithmJar));
        ValidationSupport.requireArgument(
                parameterPath.exists() && parameterPath.isFile(),
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

    private static void validateConfigMode(ServerOptions opts) {
        ValidationSupport.requireArgument(opts.algorithmJar == null,
                "--algorithm-jar is only supported in single-runtime mode");
        ValidationSupport.requireArgument(opts.algorithmName == null,
                "--algorithm-name is only supported in single-runtime mode");
        ValidationSupport.requireArgument(opts.algorithmOverride == null,
                "--algorithm-override is only supported in single-runtime mode");
        ValidationSupport.requireArgument(opts.parameterPath == null,
                "--parameter-path is only supported in single-runtime mode");
        ValidationSupport.requireArgument(
                opts.localRuntimeConfig.exists() && opts.localRuntimeConfig.isFile(),
                "--local-runtime-config not found: %s",
                opts.localRuntimeConfig.getAbsolutePath());
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
}
