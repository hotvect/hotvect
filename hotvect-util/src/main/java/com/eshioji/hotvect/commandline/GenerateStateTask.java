package com.eshioji.hotvect.commandline;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Slf4jReporter;
import com.eshioji.hotvect.api.AlgorithmDefinition;
import com.eshioji.hotvect.core.transform.FeatureState;
import com.eshioji.hotvect.util.CpuIntensiveFileAggregator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Charsets;
import com.google.common.io.Files;

import java.io.*;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.zip.GZIPOutputStream;

public abstract class GenerateStateTask<R> extends Task<R> {
    public GenerateStateTask(Options opts, MetricRegistry metricRegistry, AlgorithmDefinition algorithmDefinition) throws Exception {
        super(opts, metricRegistry, algorithmDefinition);
    }

    @Override
    protected Map<String, String> doCall() throws Exception {
        LOGGER.info("Running {} from {} to {} using ", this.getClass().getSimpleName(), opts.sourceFile, opts.destinationFile);
        Map<String, String> metadata = perform();
        metadata.put("task_type", this.getClass().getSimpleName());
        metadata.put("metadata_location", opts.metadataLocation.toString());
        metadata.put("destination_file", opts.destinationFile.toString());
        metadata.put("source_file", opts.sourceFile.toString());
        metadata.put("training_file", opts.trainingFile.toString());
        metadata.put("state_generator", opts.stateDefinition);
        return metadata;
    }

    protected abstract Map<String, String> perform() throws Exception;

    protected <Z> Z runAggregation(File source, Supplier<Z> init, BiFunction<Z, String, Z> merge) throws Exception {
        MetricRegistry metricRegistry = new MetricRegistry();
        Z ret = CpuIntensiveFileAggregator.aggregator(
                metricRegistry,
                source,
                init,
                merge
        ).call();
        Slf4jReporter.forRegistry(metricRegistry).build().report();
        return ret;

    }

    protected <S extends FeatureState> long serializeToDestination(BiConsumer<OutputStream, S> serializer, S featureState){
        String ext = Files.getFileExtension(opts.destinationFile.toPath().getFileName().toString());
        boolean isDestGzip = "gz".equalsIgnoreCase(ext);

        try (FileOutputStream file = new FileOutputStream(opts.destinationFile);
             OutputStream sink = isDestGzip ? new GZIPOutputStream(file) : file;
        ) {
            serializer.accept(sink, featureState);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return opts.destinationFile.length();

    }

}
