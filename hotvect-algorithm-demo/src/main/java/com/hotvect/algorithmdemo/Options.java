package com.hotvect.algorithmdemo;

import picocli.CommandLine;

import java.io.File;
import java.util.concurrent.Callable;

@CommandLine.Command(
        name = "hotvect-algorithm-demo",
        mixinStandardHelpOptions = true,
        description = "Local HTTP server for algorithm debugging; add --ui to enable the browser UI"
)
public class Options implements Callable<Integer> {
    @CommandLine.Option(names = {"--algorithm-jar"}, required = true, description = "Path to the algorithm JAR (required)")
    public File algorithmJar;

    @CommandLine.Option(names = {"--algorithm-name"}, required = true, description = "Algorithm name (matches algorithm definition in JAR) (required)")
    public String algorithmName;

    @CommandLine.Option(names = {"--algorithm-override"}, description = "Path to JSON file with algorithm definition overrides (optional)")
    public File algorithmOverride;

    @CommandLine.Option(names = {"--parameter-path"}, required = true, description = "Path to parameters ZIP (required)")
    public File parameterPath;

    @CommandLine.Option(
            names = {"--source-path"},
            description = "Directory scanned recursively for *.jsonl/*.json and gz variants (*.jsonl.gz/*.json.gz) demo examples (required with --ui)"
    )
    public File sourcePath;

    @CommandLine.Option(
            names = {"--action-metadata-path"},
            description = "Directory scanned recursively for action metadata files: *.jsonl/*.json/part-* and gz variants (e.g. *.jsonl.gz, part-00000.json.gz) (optional; required for image cards)"
    )
    public File actionMetadataPath;

    @CommandLine.Option(
            names = {"--demo-sqlite-path"},
            description = "Path to a SQLite DB file used to cache action metadata (optional; if the DB does not exist, it will be built on startup; defaults to a deterministic path under /tmp)"
    )
    public File demoSqlitePath;

    @CommandLine.Option(
            names = {"--ui"},
            description = "Enable the browser UI routes and static assets (requires --source-path)"
    )
    public boolean ui;

    @CommandLine.Option(
            names = {"--max-request-mib"},
            defaultValue = "256",
            description = "Max HTTP request size in MiB (default: ${DEFAULT-VALUE})"
    )
    public long maxRequestMiB;

    @CommandLine.Option(names = {"--host"}, defaultValue = "127.0.0.1", description = "Bind host (default: ${DEFAULT-VALUE})")
    public String host;

    @CommandLine.Option(names = {"--port"}, defaultValue = "12000", description = "Bind port (default: ${DEFAULT-VALUE})")
    public int port;

    @Override
    public Integer call() throws Exception {
        try (DemoUiApp app = new DemoUiApp(this)) {
            app.start();
            Thread.sleep(Long.MAX_VALUE);
            return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        }
    }
}
