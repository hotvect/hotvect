package com.eshioji.hotvect.commandline;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Slf4jReporter;
import com.eshioji.hotvect.api.AlgorithmDefinition;
import com.eshioji.hotvect.api.featurestate.FeatureState;
import com.eshioji.hotvect.util.CpuIntensiveFileAggregator;

import java.io.*;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Supplier;

public abstract class GenerateStateTask<R> extends Task<R> {

    protected GenerateStateTask(OfflineTaskContext offlineTaskContext) {
        super(offlineTaskContext);
    }

    @Override
    protected Map<String, String> doCall() throws Exception {
        LOGGER.info("Running {} from {} to {} using ", this.getClass().getSimpleName(), this.offlineTaskContext.getOptions().sourceFile, this.offlineTaskContext.getOptions().destinationFile);
        Map<String, String> metadata = perform();
        metadata.put("task_type", this.getClass().getSimpleName());
        metadata.put("metadata_location", this.offlineTaskContext.getOptions().metadataLocation.toString());
        metadata.put("destination_file", this.offlineTaskContext.getOptions().destinationFile.toString());
        metadata.put("source_file", this.offlineTaskContext.getOptions().sourceFile.toString());
        metadata.put("training_file", this.offlineTaskContext.getOptions().trainingFile.toString());
        metadata.put("state_generator", this.offlineTaskContext.getOptions().stateDefinition);
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
        try (FileOutputStream file = new FileOutputStream(this.offlineTaskContext.getOptions().destinationFile)) {
            serializer.accept(file, featureState);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return this.offlineTaskContext.getOptions().destinationFile.length();

    }

}
