package com.hotvect.offlineutils.commandline;


import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algodefinition.common.RewardFunction;
import com.hotvect.api.algorithms.*;
import com.hotvect.api.codec.common.ExampleDecoder;
import com.hotvect.api.data.common.Example;
import com.hotvect.api.execution.ExecutionContext;
import com.hotvect.api.execution.InputSemantic;
import com.hotvect.api.execution.WorkloadMode;
import com.hotvect.api.data.OfflineRequest;import com.hotvect.offlineutils.export.*;
import com.hotvect.offlineutils.hotdeploy.AlgorithmOfflineSupporterFactory;
import com.hotvect.onlineutils.concurrency.fileutils.OrderedFileMapper;
import com.hotvect.onlineutils.hotdeploy.AlgorithmInstanceFactory;
import com.hotvect.onlineutils.hotdeploy.util.MalformedAlgorithmException;
import com.hotvect.utils.ListTransform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkState;
import static java.lang.Math.max;

public class PredictTask<EXAMPLE extends Example<? extends OfflineRequest, ?>, ALGO extends Algorithm, OUTCOME> extends Task {
    private static final Logger logger = LoggerFactory.getLogger(PredictTask.class);

    protected PredictTask(OfflineTaskContext offlineTaskContext) {
        super(offlineTaskContext);
    }


    @Override
    protected Map<String, Object> perform() throws Exception {
        AlgorithmOfflineSupporterFactory algorithmSupporterFactory = new AlgorithmOfflineSupporterFactory(this.offlineTaskContext.classLoader());
        AlgorithmInstanceFactory algoAlgorithmInstanceFactory = new AlgorithmInstanceFactory(
                offlineTaskContext.classLoader(),
                ExecutionContext.of(WorkloadMode.BATCH, InputSemantic.OFFLINE),
                false,
                this.offlineTaskContext.options().logFeatures
        );

        ExampleDecoder<EXAMPLE> testDecoder = algorithmSupporterFactory.getTestDecoder(offlineTaskContext.algorithmDefinition());

        try (AlgorithmInstance<ALGO> algoAlgorithmInstance = algoAlgorithmInstanceFactory.load(
                this.offlineTaskContext.algorithmDefinition(),
                this.offlineTaskContext.options().parameters,
                Map.of()
        )) {
            LOGGER.info("Loaded AlgorithmInstance:{}", algoAlgorithmInstance);

            RewardFunction<OUTCOME> rewardFunction = algorithmSupporterFactory.getRewardFunction(offlineTaskContext.algorithmDefinition());

            Function<EXAMPLE, ByteBuffer> algorithmOutputformatter = getOutputFormatter(algoAlgorithmInstance.algorithm(), rewardFunction);

            Function<String, List<ByteBuffer>> transformation =
                    testDecoder.andThen(i -> ListTransform.map(i, algorithmOutputformatter));

            checkState(
                    this.offlineTaskContext.options().sourceFiles.size() == 1 &&
                            this.offlineTaskContext.options().sourceFiles.keySet().iterator().next().equals("default")
                    ,
                    "Only one source file type is supported for predict tasks"
            );

            OrderedFileMapper processor = OrderedFileMapper.mapper(
                    super.offlineTaskContext.meterRegistry(),
                    super.offlineTaskContext.options().sourceFiles.values().iterator().next(),
                    super.offlineTaskContext.options().destinationFile, transformation,
                    this.offlineTaskContext.options().maxThreads < 0 ? max(Runtime.getRuntime().availableProcessors() - 1, 1) : this.offlineTaskContext.options().maxThreads,
                    this.offlineTaskContext.options().queueLength,
                    this.offlineTaskContext.options().batchSize,
                    this.offlineTaskContext.options().samples
            );
            Map<String, Object> result = callOrderedFileMapper(processor);

            if ((long) result.getOrDefault("total_record_count", 0L) == 0) {
                throw new Exception("No rows have been written.");
            }

            return result;
        }
    }

    private <EXAMPLE, ALGO, OUTCOME> Function<EXAMPLE, ByteBuffer> getOutputFormatter(ALGO algo, RewardFunction<OUTCOME> rewardFunction) {
        boolean includeFeatureStoreResponses = offlineTaskContext.options().includeFeatureStoreResponses;
        if (algo instanceof Ranker ranker) {
            return new RankingResultFormatter(includeFeatureStoreResponses).apply(rewardFunction, ranker);
        } else if (algo instanceof BulkScorer bulkScorer) {
            Ranker ranker = new BulkScoreGreedyRanker(bulkScorer);
            return new RankingResultFormatter(includeFeatureStoreResponses).apply(rewardFunction, ranker);
        } else if (algo instanceof ThemedTopK themedTopK) {
            // Handle ThemedTopK
            return new ThemedTopKResultFormatter().apply(rewardFunction, themedTopK);
        } else if (algo instanceof TopK topK) {
            return new TopKResultFormatter().apply(rewardFunction, topK);
        } else {
            throw new MalformedAlgorithmException(
                    "Unknown algorithm type: " + algo.getClass().getCanonicalName());
        }
    }

    protected Map<String, Object> callOrderedFileMapper(OrderedFileMapper processor) throws Exception {
        return processor.call();
    }

}
