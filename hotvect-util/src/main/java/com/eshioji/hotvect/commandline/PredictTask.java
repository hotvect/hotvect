package com.eshioji.hotvect.commandline;


import com.codahale.metrics.MetricRegistry;
import com.eshioji.hotvect.api.AlgorithmDefinition;
import com.eshioji.hotvect.api.codec.ExampleDecoder;
import com.eshioji.hotvect.api.data.raw.Example;
import com.eshioji.hotvect.api.scoring.Scorer;
import com.eshioji.hotvect.core.util.ListTransform;
import com.eshioji.hotvect.util.CpuIntensiveFileMapper;
import com.google.common.io.Files;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class PredictTask<R> extends Task<R> {
    public PredictTask(Options opts, MetricRegistry metricRegistry, AlgorithmDefinition algorithmDefinition) throws Exception {
        super(opts, metricRegistry, algorithmDefinition);
    }

    @Override
    protected Map<String, String> perform() throws Exception {
        ExampleDecoder<R> exampleDecoder = instantiate(algorithmDefinition.getExampleDecoderFactoryClassName());

        Readable parameters = Files.newReader(new File(opts.modelParameterFile), StandardCharsets.UTF_8);

        Scorer<R> scorer = instantiate(algorithmDefinition.getExampleScorerFactoryClassName(), parameters);
        Function<Example<R>, String> scoreOutputFormatter = x ->
                scorer.applyAsDouble(x.getRecord()) + "," + x.getTarget();

        Function<String, List<String>> transformation =
                exampleDecoder.andThen(i -> ListTransform.map(i, scoreOutputFormatter));

        CpuIntensiveFileMapper processor = CpuIntensiveFileMapper.mapper(metricRegistry, opts.sourceFile, opts.destinationFile, transformation);
        processor.run();
        return new HashMap<>();
    }

}
