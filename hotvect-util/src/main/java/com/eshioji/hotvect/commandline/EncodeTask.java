package com.eshioji.hotvect.commandline;


import com.codahale.metrics.MetricRegistry;
import com.eshioji.hotvect.api.ExampleEncoderFactory;
import com.eshioji.hotvect.api.codec.ExampleEncoder;
import com.eshioji.hotvect.util.CpuIntensiveFileMapper;

import java.util.HashMap;
import java.util.Map;

public class EncodeTask<R> extends Task<R> {
    private final ExampleEncoder<R> exampleEncoder;
    public EncodeTask(Options opts, MetricRegistry metricRegistry) throws Exception {
        super(opts, metricRegistry);
        ExampleEncoderFactory<R> eef = (ExampleEncoderFactory<R>) Class.forName(opts.exampleEncoderName).getDeclaredConstructor().newInstance();
        this.exampleEncoder = eef.get();

    }

    @Override
    protected Map<String, String> perform() throws Exception {
        CpuIntensiveFileMapper processor;
        if (super.exampleDecoder != null){
            var transformation = super.exampleDecoder.andThen(this.exampleEncoder);
            processor = CpuIntensiveFileMapper.mapper(metricRegistry, opts.sourceFile, opts.destinationFile, transformation);
        } else {
            var transformation = super.flatmapExampleDecoder.andThen(i -> i.map(this.exampleEncoder));
            processor = CpuIntensiveFileMapper.flatMapper(metricRegistry, opts.sourceFile, opts.destinationFile, transformation);
        }
        processor.run();
        Map<String, String> metadata = new HashMap<>();
        metadata.put("example_encoder", opts.exampleEncoderName);
        return metadata;
    }
}
