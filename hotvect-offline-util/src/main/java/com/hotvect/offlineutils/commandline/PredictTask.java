package com.hotvect.offlineutils.commandline;


import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algodefinition.common.RewardFunction;
import com.hotvect.api.algorithms.*;
import com.hotvect.api.codec.common.ExampleDecoder;
import com.hotvect.api.data.common.Example;
import com.hotvect.api.execution.ExecutionContext;
import com.hotvect.api.execution.InputSemantic;
import com.hotvect.api.execution.WorkloadMode;
import com.hotvect.api.data.OfflineRequest;
import com.hotvect.offlineutils.export.*;
import com.hotvect.offlineutils.hotdeploy.AlgorithmOfflineSupporterFactory;
import com.hotvect.onlineutils.concurrency.ConcurrentUtils;
import com.hotvect.onlineutils.concurrency.fileutils.OrderedFileMapper;
import com.hotvect.onlineutils.concurrency.fileutils.UnorderedFileMapper;
import com.hotvect.onlineutils.concurrency.fileutils.UnorderedFileWriter;
import com.hotvect.onlineutils.hotdeploy.AlgorithmInstanceFactory;
import com.hotvect.onlineutils.hotdeploy.util.MalformedAlgorithmException;
import com.hotvect.utils.HyperparamUtils;
import com.hotvect.utils.ListTransform;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkState;
import static java.lang.Math.max;
import static java.lang.Math.min;

public class PredictTask<EXAMPLE extends Example<? extends OfflineRequest, ?>, ALGO extends Algorithm, OUTCOME> extends Task {
    private static final String PREDICTION_EXTENSION = ".jsonl";

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

            boolean orderedOutput = shouldWriteOrderedPrediction();
            if (orderedOutput && this.offlineTaskContext.options().writerNumShards > 1) {
                throw new IllegalArgumentException(
                        "writer-num-shards > 1 may only be used with unordered predict output."
                );
            }

            checkState(
                    this.offlineTaskContext.options().sourceFiles.size() == 1 &&
                            this.offlineTaskContext.options().sourceFiles.keySet().iterator().next().equals("default")
                    ,
                    "Only one source file type is supported for predict tasks"
            );

            Map<String, Object> result = new HashMap<>(orderedOutput
                    ? performOrderedPrediction(transformation)
                    : performUnorderedPrediction(transformation));

            long totalRecordCount = ((Number) result.getOrDefault("total_record_count", result.getOrDefault("lines_written", 0L))).longValue();
            if (totalRecordCount == 0L) {
                throw new Exception("No rows have been written.");
            }

            result.putIfAbsent("total_record_count", totalRecordCount);
            result.putIfAbsent("lines_written", totalRecordCount);
            result.put("prediction_output_ordering", orderedOutput ? "ordered" : "unordered");

            return result;
        }
    }

    private Map<String, Object> performOrderedPrediction(Function<String, List<ByteBuffer>> transformation) throws Exception {
        File predictionDirectory = ensurePredictionOutputDirectory(this.offlineTaskContext.options().destinationFile);
        File orderedPredictionShard = new File(
                predictionDirectory,
                String.format(Locale.ROOT, UnorderedFileWriter.DEFAULT_OUTPUT_FILE_PATTERN, 0, PREDICTION_EXTENSION)
        );
        OrderedFileMapper processor = OrderedFileMapper.mapper(
                super.offlineTaskContext.meterRegistry(),
                super.offlineTaskContext.options().sourceFiles.values().iterator().next(),
                orderedPredictionShard,
                transformation,
                this.offlineTaskContext.options().maxThreads <= 0 ? max(Runtime.getRuntime().availableProcessors() - 1, 1) : this.offlineTaskContext.options().maxThreads,
                this.offlineTaskContext.options().queueLength,
                this.offlineTaskContext.options().batchSize,
                this.offlineTaskContext.options().samples
        );
        Map<String, Object> result = new HashMap<>(callOrderedFileMapper(processor));
        result.putIfAbsent("prediction_writer_num_shards", 1);
        return result;
    }

    private Map<String, Object> performUnorderedPrediction(Function<String, List<ByteBuffer>> transformation) throws Exception {
        int nRecommendedComputationThreads = min(
                128,
                max(Runtime.getRuntime().availableProcessors() - 1, 1)
        );
        int effectiveComputationThreads = this.offlineTaskContext.options().maxThreads <= 0
                ? nRecommendedComputationThreads
                : this.offlineTaskContext.options().maxThreads;
        int effectiveWriterNumShards = resolveWriterNumShards(effectiveComputationThreads);
        File shardDirectory = ensurePredictionOutputDirectory(this.offlineTaskContext.options().destinationFile);

        UnorderedFileMapper.Builder<String> mapperBuilder = UnorderedFileMapper.<String>builder(
                        super.offlineTaskContext.options().sourceFiles.values().iterator().next(),
                        shardDirectory,
                        transformation
                )
                .meterRegistry(super.offlineTaskContext.meterRegistry())
                .nThreads(effectiveComputationThreads)
                .batchSize(this.offlineTaskContext.options().batchSize)
                .extension(PREDICTION_EXTENSION)
                .numberOfShards(effectiveWriterNumShards);
        Integer readQueueSize = resolveReadQueueSize();
        Integer writeQueueSize = resolveWriteQueueSize();
        if (readQueueSize != null) {
            mapperBuilder = mapperBuilder.readQueueSize(readQueueSize);
        }
        if (writeQueueSize != null) {
            mapperBuilder = mapperBuilder.writeQueueSize(writeQueueSize);
        }

        int effectiveBatchSize = ConcurrentUtils.getBatchSize(Optional.of(this.offlineTaskContext.options().batchSize));
        int fallbackReadQueueSize = readQueueSize != null ? readQueueSize : effectiveComputationThreads * effectiveBatchSize * 4;
        int fallbackWriteQueueSize = writeQueueSize != null ? writeQueueSize : fallbackReadQueueSize;

        int explicitReaderThreads = resolveReaderThreads();
        if (explicitReaderThreads > 0) {
            mapperBuilder = mapperBuilder.readerThreads(explicitReaderThreads);
        }

        UnorderedFileMapper<String> processor = mapperBuilder.build();
        Map<String, Object> result = new HashMap<>(callUnorderedFileMapper(processor));
        long linesWritten = ((Number) result.getOrDefault("lines_written", 0L)).longValue();
        result.put("prediction_writer_num_shards", effectiveWriterNumShards);
        result.put("total_record_count", linesWritten);
        result.put(
                "prediction_effective_computation_threads",
                result.getOrDefault("unordered_mapper_computation_threads", effectiveComputationThreads)
        );
        result.put(
                "prediction_effective_read_queue_size",
                result.getOrDefault("unordered_mapper_read_queue_size", fallbackReadQueueSize)
        );
        result.put(
                "prediction_effective_write_queue_size",
                result.getOrDefault("unordered_mapper_write_queue_size", fallbackWriteQueueSize)
        );
        if (explicitReaderThreads > 0) {
            result.put(
                    "prediction_effective_reader_threads",
                    result.getOrDefault("unordered_mapper_reader_threads", explicitReaderThreads)
            );
        }
        return result;
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

    protected Map<String, Object> callUnorderedFileMapper(UnorderedFileMapper<String> processor) throws Exception {
        return processor.call();
    }

    private boolean shouldWriteOrderedPrediction() {
        Optional<JsonNode> rawAlgorithmDefinition = Optional.ofNullable(this.offlineTaskContext.algorithmDefinition().rawAlgorithmDefinition());
        return resolveOrderedOutput(
                HyperparamUtils.getOrDefault(
                        rawAlgorithmDefinition,
                        JsonNode::asBoolean,
                        false,
                        "hotvect_execution_parameters",
                        "predict",
                        "ordered"
                ),
                "predict"
        );
    }

    private int resolveWriterNumShards(int effectiveComputationThreads) {
        if (this.offlineTaskContext.options().writerNumShards > 0) {
            return this.offlineTaskContext.options().writerNumShards;
        }

        Optional<JsonNode> rawAlgorithmDefinition = Optional.ofNullable(this.offlineTaskContext.algorithmDefinition().rawAlgorithmDefinition());
        int writerNumShards = HyperparamUtils.getOrDefault(
                rawAlgorithmDefinition,
                JsonNode::asInt,
                -1,
                "hotvect_execution_parameters",
                "predict",
                "writer_num_shards"
        );

        if (writerNumShards > 0) {
            return writerNumShards;
        }

        int effectiveWriterNumShards = max(1, min((int) (effectiveComputationThreads / 2.5), 16));
        LOGGER.info(
                "Auto-determined unordered predict writer-num-shards: {} (based on {} computation threads)",
                effectiveWriterNumShards,
                effectiveComputationThreads
        );
        return effectiveWriterNumShards;
    }

    private int resolveReaderThreads() {
        Optional<JsonNode> rawAlgorithmDefinition = Optional.ofNullable(this.offlineTaskContext.algorithmDefinition().rawAlgorithmDefinition());
        return HyperparamUtils.getOrDefault(
                rawAlgorithmDefinition,
                JsonNode::asInt,
                -1,
                "hotvect_execution_parameters",
                "predict",
                "reader_threads"
        );
    }

    private File ensurePredictionOutputDirectory(File destinationFile) throws IOException {
        if (destinationFile.exists()) {
            if (!destinationFile.isDirectory()) {
                throw new IOException("Predict destination must be a directory path or a non-existent path: " + destinationFile);
            }
            return destinationFile;
        }

        if (!destinationFile.mkdirs()) {
            throw new IOException("Failed to create prediction output directory: " + destinationFile);
        }
        return destinationFile;
    }
}
