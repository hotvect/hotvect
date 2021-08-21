package com.eshioji.hotvect.commandline;


import com.codahale.metrics.MetricRegistry;
import com.eshioji.hotvect.api.data.raw.Example;
import com.eshioji.hotvect.hotdeploy.CloseableAlgorithmHandle;
import com.eshioji.hotvect.util.CpuIntensiveFileMapper;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

public class PredictTask<R> extends Task<R> {
    public PredictTask(Options opts, MetricRegistry metricRegistry, CloseableAlgorithmHandle<R> closeableAlgorithmHandle) throws Exception {
        super(opts, metricRegistry, closeableAlgorithmHandle);
    }

    @Override
    protected Map<String, String> perform() throws Exception {
        var exampleDecoder = super.closeableAlgorithmHandle.getExampleDecoder();
        var scorer = super.closeableAlgorithmHandle.getScorer();
        Function<Example<R>, String> scoreOutputFormatter = x ->
                scorer.applyAsDouble(x.getRecord()) + "," + x.getTarget();

        var transformation =
                exampleDecoder.andThen(i -> i.map(scoreOutputFormatter));

        var processor = CpuIntensiveFileMapper.mapper(metricRegistry, opts.sourceFile, opts.destinationFile, transformation);
        processor.run();
        return new HashMap<>();
    }
}
