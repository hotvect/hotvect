package com.eshioji.hotvect.commandline;


import com.codahale.metrics.MetricRegistry;
import com.eshioji.hotvect.hotdeploy.CloseableAlgorithmHandle;
import com.eshioji.hotvect.util.CpuIntensiveFileMapper;

import java.util.HashMap;
import java.util.Map;

public class EncodeTask<R> extends Task<R> {
    public EncodeTask(Options opts, MetricRegistry metricRegistry, CloseableAlgorithmHandle<R> closeableAlgorithmHandle) throws Exception {
        super(opts, metricRegistry, closeableAlgorithmHandle);
    }

    @Override
    protected Map<String, String> perform() throws Exception {
        var exampleDecoder = super.closeableAlgorithmHandle.getExampleDecoder();
        var exampleEncoder = super.closeableAlgorithmHandle.getExampleEncoder();

        var transformation = exampleDecoder.andThen(s -> s.map(exampleEncoder));
        var processor = CpuIntensiveFileMapper.mapper(metricRegistry, opts.sourceFile, opts.destinationFile, transformation);

        processor.run();
        Map<String, String> metadata = new HashMap<>();
        return metadata;
    }
}
