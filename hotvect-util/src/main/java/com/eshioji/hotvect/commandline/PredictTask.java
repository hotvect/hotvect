package com.eshioji.hotvect.commandline;


import com.codahale.metrics.MetricRegistry;
import com.eshioji.hotvect.api.data.raw.Example;
import com.eshioji.hotvect.hotdeploy.AlgorithmDefinition;
import com.eshioji.hotvect.util.CpuIntensiveFileMapper;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

public class PredictTask<R> extends Task<R> {
    public PredictTask(Options opts, MetricRegistry metricRegistry, AlgorithmDefinition<R> algorithmDefinition) throws Exception {
        super(opts, metricRegistry, algorithmDefinition);
    }

    @Override
    protected Map<String, String> perform() throws Exception {
        var exampleDecoder = super.algorithmDefinition.getExampleDecoder();
        var scorer = super.algorithmDefinition.getScorer();
        Function<Example<R>, String> scoreOutputFormatter = x ->
                scorer.applyAsDouble(x.getRecord()) + "," + x.getTarget();

        var transformation =
                exampleDecoder.andThen(i -> i.map(scoreOutputFormatter));

        var processor = CpuIntensiveFileMapper.mapper(metricRegistry, opts.sourceFile, opts.destinationFile, transformation);
        processor.run();
        return new HashMap<>();
    }
}
