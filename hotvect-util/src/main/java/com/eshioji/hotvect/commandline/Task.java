package com.eshioji.hotvect.commandline;

import com.codahale.metrics.MetricRegistry;
import com.eshioji.hotvect.api.ExampleDecoderFactory;
import com.eshioji.hotvect.api.FlatmapExampleDecoderFactory;
import com.eshioji.hotvect.api.codec.ExampleDecoder;
import com.eshioji.hotvect.api.codec.FlatmapExampleDecoder;
import com.eshioji.hotvect.util.VerboseCallable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static com.google.common.base.Preconditions.checkState;

public abstract class Task<R> extends VerboseCallable<Map<String,String>> {
    protected static final Logger LOGGER = LoggerFactory.getLogger(Task.class);
    protected final Options opts;
    protected final MetricRegistry metricRegistry;
    protected final ExampleDecoder<R> exampleDecoder;
    protected final FlatmapExampleDecoder<R> flatmapExampleDecoder;

    public Task(Options opts, MetricRegistry metricRegistry) throws Exception {
        this.opts = opts;
        this.metricRegistry = metricRegistry;

        checkState((opts.exampleDecoderName != null) ^ (opts.flatmapExampleDecoderName != null),
                "Please specify an ExampleDecoderFactory or FlatmapExampleDecoderFactory");

        if (opts.exampleDecoderName != null){
            ExampleDecoderFactory<R> edf = (ExampleDecoderFactory<R>) Class.forName(opts.exampleDecoderName).getDeclaredConstructor().newInstance();
            this.exampleDecoder = edf.get();
            this.flatmapExampleDecoder = null;
            LOGGER.info("Using {} as mapping decoder", this.exampleDecoder.getClass().getSimpleName());
        } else {
            FlatmapExampleDecoderFactory<R> fedf = (FlatmapExampleDecoderFactory<R>) Class.forName(opts.exampleDecoderName).getDeclaredConstructor().newInstance();
            this.flatmapExampleDecoder = fedf.get();
            this.exampleDecoder = null;
            LOGGER.info("Using {} as flatmapping decoder", this.flatmapExampleDecoder);

        }



    }

    protected abstract Map<String, String> perform() throws Exception;

    @Override
    protected Map<String, String> doCall() throws Exception {
        LOGGER.info("Running {} from {} to {}", this.getClass().getSimpleName(), opts.sourceFile, opts.destinationFile);
        var metadata =  perform();
        metadata.put("task_type", opts.encode ? "encode" : "predict");
        metadata.put("metadata_location", opts.metadataLocation.toString());
        metadata.put("destination_file", opts.destinationFile.toString());
        metadata.put("source_file", opts.sourceFile.toString());
        metadata.put("sample_pct", String.valueOf(opts.samplePct));
        metadata.put("sample_seed", String.valueOf(opts.sampleSeed));

        if (opts.exampleDecoderName != null){
            metadata.put("example_decoder", opts.exampleDecoderName);
        }

        if (opts.flatmapExampleDecoderName != null){
            metadata.put("flatmap_example_decoder", opts.flatmapExampleDecoderName);
        }
        return metadata;
    }
}
