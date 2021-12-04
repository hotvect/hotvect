package com.eshioji.hotvect.commandline;

import com.codahale.metrics.MetricRegistry;
import com.eshioji.hotvect.api.AlgorithmDefinition;

import java.util.Map;

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
        metadata.put("state_generator", opts.stateGenerator);
        return metadata;
    }

    protected abstract Map<String, String> perform() throws Exception;
}
