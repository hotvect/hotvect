package com.hotvect.offlineutils.commandline.util.flatmap;

import picocli.CommandLine;

import java.io.File;
import java.util.List;

public class FlatMapOptions {
    @CommandLine.Option(names = {"--max-threads"}, description = "Number of threads to be used for processing", defaultValue = "-1")
    public int maxThreads;
    @CommandLine.Option(names = {"--jars"}, description = "Jars that contain classes needed for processing", defaultValue = "", split = ",")
    public List<File> jars;

    @CommandLine.Option(names = {"--batch-size"}, description = "Size of the micro-batch used while processing", defaultValue = "-1")
    public int batchSize;

    @CommandLine.Option(names = {"--queue-length"}, description = "Size of the queues used for file IO", defaultValue = "-1")
    public int queueLength;

    @CommandLine.Option(names = {"--hyper-parameter"}, description = "A JSON, or a JSON file containing any hyperparameter to pass to the flatmap function factory")
    public String hyperParameter;

    @CommandLine.Option(names = {"--flatmap-class"}, description = "The name of the class that contains the flat mapping function", required = true)
    public String flatmapFunFactoryClassname;

    @CommandLine.Option(names = {"--source"}, paramLabel = "SOURCE_FILE", description = "The data source files", split = ",", required = true)
    public List<File> sourceFile;

    @CommandLine.Option(names = {"--dest"}, paramLabel = "DESTINATION_FILE", description = "Destination file where the outputs will be written", required = true)
    public File destinationFile;

    @CommandLine.Option(names = {"--meta-data"}, paramLabel = "Metadata location", description = "Location of the metadata file which includes logging data from the processing.", defaultValue = "metadata.json")
    public File metadataLocation;

    @CommandLine.Option(names = {"--samples"}, paramLabel = "Number of records to use", description = "Metadata location", defaultValue = "-1")
    public int samples;
    @CommandLine.Option(names = {"-h", "--help"}, usageHelp = true, description = "Display a help message")
    private boolean helpRequested = false;
}
