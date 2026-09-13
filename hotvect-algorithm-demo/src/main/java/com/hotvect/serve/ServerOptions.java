package com.hotvect.serve;

import picocli.CommandLine;

import java.io.File;
public class ServerOptions {
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
}
