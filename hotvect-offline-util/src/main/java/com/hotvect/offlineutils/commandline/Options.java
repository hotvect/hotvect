package com.hotvect.offlineutils.commandline;

import java.io.File;
import java.util.*;

public class Options {
    OfflineAlgorithmSource algorithmSource;
    public int maxThreads = -1;

    public int batchSize = -1;

    public int queueLength = -1;
    public int readQueueLength = -1;
    public int writeQueueLength = -1;

    public boolean verbose;

    public boolean logFeatures;

    public boolean includeFeatureStoreResponses;

    public boolean ordered;

    public boolean unordered;

    public boolean requireUnorderedOutput;

    public int writerNumShards = -1;

    public Map<String, List<File>> sourceFiles = new HashMap<>();

    public List<SourceDestMapping> sourceDestMappings = new ArrayList<>();

    public File schemaDescriptionFile;

    public File destinationFile;

    public File metadataLocation = new File("metadata");

    public int samples = -1;
    public int samplePoolSize = -1;

    public double targetRps = -1.0;
    public double targetThroughputFraction = 0.8;
    public String performanceTestWorkloadMode;

    @Override
    public String toString() {
        return "Options{" +
                "algorithmSource=" + algorithmSource +
                ", maxThreads=" + maxThreads +
                ", batchSize=" + batchSize +
                ", queueLength=" + queueLength +
                ", readQueueLength=" + readQueueLength +
                ", writeQueueLength=" + writeQueueLength +
                ", verbose=" + verbose +
                ", logFeatures=" + logFeatures +
                ", includeFeatureStoreResponses=" + includeFeatureStoreResponses +
                ", ordered=" + ordered +
                ", unordered=" + unordered +
                ", requireUnorderedOutput=" + requireUnorderedOutput +
                ", writerNumShards=" + writerNumShards +
                ", sourceFiles=" + sourceFiles +
                ", sourceDestMappings=" + sourceDestMappings +
                ", schemaDescriptionFile=" + schemaDescriptionFile +
                ", destinationFile=" + destinationFile +
                ", metadataLocation=" + metadataLocation +
                ", samples=" + samples +
                ", samplePoolSize=" + samplePoolSize +
                ", targetRps=" + targetRps +
                ", targetThroughputFraction=" + targetThroughputFraction +
                ", performanceTestWorkloadMode='" + performanceTestWorkloadMode + '\'' +
                '}';
    }
}
