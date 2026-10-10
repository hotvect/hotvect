package com.hotvect.offlineutils.commandline;


import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotvect.api.algodefinition.AlgorithmDependencies;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algodefinition.AlgorithmRuntimeId;
import com.hotvect.api.algodefinition.ParameterizedAlgorithmId;
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
import com.hotvect.onlineutils.hotdeploy.AlgorithmGraph;
import com.hotvect.onlineutils.hotdeploy.util.MalformedAlgorithmException;
import com.hotvect.onlineutils.serving.AlgorithmRuntimeContext;
import com.hotvect.onlineutils.serving.AlgorithmSelection;
import com.hotvect.onlineutils.serving.SelectedAlgorithmRuntime;
import com.hotvect.onlineutils.serving.SlotAssignment;
import com.hotvect.utils.HyperparamUtils;
import com.hotvect.utils.ListTransform;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkState;
import static java.lang.Math.max;
import static java.lang.Math.min;

public class PredictTask<EXAMPLE extends Example<? extends OfflineRequest, ?>, ALGO extends Algorithm, OUTCOME> extends Task {
    private static final String PREDICTION_EXTENSION = ".jsonl";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final JsonPointer assignmentKeyPointer;
    private final Map<AlgorithmRuntimeId, Function<String, List<ByteBuffer>>> emsHandlers = new ConcurrentHashMap<>();
    private final Map<AlgorithmSelection, LongAdder> emsSelectionCounts = new ConcurrentHashMap<>();

    protected PredictTask(OfflineTaskContext offlineTaskContext) {
        super(offlineTaskContext);
        this.assignmentKeyPointer = offlineTaskContext.source() instanceof OfflineTaskContext.EmsRuntime ems
                ? JsonPointer.compile(ems.assignmentKeyJsonPointer())
                : null;
    }


    @Override
    protected Map<String, Object> perform() throws Exception {
        return switch (offlineTaskContext.source()) {
            case OfflineTaskContext.DirectRuntime direct -> performDirectPrediction(direct);
            case OfflineTaskContext.FixedRuntime fixed -> performFixedPrediction(fixed);
            case OfflineTaskContext.EmsRuntime ems -> performEmsPrediction(ems);
        };
    }

    private Map<String, Object> performFixedPrediction(
            OfflineTaskContext.FixedRuntime source) throws Exception {
        AlgorithmRuntimeContext context = source.runtime().context();
        Function<String, List<ByteBuffer>> handler = predictionHandler(context);
        Map<String, Object> result = performPrediction(handler);
        result.put("composition_source", source.runtime().compositionSource().toUri().toString());
        result.put("composition", source.runtime().canonicalComposition());
        result.put("algorithm_runtime_id", source.runtime().runtimeId());
        result.put("composition_execution_context", "BATCH/OFFLINE");
        return result;
    }

    private Map<String, Object> performEmsPrediction(
            OfflineTaskContext.EmsRuntime source) throws Exception {
        Map<String, Object> result = performPrediction(json -> predictEmsRecord(source, json));
        result.put("ems_root_slot", source.rootSlot());
        result.put("ems_assignment_key_json_pointer", source.assignmentKeyJsonPointer());
        result.put("ems_state_source", source.stateSource());
        result.put("ems_execution_context", "BATCH/OFFLINE");
        result.put("ems_compositions", emsCompositionMetadata());
        return result;
    }

    private Map<String, Object> performDirectPrediction(
            OfflineTaskContext.DirectRuntime source) throws Exception {
        try (AlgorithmOfflineSupporterFactory algorithmSupporterFactory =
                     new AlgorithmOfflineSupporterFactory(this.offlineTaskContext.classLoader());
             AlgorithmInstanceFactory algorithmInstanceFactory = new AlgorithmInstanceFactory(
                     offlineTaskContext.classLoader(),
                     new AlgorithmInstanceFactory.Options(
                             InputSemantic.OFFLINE,
                             false,
                             this.offlineTaskContext.options().logFeatures,
                             Optional.of(this.offlineTaskContext.localStateRoot())));
             AlgorithmGraph<ALGO> algorithmGraph = algorithmInstanceFactory.loadGraph(
                this.offlineTaskContext.algorithmDefinition(),
                source.algorithmSource().parameters(),
                AlgorithmDependencies.empty(),
                ExecutionContext.of(WorkloadMode.BATCH, InputSemantic.OFFLINE)
        )) {
            ExampleDecoder<EXAMPLE> testDecoder =
                    algorithmSupporterFactory.getTestDecoder(offlineTaskContext.algorithmDefinition());
            LOGGER.info("Loaded algorithm graph rooted at:{}", algorithmGraph.root());

            RewardFunction<OUTCOME> rewardFunction = offlineTaskContext.algorithmDefinition().rewardFunctionFactoryName() == null
                    ? null : algorithmSupporterFactory.getRewardFunction(offlineTaskContext.algorithmDefinition());

            Function<EXAMPLE, ByteBuffer> algorithmOutputformatter = getOutputFormatter(algorithmGraph.algorithm(), rewardFunction);

            return performPrediction(testDecoder.andThen(i -> ListTransform.map(i, algorithmOutputformatter)));
        }
    }

    private Map<String, Object> performPrediction(
            Function<String, List<ByteBuffer>> transformation) throws Exception {
        boolean orderedOutput = shouldWriteOrderedPrediction();
        if (orderedOutput && this.offlineTaskContext.options().writerNumShards > 1) {
            throw new IllegalArgumentException(
                    "writer-num-shards > 1 may only be used with unordered predict output."
            );
        }

        checkState(
                this.offlineTaskContext.options().sourceFiles.size() == 1 &&
                        this.offlineTaskContext.options().sourceFiles.keySet().iterator().next().equals("default"),
                "Only one source file type is supported for predict tasks"
        );

        Map<String, Object> result = new HashMap<>(orderedOutput
                ? performOrderedPrediction(transformation)
                : performUnorderedPrediction(transformation));

        long totalRecordCount = ((Number) result.getOrDefault(
                "total_record_count",
                result.getOrDefault("lines_written", 0L))).longValue();
        if (totalRecordCount == 0L) {
            throw new Exception("No rows have been written.");
        }

        result.putIfAbsent("total_record_count", totalRecordCount);
        result.putIfAbsent("lines_written", totalRecordCount);
        result.put("prediction_output_ordering", orderedOutput ? "ordered" : "unordered");
        return result;
    }

    private List<ByteBuffer> predictEmsRecord(
            OfflineTaskContext.EmsRuntime source,
            String json) {
        String assignmentKey = assignmentKey(assignmentKeyPointer, json);
        SelectedAlgorithmRuntime selected = source.runtime().select(assignmentKey);
        List<ByteBuffer> predictions = emsHandlers
                .computeIfAbsent(
                        selected.context().runtimeId(),
                        ignored -> predictionHandler(selected.context()))
                .apply(json);
        emsSelectionCounts.computeIfAbsent(selected.selection(), ignored -> new LongAdder()).increment();
        return predictions;
    }

    static String assignmentKey(JsonPointer pointer, String json) {
        final JsonNode record;
        try {
            record = OBJECT_MAPPER.readTree(json);
        } catch (IOException error) {
            throw new IllegalArgumentException("Could not parse prediction input as JSON", error);
        }
        JsonNode value = record.at(pointer);
        if (value.isMissingNode() || value.isNull() || !value.isValueNode()) {
            throw new IllegalArgumentException(
                    "Prediction input does not contain a scalar EMS assignment key at " + pointer);
        }
        String assignmentKey = value.asText();
        if (assignmentKey.isBlank()) {
            throw new IllegalArgumentException(
                    "Prediction input contains a blank EMS assignment key at " + pointer);
        }
        return assignmentKey;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Function<String, List<ByteBuffer>> predictionHandler(AlgorithmRuntimeContext context) {
        try {
            AlgorithmInstance<?> instance = context.algorithmInstance();
            AlgorithmOfflineSupporterFactory supporter = new AlgorithmOfflineSupporterFactory(
                    context.rootArtifactClassLoader());
            ExampleDecoder<EXAMPLE> decoder = supporter.getTestDecoder(instance.algorithmDefinition());
            RewardFunction<OUTCOME> rewardFunction = instance.algorithmDefinition().rewardFunctionFactoryName() == null
                    ? null : supporter.getRewardFunction(instance.algorithmDefinition());
            Function<EXAMPLE, ByteBuffer> formatter = getOutputFormatter(instance.algorithm(), rewardFunction);
            return decoder.andThen(examples -> ListTransform.map(examples, formatter));
        } catch (MalformedAlgorithmException error) {
            throw error;
        }
    }

    private List<Map<String, Object>> emsCompositionMetadata() {
        return emsCompositionMetadata(emsSelectionCounts);
    }

    static List<Map<String, Object>> emsCompositionMetadata(
            Map<AlgorithmSelection, LongAdder> selectionCounts) {
        ArrayList<Map.Entry<AlgorithmSelection, LongAdder>> entries = new ArrayList<>(
                selectionCounts.entrySet());
        entries.sort(Map.Entry.comparingByKey(PredictTask::compareSelections));
        ArrayList<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<AlgorithmSelection, LongAdder> entry : entries) {
            LinkedHashMap<String, Object> composition = new LinkedHashMap<>();
            composition.put("record_count", entry.getValue().sum());
            composition.put("root_slot", entry.getKey().rootSlot());
            composition.put("assignments", entry.getKey().assignments());
            composition.put("algorithm_runtime_id", entry.getKey().runtimeId());
            result.add(composition);
        }
        return List.copyOf(result);
    }

    private static int compareSelections(
            AlgorithmSelection left,
            AlgorithmSelection right) {
        int compared = left.runtimeId().value().compareTo(right.runtimeId().value());
        if (compared != 0) {
            return compared;
        }
        compared = left.rootSlot().compareTo(right.rootSlot());
        if (compared != 0) {
            return compared;
        }
        return compareMaps(
                left.assignments(),
                right.assignments(),
                PredictTask::compareAssignments);
    }

    private static int compareAssignments(SlotAssignment left, SlotAssignment right) {
        int compared = left.variantId().compareTo(right.variantId());
        return compared != 0 ? compared : compareAlgorithms(left.algorithm(), right.algorithm());
    }

    private static int compareAlgorithms(ParameterizedAlgorithmId left, ParameterizedAlgorithmId right) {
        return left.value().compareTo(right.value());
    }

    private static <VALUE> int compareMaps(
            Map<String, VALUE> left,
            Map<String, VALUE> right,
            Comparator<VALUE> valueComparator) {
        Iterator<Map.Entry<String, VALUE>> leftEntries = left.entrySet().iterator();
        Iterator<Map.Entry<String, VALUE>> rightEntries = right.entrySet().iterator();
        while (leftEntries.hasNext() && rightEntries.hasNext()) {
            Map.Entry<String, VALUE> leftEntry = leftEntries.next();
            Map.Entry<String, VALUE> rightEntry = rightEntries.next();
            int compared = leftEntry.getKey().compareTo(rightEntry.getKey());
            if (compared != 0) {
                return compared;
            }
            compared = valueComparator.compare(leftEntry.getValue(), rightEntry.getValue());
            if (compared != 0) {
                return compared;
            }
        }
        return Boolean.compare(leftEntries.hasNext(), rightEntries.hasNext());
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
        Optional<JsonNode> rawAlgorithmDefinition = Optional.of(
                this.offlineTaskContext.algorithmDefinition().rawAlgorithmDefinition());
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

        Optional<JsonNode> rawAlgorithmDefinition = Optional.of(
                this.offlineTaskContext.algorithmDefinition().rawAlgorithmDefinition());
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
        Optional<JsonNode> rawAlgorithmDefinition = Optional.of(
                this.offlineTaskContext.algorithmDefinition().rawAlgorithmDefinition());
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
