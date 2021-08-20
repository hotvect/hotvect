package com.eshioji.hotvect.commandline;


import com.codahale.metrics.MetricRegistry;
import com.eshioji.hotvect.api.ScorerFactory;
import com.eshioji.hotvect.api.data.raw.Example;
import com.eshioji.hotvect.api.scoring.Scorer;
import com.eshioji.hotvect.util.CpuIntensiveFileMapper;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

public class PredictTask<R> extends Task<R> {
    private final Scorer<R> scorer;

    public PredictTask(Options opts, MetricRegistry metricRegistry) throws Exception {
        super(opts, metricRegistry);
        ScorerFactory<R> eef = (ScorerFactory<R>) Class.forName(opts.exampleEncoderName).getDeclaredConstructor().newInstance();
        this.scorer = eef.get();
    }

    @Override
    protected Map<String, String> perform() throws Exception {
        Function<Example<R>, String> scoreOutputFormatter = x ->
                this.scorer.applyAsDouble(x.getRecord()) + "," + x.getTarget();

        var transformation =
                super.exampleDecoder.andThen(i -> i.map(scoreOutputFormatter));

        var processor = CpuIntensiveFileMapper.mapper(metricRegistry, opts.sourceFile, opts.destinationFile, transformation);
        processor.run();
        Map<String, String> metadata = new HashMap<>();
        metadata.put("scorer", opts.scorerName);
        return metadata;
    }
}
