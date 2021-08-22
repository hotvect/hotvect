package com.eshioji.hotvect.commandline;


import com.codahale.metrics.MetricRegistry;
import com.eshioji.hotvect.api.AlgorithmDefinition;
import com.eshioji.hotvect.api.codec.ExampleDecoder;
import com.eshioji.hotvect.api.codec.ExampleEncoder;
import com.eshioji.hotvect.util.CpuIntensiveFileMapper;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

public class EncodeTask<R> extends Task<R> {
    public EncodeTask(Options opts, MetricRegistry metricRegistry, AlgorithmDefinition algorithmDefinition) throws Exception {
        super(opts, metricRegistry, algorithmDefinition);
    }

    @Override
    protected Map<String, String> perform() throws Exception {
        ExampleDecoder<R> exampleDecoder = instantiate(super.algorithmDefinition.getExampleDecoderFactoryClassName());
        ExampleEncoder<R> exampleEncoder = instantiate(super.algorithmDefinition.getExampleEncoderFactoryClassName());

        var transformation = exampleDecoder.andThen(s -> s.map(exampleEncoder));
        var processor = CpuIntensiveFileMapper.mapper(metricRegistry, opts.sourceFile, opts.destinationFile, transformation);

        processor.run();
        Map<String, String> metadata = new HashMap<>();
        metadata.put("example_decoder", exampleDecoder.getClass().getCanonicalName());
        metadata.put("example_encoder", exampleEncoder.getClass().getCanonicalName());
        return metadata;
    }



}
