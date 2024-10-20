package com.hotvect.offlineutils.commandline;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine;
import picocli.CommandLine.Model.ArgSpec;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParameterException;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

public class Options {
    @CommandLine.Option(names = {"--max-threads"}, description = "Number of threads to be used for processing", defaultValue = "-1")
    public int maxThreads;
    @CommandLine.Option(names = {"--additional-jars"}, description = "Additional jars that should be made available during processing", defaultValue = "-1", split = ",")
    public List<File> additionalJarFiles;

    @CommandLine.Option(names = {"--batch-size"}, description = "Size of the micro-batch used while processing", defaultValue = "-1")
    public int batchSize;

    @CommandLine.Option(names = {"--queue-length"}, description = "Size of the queues used for file IO", defaultValue = "-1")
    public int queueLength;

    @CommandLine.Option(names = {"--algorithm-jar"}, description = "The jar containing the algorithm")
    public File algorithmJar;

    @CommandLine.Option(names = {"--algorithm-definition"}, description = "Either the algorithm name as string, or a path to a JSON file containing the custom algorithm definition.")
    public String algorithmDefinition;


    @CommandLine.Option(names = {"--parameters"}, description = "Path to parameter package file to be used.")
    public File parameters;

    @CommandLine.Option(names = {"--encode"}, description = "Extract features from source data files and encode it.")
    public boolean encode;

    @CommandLine.Option(names = {"--list-available-transformations"}, description = "List available transformations in the given algorithm jar.")
    public boolean listAvailableTransformations;


    @CommandLine.Option(names = {"--predict"}, description = "Perform prediction (test) on the source file.")
    public boolean predict;

    @CommandLine.Option(names = {"--audit"}, description = "Produce audit output from source file.")
    public boolean audit;

    @CommandLine.Option(names = {"--ordered"}, description = "Whether the order in the output should strictly follow the order in the input")
    public boolean ordered;

    @CommandLine.Option(names = {"--performance-test"}, description = "Perform a performance test")
    public boolean performanceTest;

    @CommandLine.Option(names = {"--generate-state"}, description = "Generate state using this class name")
    public String generateStateTask;

    @CommandLine.Option(names = {"--source"}, description = "The data source files. Format: [type=]file1,file2,file3...",
            parameterConsumer = SourceFileConsumer.class)
    public Map<String, List<File>> sourceFiles = new HashMap<>();

    private static class SourceFileConsumer implements CommandLine.IParameterConsumer {
        @Override
        public void consumeParameters(Stack<String> args, ArgSpec argSpec, CommandSpec commandSpec) {
            if (args.isEmpty()) {
                throw new ParameterException(commandSpec.commandLine(), "No value provided for --source");
            }

            Map<String, List<File>> sourceFiles = (Map<String, List<File>>) argSpec.getValue();
            if (sourceFiles != null && !sourceFiles.isEmpty()) {
                throw new ParameterException(commandSpec.commandLine(), "--source option can only be specified once");
            }

            String arg = args.pop();

            if (arg.startsWith("{") || arg.startsWith("[")) {
                // JSON input
                sourceFiles = parseJsonInput(arg, commandSpec);
            } else {
                // Comma-separated input
                sourceFiles = parseCommaSeparatedInput(arg);
            }

            argSpec.setValue(sourceFiles);
        }

        private Map<String, List<File>> parseJsonInput(String json, CommandSpec commandSpec) {
            ObjectMapper mapper = new ObjectMapper();
            try {
                if (json.startsWith("[")) {
                    // JSON array input
                    List<String> defaultFiles = mapper.readValue(json, new TypeReference<List<String>>() {});
                    Map<String, List<File>> result = new HashMap<>();
                    result.put("default", defaultFiles.stream().map(File::new).collect(Collectors.toList()));
                    return result;
                } else {
                    // JSON object input
                    Map<String, List<String>> typedInput = mapper.readValue(json, new TypeReference<>() {});
                    Map<String, List<File>> result = new HashMap<>();

                    for (Map.Entry<String, List<String>> entry : typedInput.entrySet()) {
                        List<File> files = new ArrayList<>();
                        for (String path : entry.getValue()) {
                            files.add(new File(path.trim()));
                        }
                        result.put(entry.getKey(), files);
                    }
                    return result;
                }
            } catch (Exception e) {
                throw new ParameterException(commandSpec.commandLine(), "Invalid JSON format for --source", e);
            }
        }

        private Map<String, List<File>> parseCommaSeparatedInput(String input) {
            List<File> files = new ArrayList<>();
            for (String path : input.split(",")) {
                files.add(new File(path.trim()));
            }
            return Collections.singletonMap("default", files);
        }
    }

    @CommandLine.Option(names = {"--dest-schema-description"}, paramLabel = "DEST_SCHEMA_DESCRIPTION_FILE", description = "The file to which the schema description of the destination file will be written")
    public File schemaDescriptionFile;

    @CommandLine.Option(names = {"--dest"}, paramLabel = "DESTINATION_FILE", description = "Destination file where the outputs will be written")
    public File destinationFile;

    @CommandLine.Option(names = {"--meta-data"}, paramLabel = "Metadata location", description = "Location of the metadata file which includes logging data from the processing.", defaultValue = "metadata.json")
    public File metadataLocation;

    @CommandLine.Option(names = {"--samples"}, paramLabel = "Number of records to use", description = "Metadata location", defaultValue = "-1")
    public int samples;

    @CommandLine.Option(names = {"--collect-memoization-statistics"}, description = "Collect memoization statistics")
    public boolean collectMemoizationStatistics = false;

    @CommandLine.Option(names = {"-h", "--help"}, usageHelp = true, description = "Display a help message")
    private boolean helpRequested = false;

    @Override
    public String toString() {
        return "Options{" +
                "maxThreads=" + maxThreads +
                ", additionalJarFiles=" + additionalJarFiles +
                ", batchSize=" + batchSize +
                ", queueLength=" + queueLength +
                ", algorithmJar=" + algorithmJar +
                ", algorithmDefinition='" + algorithmDefinition + '\'' +
                ", parameters=" + parameters +
                ", encode=" + encode +
                ", listAvailableTransformations=" + listAvailableTransformations +
                ", predict=" + predict +
                ", audit=" + audit +
                ", ordered=" + ordered +
                ", performanceTest=" + performanceTest +
                ", generateStateTask='" + generateStateTask + '\'' +
                ", sourceFiles=" + sourceFiles +
                ", schemaDescriptionFile=" + schemaDescriptionFile +
                ", destinationFile=" + destinationFile +
                ", metadataLocation=" + metadataLocation +
                ", samples=" + samples +
                ", collectMemoizationStatistics=" + collectMemoizationStatistics +
                ", helpRequested=" + helpRequested +
                '}';
    }
}
