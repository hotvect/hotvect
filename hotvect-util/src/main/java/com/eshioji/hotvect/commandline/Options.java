package com.eshioji.hotvect.commandline;

import picocli.CommandLine;

import java.io.File;

class Options {
    @CommandLine.Option(names = {"--encode"}, description = "Vectorize and encode")
    boolean encode;

    @CommandLine.Option(names = {"--predict"}, description = "Predict")
    boolean predict;

    @CommandLine.Option(names = {"--model"}, paramLabel = "MODEL_FILE", description = "Model file")
    File modelFile;

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

    @CommandLine.Option(names = {"--example-decoder"}, paramLabel = "TODO", description = "TODO")
    String exampleDecoderName;

    @CommandLine.Option(names = {"--flatmap-example-decoder"}, paramLabel = "TODO", description = "TODO")
    String flatmapExampleDecoderName;

    @CommandLine.Option(names = {"--example-encoder"}, paramLabel = "TODO", description = "TODO")
    public String exampleEncoderName;

    @CommandLine.Option(names = {"--example-scorer"}, paramLabel = "TODO", description = "TODO")
    public String scorerName;





    @CommandLine.Option(names = {"-h", "--help"}, usageHelp = true, description = "display a help message")
    private boolean helpRequested = false;
}