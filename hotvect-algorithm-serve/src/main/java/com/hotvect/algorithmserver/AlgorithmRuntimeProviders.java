package com.hotvect.algorithmserver;

final class AlgorithmRuntimeProviders {
    private AlgorithmRuntimeProviders() {
    }

    static void validateOptions(ServerOptions opts) {
        boolean localRuntimeConfigMode = opts.localRuntimeConfig != null;
        if (isEmsMode(opts)) {
            require(nonBlank(opts.emsUrl), "--ems-url is required in EMS mode");
            require(nonBlank(opts.emsSlot), "--ems-slot is required in EMS mode");
            require(nonBlank(opts.emsAssignmentKey), "--ems-assignment-key must not be blank");
            require(nonBlank(opts.emsTokenEnv), "--ems-token-env must not be blank");
            require(opts.emsRefreshPeriodSeconds > 0, "--ems-refresh-period-seconds must be positive");
            require(opts.emsConnectTimeoutSeconds > 0, "--ems-connect-timeout-seconds must be positive");
            require(opts.emsReadTimeoutSeconds > 0, "--ems-read-timeout-seconds must be positive");
            require(opts.algorithmJar == null, "--algorithm-jar is only supported in local algorithm mode");
            require(opts.algorithmName == null, "--algorithm-name is only supported in local algorithm mode");
            require(opts.algorithmOverride == null, "--algorithm-override is only supported in local algorithm mode");
            require(opts.parameterPath == null, "--parameter-path is only supported in local algorithm mode");
            require(opts.localRuntimeConfig == null, "--local-runtime-config is only supported in local algorithm mode");
        } else if (localRuntimeConfigMode) {
            require(opts.algorithmJar == null, "--algorithm-jar is only supported in single-runtime local mode");
            require(opts.algorithmName == null, "--algorithm-name is only supported in single-runtime local mode");
            require(opts.algorithmOverride == null, "--algorithm-override is only supported in single-runtime local mode");
            require(opts.parameterPath == null, "--parameter-path is only supported in single-runtime local mode");
            require(opts.localRuntimeConfig.exists() && opts.localRuntimeConfig.isFile(),
                    "--local-runtime-config not found: %s",
                    opts.localRuntimeConfig.getAbsolutePath());
            require(opts.emsScratchDir == null, "--ems-scratch-dir is only supported in EMS mode");
        } else {
            require(opts.algorithmJar != null, "--algorithm-jar is required in local algorithm mode");
            require(opts.algorithmName != null && !opts.algorithmName.isBlank(), "--algorithm-name is required in local algorithm mode");
            require(opts.parameterPath != null, "--parameter-path is required in local algorithm mode");
            require(opts.algorithmJar.exists() && opts.algorithmJar.isFile(), "--algorithm-jar not found: %s", opts.algorithmJar.getAbsolutePath());
            require(opts.parameterPath.exists() && opts.parameterPath.isFile(), "--parameter-path not found: %s", opts.parameterPath.getAbsolutePath());
            require(opts.emsScratchDir == null, "--ems-scratch-dir is only supported in EMS mode");
        }
    }

    static AlgorithmRuntimeProvider create(ServerOptions opts) throws Exception {
        if (isEmsMode(opts)) {
            return EmsAlgorithmRuntimeProvider.create(opts);
        }
        return new LocalAlgorithmRuntimeProvider(opts);
    }

    private static boolean isEmsMode(ServerOptions opts) {
        return nonBlank(opts.emsUrl) || nonBlank(opts.emsSlot);
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static void require(boolean condition, String messageTemplate, Object... args) {
        if (!condition) {
            throw new IllegalArgumentException(String.format(messageTemplate, args));
        }
    }
}
