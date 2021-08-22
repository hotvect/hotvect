package com.eshioji.hotvect.commandline;


import com.codahale.metrics.MetricRegistry;
import com.eshioji.hotvect.api.AlgorithmDefinition;
import com.eshioji.hotvect.api.codec.ExampleDecoder;
import com.eshioji.hotvect.api.data.raw.Example;
import com.eshioji.hotvect.api.scoring.Scorer;
import com.eshioji.hotvect.util.CpuIntensiveFileMapper;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

public class PredictTask<R> extends Task<R> {
    public PredictTask(Options opts, MetricRegistry metricRegistry, AlgorithmDefinition algorithmDefinition) throws Exception {
        super(opts, metricRegistry, algorithmDefinition);
    }

    @Override
    protected Map<String, String> perform() throws Exception {
        ExampleDecoder<R> exampleDecoder = instantiate(algorithmDefinition.getExampleDecoderFactoryClassName());
        Scorer<R> scorer = instantiate(algorithmDefinition.getExampleScorerFactoryClassName());
        Function<Example<R>, String> scoreOutputFormatter = x ->
                scorer.applyAsDouble(x.getRecord()) + "," + x.getTarget();

        var transformation =
                exampleDecoder.andThen(i -> i.map(scoreOutputFormatter));

        var processor = CpuIntensiveFileMapper.mapper(metricRegistry, opts.sourceFile, opts.destinationFile, transformation);
        processor.run();
        return new HashMap<>();
    }
}
