package com.hotvect.algorithmdemo;

import com.hotvect.algorithmserver.ActionMetadataLookup;
import com.hotvect.algorithmserver.AlgorithmServerApp;
import com.hotvect.algorithmserver.ServerExtension;
import com.hotvect.algorithmserver.ServerOptions;
import picocli.CommandLine;

import java.io.File;
import java.util.List;
import java.util.concurrent.Callable;

@CommandLine.Command(
        name = "hotvect-algorithm-demo",
        mixinStandardHelpOptions = true,
        description = "Local HTTP server for algorithm debugging; add --ui to enable the browser UI"
)
public class Options extends ServerOptions implements Callable<Integer> {
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
            names = {"--default-select-json-path"},
            description = "JSON path selected by default in the input editor (optional)"
    )
    public String defaultSelectJsonPath;

    @CommandLine.Option(
            names = {"--ui"},
            description = "Enable the browser UI routes and static assets (requires --source-path)"
    )
    public boolean ui;

    @Override
    public Integer call() throws Exception {
        if (!ui) {
            if (sourcePath != null) {
                throw new IllegalArgumentException("--source-path is only supported with --ui");
            }
            if (actionMetadataPath != null) {
                throw new IllegalArgumentException("--action-metadata-path is only supported with --ui");
            }
            if (demoSqlitePath != null) {
                throw new IllegalArgumentException("--demo-sqlite-path is only supported with --ui");
            }
            if (defaultSelectJsonPath != null) {
                throw new IllegalArgumentException("--default-select-json-path is only supported with --ui");
            }
        }
        DemoUiExtension demoUiExtension = ui ? new DemoUiExtension(this) : null;
        ActionMetadataLookup actionMetadata = demoUiExtension == null
                ? ActionMetadataLookup.empty()
                : demoUiExtension.actionMetadata();
        List<ServerExtension> extensions = demoUiExtension == null
                ? List.of()
                : List.of(demoUiExtension);
        return AlgorithmServerApp.runUntilInterrupted(this, actionMetadata, extensions);
    }
}
