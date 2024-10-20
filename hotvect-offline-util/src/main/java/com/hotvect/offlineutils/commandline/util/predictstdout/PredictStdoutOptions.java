package com.hotvect.offlineutils.commandline.util.predictstdout;

import picocli.CommandLine;
import java.io.File;

public class PredictStdoutOptions {

    @CommandLine.Option(names = {"--algorithm-jar"}, description = "The jar containing the algorithm", required = true)
    public File algorithmJar;

    @CommandLine.Option(names = {"--algorithm-definition"}, description = "Either the algorithm name as string, or a path to a JSON file containing the custom algorithm definition.", required = true)
    public String algorithmDefinition;

    @CommandLine.Option(names = {"--parameters"}, description = "Path to parameter package file to be used.")
    public File parameters;

    @CommandLine.Option(names = {"--log-file"}, description = "Path to the log file.", defaultValue = "hotvect-predictstdout.log")
    public File logFile;

    @CommandLine.Option(names = {"-h", "--help"}, usageHelp = true, description = "Display a help message")
    public boolean helpRequested = false;

    @Override
    public String toString() {
        return "PredictStdoutOptions{" +
                "algorithmJar=" + algorithmJar +
                ", algorithmDefinition='" + algorithmDefinition + '\'' +
                ", parameters=" + parameters +
                ", logFile=" + logFile +
                ", helpRequested=" + helpRequested +
                '}';
    }
}