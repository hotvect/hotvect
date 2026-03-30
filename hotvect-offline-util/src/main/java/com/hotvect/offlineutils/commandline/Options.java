package com.hotvect.offlineutils.commandline;

import java.io.File;
import java.util.*;

public class Options {
    public int maxThreads = -1;
    public List<File> additionalJarFiles = new ArrayList<>();

    public int batchSize = -1;

    public int queueLength = -1;

    public File algorithmJar;

    public String algorithmDefinition;


    public File parameters;

    public boolean verbose;

    public boolean logFeatures;

    public boolean includeFeatureStoreResponses;

    public boolean ordered;

    public int writerNumShards = -1;

    public Map<String, List<File>> sourceFiles = new HashMap<>();

    public File schemaDescriptionFile;

    public File destinationFile;

    public File metadataLocation = new File("metadata");

    public int samples = -1;

    public double targetRps = -1.0;
    public double targetThroughputFraction = 0.8;
    public String performanceTestWorkloadMode;

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
                ", verbose=" + verbose +
                ", logFeatures=" + logFeatures +
                ", includeFeatureStoreResponses=" + includeFeatureStoreResponses +
                ", ordered=" + ordered +
                ", writerNumShards=" + writerNumShards +
                ", sourceFiles=" + sourceFiles +
                ", schemaDescriptionFile=" + schemaDescriptionFile +
                ", destinationFile=" + destinationFile +
                ", metadataLocation=" + metadataLocation +
                ", samples=" + samples +
                ", targetRps=" + targetRps +
                ", targetThroughputFraction=" + targetThroughputFraction +
                ", performanceTestWorkloadMode='" + performanceTestWorkloadMode + '\'' +
                '}';
    }
}
