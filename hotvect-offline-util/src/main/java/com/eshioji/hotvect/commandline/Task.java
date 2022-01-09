package com.eshioji.hotvect.commandline;

import com.codahale.metrics.Histogram;
import com.eshioji.hotvect.api.algodefinition.regression.ExampleDecoderFactory;
import com.eshioji.hotvect.api.algodefinition.regression.ExampleEncoderFactory;
import com.eshioji.hotvect.api.algodefinition.regression.ScorerFactory;
import com.eshioji.hotvect.api.algodefinition.regression.VectorizerFactory;
import com.eshioji.hotvect.api.codec.regression.ExampleDecoder;
import com.eshioji.hotvect.api.codec.regression.ExampleEncoder;
import com.eshioji.hotvect.api.policies.Scorer;
import com.eshioji.hotvect.api.vectorization.regression.Vectorizer;
import com.eshioji.hotvect.core.concurrent.VerboseCallable;
import com.eshioji.hotvect.core.concurrent.VerboseRunnable;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public abstract class Task<RECORD> extends VerboseCallable<Map<String, String>> {
    protected final Logger LOGGER = LoggerFactory.getLogger(this.getClass());
    protected final OfflineTaskContext offlineTaskContext;

    protected Task(OfflineTaskContext offlineTaskContext) {
        this.offlineTaskContext = offlineTaskContext;
    }


    protected abstract Map<String, String> perform() throws Exception;

    @Override
    protected Map<String, String> doCall() throws Exception {
        LOGGER.info("Running {} from {} to {}", this.getClass().getSimpleName(), offlineTaskContext.getOptions().sourceFile, offlineTaskContext.getOptions().destinationFile);

        Histogram memoryUsage = this.offlineTaskContext.getMetricRegistry().histogram("memory_usage");
        ScheduledExecutorService memoryUsageReporter = exitingScheduledExecutor();
        Runnable reportMemoryUsage = new VerboseRunnable() {
            @Override
            protected void doRun() throws Exception {
                // Best estimate of current RAM usage
                memoryUsage.update(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
            }
        };
        memoryUsageReporter.schedule(reportMemoryUsage, 5, TimeUnit.SECONDS);
        try {

            Map<String, String> metadata = perform();
            metadata.put("algorithm_jar", offlineTaskContext.getOptions().algorithmJar);
            metadata.put("task_type", this.getClass().getSimpleName());
            metadata.put("metadata_location", offlineTaskContext.getOptions().metadataLocation.toString());
            metadata.put("destination_file", offlineTaskContext.getOptions().destinationFile.toString());
            metadata.put("source_file", offlineTaskContext.getOptions().sourceFile.toString());
            metadata.put("algorithm_name", offlineTaskContext.getAlgorithmDefinition().getAlgorithmName());
            metadata.put("algorithm_definition", offlineTaskContext.getAlgorithmDefinition().toString());
            if (offlineTaskContext.getOptions().parameters != null) {
                metadata.put("parameters", offlineTaskContext.getOptions().parameters);
            }
            metadata.put("memory_usage_95pct", String.valueOf(memoryUsage.getSnapshot().get95thPercentile()));
            metadata.put("memory_usage_75pct", String.valueOf(memoryUsage.getSnapshot().get75thPercentile()));
            return metadata;
        } finally {
            memoryUsageReporter.shutdownNow();
        }
    }

    private ScheduledExecutorService exitingScheduledExecutor() {
        return MoreExecutors.getExitingScheduledExecutorService(
                new ScheduledThreadPoolExecutor(1, new ThreadFactoryBuilder().setNameFormat("memory-reporter").build())
        );
    }

    protected Vectorizer<RECORD> getVectorizer(Map<String, InputStream> parameter) throws Exception {
        String factoryName = this.offlineTaskContext.getAlgorithmDefinition().getVectorizerFactoryName();
        Optional<JsonNode> hyperparameter = this.offlineTaskContext.getAlgorithmDefinition().getVectorizerParameter();
        return ((VectorizerFactory<RECORD>) loadClass(factoryName).getDeclaredConstructor().newInstance())
                .apply(hyperparameter, parameter);
    }

    protected ExampleDecoder<RECORD> getTrainDecoder() throws Exception {
        String factoryName = this.offlineTaskContext.getAlgorithmDefinition().getDecoderFactoryName();
        Optional<JsonNode> parameter = this.offlineTaskContext.getAlgorithmDefinition().getTrainDecoderParameter();
        return ((ExampleDecoderFactory<RECORD>) loadClass(factoryName).getDeclaredConstructor().newInstance())
                .apply(parameter);
    }

    protected ExampleEncoder<RECORD> getTrainEncoder(Vectorizer<RECORD> vectorizer) throws Exception {
        String factoryName = this.offlineTaskContext.getAlgorithmDefinition().getEncoderFactoryName();
        return ((ExampleEncoderFactory<RECORD>) loadClass(factoryName).getDeclaredConstructor().newInstance())
                .apply(vectorizer);
    }

    protected ExampleDecoder<RECORD> getPredictDecoder() throws Exception {
        String factoryName = this.offlineTaskContext.getAlgorithmDefinition().getDecoderFactoryName();
        Optional<JsonNode> hyperparameter = this.offlineTaskContext.getAlgorithmDefinition().getPredictDecoderParameter();
        return ((ExampleDecoderFactory<RECORD>) loadClass(factoryName).getDeclaredConstructor().newInstance())
                .apply(hyperparameter);
    }

    protected Scorer<RECORD> getScorer(Map<String, InputStream> parameter) throws Exception {
        String factoryName = this.offlineTaskContext.getAlgorithmDefinition().getScorerFactoryName();
        return ((ScorerFactory<RECORD>) loadClass(factoryName).getDeclaredConstructor().newInstance())
                .apply(getVectorizer(parameter), parameter);
    }

    protected final <T> Class<T> loadClass(String className) throws ClassNotFoundException {
        return (Class<T>) Class.forName(className, true, this.offlineTaskContext.getClassLoader());
    }


}
