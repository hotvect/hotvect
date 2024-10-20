package com.hotvect.offlineutils.commandline;


import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algodefinition.common.RewardFunction;
import com.hotvect.api.algorithms.*;
import com.hotvect.api.codec.common.ExampleDecoder;
import com.hotvect.api.data.common.Example;
import com.hotvect.offlineutils.export.BulkScoreGreedyRanker;
import com.hotvect.offlineutils.export.RankingResultFormatter;
import com.hotvect.offlineutils.export.ScoringResultFormatter;
import com.hotvect.offlineutils.export.TopKResultFormatter;
import com.hotvect.offlineutils.hotdeploy.AlgorithmOfflineSupporterFactory;
import com.hotvect.offlineutils.util.OrderedFileMapper;
import com.hotvect.onlineutils.hotdeploy.AlgorithmInstanceFactory;
import com.hotvect.onlineutils.hotdeploy.util.MalformedAlgorithmException;
import com.hotvect.utils.ListTransform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkState;
import static java.lang.Math.max;

// TODO fix name
public class PredictTask<EXAMPLE extends Example, ALGO extends Algorithm, OUTCOME> extends Task {
    private static final Logger logger = LoggerFactory.getLogger(PredictTask.class);

    protected PredictTask(OfflineTaskContext offlineTaskContext) {
        super(offlineTaskContext);
    }


    @Override
    protected Map<String, Object> perform() throws Exception {
        AlgorithmOfflineSupporterFactory algorithmSupporterFactory = new AlgorithmOfflineSupporterFactory(this.offlineTaskContext.getClassLoader());
        AlgorithmInstanceFactory algoAlgorithmInstanceFactory = new AlgorithmInstanceFactory(
                offlineTaskContext.getClassLoader(),
                false
        );

        ExampleDecoder<EXAMPLE> testDecoder = algorithmSupporterFactory.getTestDecoder(offlineTaskContext.getAlgorithmDefinition());

        AlgorithmInstance<ALGO> algoAlgorithmInstance = algoAlgorithmInstanceFactory.load(
                this.offlineTaskContext.getAlgorithmDefinition(),
                this.offlineTaskContext.getOptions().parameters
        );

        LOGGER.info("Loaded AlgorithmInstance:{}", algoAlgorithmInstance);


        RewardFunction<OUTCOME> rewardFunction = algorithmSupporterFactory.getRewardFunction(offlineTaskContext.getAlgorithmDefinition());

        Function<EXAMPLE, String> algorithmOutputformatter = getOutputFormatter(algoAlgorithmInstance.getAlgorithm(), rewardFunction);

        Function<String, List<String>> transformation =
                testDecoder.andThen(i -> ListTransform.map(i, algorithmOutputformatter));

        checkState(
                this.offlineTaskContext.getOptions().sourceFiles.size() == 1 &&
                        this.offlineTaskContext.getOptions().sourceFiles.keySet().iterator().next().equals("default")
                ,
                "Only one source file type is supported for predict tasks"
        );


        OrderedFileMapper processor = OrderedFileMapper.mapper(
                super.offlineTaskContext.getMetricRegistry(),
                super.offlineTaskContext.getOptions().sourceFiles.values().iterator().next(),
                super.offlineTaskContext.getOptions().destinationFile, transformation,
                this.offlineTaskContext.getOptions().maxThreads < 0 ? max(Runtime.getRuntime().availableProcessors() - 1, 1) : this.offlineTaskContext.getOptions().maxThreads,
                this.offlineTaskContext.getOptions().queueLength,
                this.offlineTaskContext.getOptions().batchSize,
                this.offlineTaskContext.getOptions().samples
        );
        Map<String, Object> result = callOrderedFileMapper(processor);

        if ((long) result.getOrDefault("total_record_count", 0L) == 0) {
            throw new Exception("No rows have been written.");
        }

        return result;
    }

    private Function<EXAMPLE, String> getOutputFormatter(ALGO algo, RewardFunction<OUTCOME> rewardFunction) {
        if (algo instanceof Scorer<?>) {
            Scorer<?> scorer = (Scorer<?>) algo;
            return new ScoringResultFormatter().apply(rewardFunction, scorer);
        } else if (algo instanceof Ranker<?, ?>) {
            Ranker<?, ?> ranker = (Ranker<?, ?>) algo;
            return new RankingResultFormatter().apply(rewardFunction, ranker);
        } else if (algo instanceof BulkScorer<?, ?>){
            BulkScorer bulkScorer = (BulkScorer)algo;
            Ranker ranker = new BulkScoreGreedyRanker(bulkScorer);
            return new RankingResultFormatter().apply(rewardFunction, ranker);
        } else if (algo instanceof TopK) {
            TopK topK = (TopK) algo;
            return new TopKResultFormatter().apply(rewardFunction, topK);

        } else {
            throw new MalformedAlgorithmException("Unknown algorithm type:" + algo.getClass().getCanonicalName());
        }
    }

    protected Map<String, Object> callOrderedFileMapper(OrderedFileMapper processor) throws Exception {
        return processor.call();
    }

}
