package com.eshioji.hotvect.offlineutils.commandline;

import picocli.CommandLine;

import java.io.File;

public class Options {
    @CommandLine.Option(names = {"--algorithm-jar"}, paramLabel = "TODO", description = "TODO")
    public String algorithmJar;

    @CommandLine.Option(names = {"--algorithm-definition"}, paramLabel = "TODO", description = "TODO")
    public String algorithmDefinition;

    @CommandLine.Option(names = {"--parameters"}, paramLabel = "TODO", description = "TODO")
    public String parameters;

    @CommandLine.Option(names = {"--encode"}, description = "Vectorize and encode")
    public boolean encode;

    @CommandLine.Option(names = {"--predict"}, description = "Predict")
    public boolean predict;

    @CommandLine.Option(names = {"--audit"}, description = "Audit")
    public boolean audit;

    @CommandLine.Option(names = {"--generate-state"}, description = "Generator class name")
    public String generateStateTask;

    @CommandLine.Option(names = {"--source"}, paramLabel = "SOURCE_FILE", description = "Source file")
    public File sourceFile;

    @CommandLine.Option(names = {"--training-data"}, paramLabel = "TRAINING_FILE", description = "Training file")
    public File trainingFile;

    @CommandLine.Option(names = {"--dest"}, paramLabel = "DESTINATION_FILE", description = "Destination file")
    public File destinationFile;

    @CommandLine.Option(names = {"--meta-data"}, paramLabel = "Metadata location", description = "Metadata location", defaultValue = "metadata.json")
    public File metadataLocation;

    @CommandLine.Option(names = {"-h", "--help"}, usageHelp = true, description = "display a help message")
    private boolean helpRequested = false;
}
