package com.hotvect.offlineutils.commandline;


import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.hotvect.api.algodefinition.common.RewardFunction;
import com.hotvect.api.algorithms.Algorithm;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.codec.common.ExampleDecoder;
import com.hotvect.api.data.common.Example;
import com.hotvect.api.algorithms.Scorer;
import com.hotvect.api.vectorization.Vectorizer;
import com.hotvect.core.util.ListTransform;
import com.hotvect.offlineutils.export.RankingResultFormatter;
import com.hotvect.offlineutils.export.ScoringResultFormatter;
import com.hotvect.offlineutils.util.CpuIntensiveFileMapper;
import com.hotvect.onlineutils.hotdeploy.util.ZipFiles;
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
