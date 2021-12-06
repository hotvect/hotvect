package com.eshioji.hotvect.commandline;

import com.codahale.metrics.Histogram;
import com.codahale.metrics.MetricRegistry;
import com.eshioji.hotvect.api.*;
import com.eshioji.hotvect.api.codec.ExampleDecoder;
import com.eshioji.hotvect.api.codec.ExampleEncoder;
import com.eshioji.hotvect.api.scoring.Scorer;
import com.eshioji.hotvect.api.vectorization.Vectorizer;
import com.eshioji.hotvect.util.VerboseCallable;
import com.eshioji.hotvect.util.VerboseRunnable;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public abstract class Task<R> extends VerboseCallable<Map<String, String>>{
    protected final Logger LOGGER = LoggerFactory.getLogger(this.getClass());
    protected final Options opts;
    protected final MetricRegistry metricRegistry;


    protected final AlgorithmDefinition algorithmDefinition;

    public Task(Options opts, MetricRegistry metricRegistry, AlgorithmDefinition algorithmDefinition) throws Exception {
        this.opts = opts;
        this.metricRegistry = metricRegistry;
        this.algorithmDefinition = algorithmDefinition;
    }

    protected abstract Map<String, String> perform() throws Exception;

    @Override
    protected Map<String, String> doCall() throws Exception {
        LOGGER.info("Running {} from {} to {}", this.getClass().getSimpleName(), opts.sourceFile, opts.destinationFile);

        Histogram memoryUsage = metricRegistry.histogram("memory_usage");
        ScheduledExecutorService memoryUsageReporter = Executors.newSingleThreadScheduledExecutor(new ThreadFactoryBuilder().setNameFormat("memory-reporter").build());
        Runnable reportMemoryUsage = new VerboseRunnable() {
            @Override
            protected void doRun() throws Exception {
                // Best estimate of current RAM usage
                memoryUsage.update(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
            }
        };
        memoryUsageReporter.schedule(reportMemoryUsage, 5, TimeUnit.SECONDS);

        Map<String, String> metadata = perform();
        metadata.put("task_type", this.getClass().getSimpleName());
        metadata.put("metadata_location", opts.metadataLocation.toString());
        metadata.put("destination_file", opts.destinationFile.toString());
        metadata.put("source_file", opts.sourceFile.toString());
        metadata.put("algorithm_name", algorithmDefinition.getAlgorithmName());
        metadata.put("algorithm_definition", algorithmDefinition.toString());
        if (opts.parameters != null){
            metadata.put("parameters", opts.parameters);
        }

        metadata.put("memory_usage_95pct", String.valueOf(memoryUsage.getSnapshot().get95thPercentile()));
        metadata.put("memory_usage_75pct", String.valueOf(memoryUsage.getSnapshot().get75thPercentile()));

        memoryUsageReporter.shutdownNow();
        return metadata;
    }

    private Vectorizer<R> getVectorizer(Map<String, InputStream> parameter) throws Exception {
        String factoryName = this.algorithmDefinition.getVectorizerFactoryName();
        Optional<JsonNode> hyperparameter = this.algorithmDefinition.getVectorizerParameter();
        return ((VectorizerFactory<R>)Class.forName(factoryName).getDeclaredConstructor().newInstance())
                .apply(hyperparameter, parameter);
    }

    protected ExampleDecoder<R> getTrainDecoder() throws Exception {
        String factoryName = this.algorithmDefinition.getDecoderFactoryName();
        Optional<JsonNode> parameter = this.algorithmDefinition.getTrainDecoderParameter();
        return ((ExampleDecoderFactory<R>)Class.forName(factoryName).getDeclaredConstructor().newInstance())
                .apply(parameter);
    }

    protected ExampleEncoder<R> getTrainEncoder(Map<String, InputStream> parameter) throws Exception {
        String factoryName = this.algorithmDefinition.getEncoderFactoryName();
        Optional<JsonNode> hyperparameter = this.algorithmDefinition.getTrainDecoderParameter();
        return ((ExampleEncoderFactory<R>)Class.forName(factoryName).getDeclaredConstructor().newInstance())
                .apply(getVectorizer(parameter), hyperparameter);
    }

    protected ExampleDecoder<R> getPredictDecoder() throws Exception {
        String factoryName = this.algorithmDefinition.getDecoderFactoryName();
        Optional<JsonNode> hyperparameter = this.algorithmDefinition.getPredictDecoderParameter();
        return ((ExampleDecoderFactory<R>)Class.forName(factoryName).getDeclaredConstructor().newInstance())
                .apply(hyperparameter);
    }

    protected Scorer<R> getScorer(Map<String, InputStream> parameter) throws Exception {
        String factoryName = this.algorithmDefinition.getScorerFactoryName();
        return ((ScorerFactory<R>)Class.forName(factoryName).getDeclaredConstructor().newInstance())
                .apply(getVectorizer(parameter), parameter);
    }


}
