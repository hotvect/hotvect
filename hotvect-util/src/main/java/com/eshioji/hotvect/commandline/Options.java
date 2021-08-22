package com.eshioji.hotvect.commandline;

import picocli.CommandLine;

import java.io.File;

class Options {
    @CommandLine.Option(names = {"--algorithm-definition"}, paramLabel = "TODO", description = "TODO")
    String algorithmDefinition;

    @CommandLine.Option(names = {"--encode"}, description = "Vectorize and encode")
    boolean encode;

    @CommandLine.Option(names = {"--predict"}, description = "Predict")
    boolean predict;

    @CommandLine.Option(names = {"--source"}, paramLabel = "SOURCE_FILE", description = "Source file")
    File sourceFile;

    @CommandLine.Option(names = {"--dest"}, paramLabel = "DESTINATION_FILE", description = "Destination file")
    File destinationFile;

    @CommandLine.Option(names = {"--sample-pct"}, paramLabel = "SAMPLE_PCT", description = "Sampling pct", defaultValue = "1.0")
    double samplePct = 1.0;

    @CommandLine.Option(names = {"--sample-seed"}, paramLabel = "Sample seed", description = "Sampling seed", defaultValue = "0")
    int sampleSeed = 0;

    @CommandLine.Option(names = {"--meta-data"}, paramLabel = "Metadata location", description = "Metadata location", defaultValue = "metadata.json")
    File metadataLocation;

    @CommandLine.Option(names = {"-h", "--help"}, usageHelp = true, description = "display a help message")
    private boolean helpRequested = false;
}