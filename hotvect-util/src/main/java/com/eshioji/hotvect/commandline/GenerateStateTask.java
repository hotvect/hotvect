package com.eshioji.hotvect.commandline;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.eshioji.hotvect.api.codec.ExampleDecoder;
import com.eshioji.hotvect.api.codec.ExampleEncoder;
import com.eshioji.hotvect.core.util.ListTransform;
import com.eshioji.hotvect.util.CpuIntensiveAggregator;
import com.eshioji.hotvect.util.CpuIntensiveFileMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public abstract class GenerateStateTask extends Task {
    public GenerateStateTask(Options opts, MetricRegistry metricRegistry) {
        super(opts, metricRegistry);
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
