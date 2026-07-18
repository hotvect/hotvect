package com.hotvect.algorithmserver;

import picocli.CommandLine;

import java.io.File;
import java.util.concurrent.Callable;

@CommandLine.Command(
        name = "hotvect-algorithm-serve",
        mixinStandardHelpOptions = true,
        description = "HTTP server for Hotvect algorithm serving/debugging"
)
public class ServerOptions implements Callable<Integer> {
    @CommandLine.Option(names = {"--algorithm-jar"}, description = "Path to the algorithm JAR (required in local mode)")
    public File algorithmJar;

    @CommandLine.Option(names = {"--algorithm-name"}, description = "Algorithm name (matches algorithm definition in JAR) (required in local mode)")
    public String algorithmName;

    @CommandLine.Option(names = {"--algorithm-override"}, description = "Path to JSON file with algorithm definition overrides (optional)")
    public File algorithmOverride;

    @CommandLine.Option(names = {"--parameter-path"}, description = "Path to parameters ZIP (required in local mode)")
    public File parameterPath;

    @CommandLine.Option(
            names = {"--local-runtime-config"},
            description = "Path to JSON file describing one or more local runtimes (alternative to --algorithm-jar/--algorithm-name/--parameter-path)"
    )
    public File localRuntimeConfig;

    @CommandLine.Option(names = {"--ems-url"}, description = "EMS base URL; enables EMS-backed algorithm loading")
    public String emsUrl;

    @CommandLine.Option(names = {"--ems-slot"}, description = "EMS slot name (required with --ems-url)")
    public String emsSlot;

    @CommandLine.Option(names = {"--ems-assignment-key"}, defaultValue = "hv-serve", description = "Assignment key used for EMS variant assignment (default: ${DEFAULT-VALUE})")
    public String emsAssignmentKey;

    @CommandLine.Option(names = {"--ems-token-env"}, defaultValue = "EMS_TOKEN", description = "Environment variable containing EMS bearer token (default: ${DEFAULT-VALUE}; blank omits Authorization)")
    public String emsTokenEnv;

    @CommandLine.Option(names = {"--ems-scratch-dir"}, description = "Scratch directory for EMS algorithm downloads (default: a hv-serve directory under java.io.tmpdir)")
    public File emsScratchDir;

    @CommandLine.Option(names = {"--ems-refresh-period-seconds"}, defaultValue = "300", description = "EMS state refresh period in seconds (default: ${DEFAULT-VALUE})")
    public long emsRefreshPeriodSeconds;

    @CommandLine.Option(names = {"--ems-connect-timeout-seconds"}, defaultValue = "5", description = "EMS HTTP connect timeout in seconds (default: ${DEFAULT-VALUE})")
    public long emsConnectTimeoutSeconds;

    @CommandLine.Option(names = {"--ems-read-timeout-seconds"}, defaultValue = "30", description = "EMS HTTP read timeout in seconds (default: ${DEFAULT-VALUE})")
    public long emsReadTimeoutSeconds;

    @CommandLine.Option(
            names = {"--max-request-mib"},
            defaultValue = "256",
            description = "Max HTTP request size in MiB (default: ${DEFAULT-VALUE}; must be between 1 and 512)"
    )
    public long maxRequestMiB;

    @CommandLine.Option(names = {"--host"}, defaultValue = "127.0.0.1", description = "Bind host (default: ${DEFAULT-VALUE})")
    public String host;

    @CommandLine.Option(names = {"--port"}, defaultValue = "12000", description = "Bind port (default: ${DEFAULT-VALUE})")
    public int port;

    @Override
    public Integer call() throws Exception {
        return AlgorithmServerApp.runUntilInterrupted(this);
    }
}
