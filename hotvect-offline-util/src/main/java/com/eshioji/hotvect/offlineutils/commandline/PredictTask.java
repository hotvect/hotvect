package com.eshioji.hotvect.offlineutils.commandline;


import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.eshioji.hotvect.api.algodefinition.common.RewardFunction;
import com.eshioji.hotvect.api.algorithms.Algorithm;
import com.eshioji.hotvect.api.algorithms.Ranker;
import com.eshioji.hotvect.api.codec.common.ExampleDecoder;
import com.eshioji.hotvect.api.data.common.Example;
import com.eshioji.hotvect.api.data.ranking.RankingExample;
import com.eshioji.hotvect.api.data.scoring.ScoringExample;
import com.eshioji.hotvect.api.algorithms.Scorer;
import com.eshioji.hotvect.api.vectorization.Vectorizer;
import com.eshioji.hotvect.core.util.ListTransform;
import com.eshioji.hotvect.offlineutils.util.CpuIntensiveFileMapper;
import com.eshioji.hotvect.onlineutils.hotdeploy.util.ZipFiles;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.zip.ZipFile;

public class PredictTask<EXAMPLE extends Example, ALGO extends Algorithm, OUTCOME, VEC extends Vectorizer> extends Task<EXAMPLE, ALGO, OUTCOME>  {
    private static final Logger logger = LoggerFactory.getLogger(PredictTask.class);
    private final RewardFunction<OUTCOME> rewardFunction = null;

    protected PredictTask(OfflineTaskContext offlineTaskContext) {
        super(offlineTaskContext);
    }


    @Override
    protected Map<String, String> perform() throws Exception {
        ExampleDecoder<EXAMPLE> scoringExampleDecoder = getPredictDecoder();

        ALGO algo;
        try(ZipFile parameterFile = new ZipFile(super.offlineTaskContext.getOptions().parameters)){
            Map<String, InputStream> parameters = ZipFiles.parameters(parameterFile);
            logger.info("Parameters loaded {}",parameters.keySet());
            algo = getScorer(parameters);
        }

        Function<EXAMPLE, String> scoreOutputFormatter = getOutputFormatter(algo);

        Function<String, List<String>> transformation =
                scoringExampleDecoder.andThen(i -> ListTransform.map(i, scoreOutputFormatter));

        CpuIntensiveFileMapper processor = CpuIntensiveFileMapper.mapper(
                super.offlineTaskContext.getMetricRegistry(),
                super.offlineTaskContext.getOptions().sourceFile,
                super.offlineTaskContext.getOptions().destinationFile, transformation);
        processor.run();

        Map<String, String> metadata = new HashMap<>();
        Meter mainMeter = super.offlineTaskContext.getMetricRegistry().meter(MetricRegistry.name(CpuIntensiveFileMapper.class, "record"));
        metadata.put("mean_throughput", String.valueOf(mainMeter.getMeanRate()));
        metadata.put("total_record_count", String.valueOf(mainMeter.getCount()));

        return metadata;

    }

    private Function<EXAMPLE, String> getOutputFormatter(ALGO algo) {
        if (algo instanceof Scorer<?>){
            return x -> {
                var scorer = (Scorer)algo;
                var example = (ScoringExample)x;
                var score = scorer.applyAsDouble(example.getRecord());
                return score + "," + this.rewardFunction.applyAsDouble((OUTCOME) example.getOutcome());
            };
        } else if(algo instanceof Ranker<?, ?>){
            var om = new ObjectMapper();
            Function<Object, String> asJson = x -> {
                try {
                    return om.writeValueAsString(x);
                } catch (JsonProcessingException e) {
                    throw new RuntimeException(e);
                }
            };
            return x -> {
                var ranker = (Ranker) algo;
                var example = (RankingExample) x;
                var decisions = ranker.apply(example.getRequest());
                return asJson.apply(decisions);
            };
        }

        x ->
                algo.applyAsDouble(x.getRecord()) + "," + x.getTarget();
    }

}
