package com.eshioji.hotvect.offlineutils.commandline;


import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.eshioji.hotvect.api.algodefinition.common.RewardFunction;
import com.eshioji.hotvect.api.algorithms.Algorithm;
import com.eshioji.hotvect.api.algorithms.Ranker;
import com.eshioji.hotvect.api.codec.common.ExampleDecoder;
import com.eshioji.hotvect.api.data.common.Example;
import com.eshioji.hotvect.api.data.ranking.RankingExample;
import com.eshioji.hotvect.api.algorithms.Scorer;
import com.eshioji.hotvect.api.vectorization.Vectorizer;
import com.eshioji.hotvect.core.util.ListTransform;
import com.eshioji.hotvect.offlineutils.export.RankingResultFormatter;
import com.eshioji.hotvect.offlineutils.export.ScoringResultFormatter;
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

// TODO fix name
public class PredictTask<EXAMPLE extends Example, ALGO extends Algorithm, OUTCOME, VEC extends Vectorizer> extends Task<EXAMPLE, ALGO, OUTCOME>  {
    private static final Logger logger = LoggerFactory.getLogger(PredictTask.class);
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

        RewardFunction<OUTCOME> rewardFunction = getRewardFunction();

        Function<EXAMPLE, String> algorithmOutputformatter = getOutputFormatter(algo, rewardFunction);

        Function<String, List<String>> transformation =
                scoringExampleDecoder.andThen(i -> ListTransform.map(i, algorithmOutputformatter));

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

    private Function<EXAMPLE, String> getOutputFormatter(ALGO algo, RewardFunction<OUTCOME> rewardFunction) {
        if (algo instanceof Scorer<?>){
            Scorer<?> scorer = (Scorer<?>) algo;
            return new ScoringResultFormatter().apply(rewardFunction, scorer);
        } else if(algo instanceof Ranker<?, ?>){
            Ranker<?,?> ranker = (Ranker<?, ?>)algo;
            return new RankingResultFormatter().apply(rewardFunction, ranker);
        } else {
            throw new AssertionError();
        }
    }

}
