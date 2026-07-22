package com.hotvect.offlineutils.commandline;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.algodefinition.ranking.RankingVectorizer;
import com.hotvect.api.codec.common.ExampleDecoder;
import com.hotvect.api.codec.common.ExampleEncoder;
import com.hotvect.api.data.OfflineRequest;
import com.hotvect.api.data.common.Example;
import com.hotvect.offlineutils.hotdeploy.AlgorithmOfflineSupporterFactory;
import com.hotvect.onlineutils.concurrency.fileutils.OrderedFileMapper;
import com.hotvect.onlineutils.concurrency.fileutils.UnorderedFileMapper;
import com.hotvect.onlineutils.concurrency.fileutils.UnorderedFileWriter;
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

public class AuditTask<EXAMPLE extends Example<? extends OfflineRequest, ?>, SUBJECT> extends Task {
    private static final String AUDIT_EXTENSION = ".jsonl";

    protected AuditTask(OfflineTaskContext offlineTaskContext) {
        super(offlineTaskContext);
    }

    @Override
    protected Map<String, Object> perform() throws Exception {
        AlgorithmOfflineSupporterFactory algorithmSupporterFactory = new AlgorithmOfflineSupporterFactory(this.offlineTaskContext.classLoader());

        ExampleDecoder<EXAMPLE> scoringExampleDecoder = algorithmSupporterFactory.getTrainDecoder(offlineTaskContext.algorithmDefinition());

        SUBJECT subject = instantiateSubject(algorithmSupporterFactory, offlineTaskContext.algorithmDefinition(), this.offlineTaskContext.options().parameters);

        ExampleEncoder<EXAMPLE> exampleEncoder = instantiateAuditEncoder(algorithmSupporterFactory, subject, offlineTaskContext.options().includeFeatureStoreResponses);
        String extension = exampleEncoder.encodedFileExtension();
        if (extension == null) {
            throw new IllegalStateException(
                    "Audit encoder " + exampleEncoder.getClass().getName() + " returned null from encodedFileExtension(). "
                            + "Return \"\" or a leading dot, e.g. \".jsonl\""
            );
        }
        if (!AUDIT_EXTENSION.equals(extension)) {
            throw new IllegalStateException(
                    "Audit encoder " + exampleEncoder.getClass().getName() + " returned " + extension
                            + " from encodedFileExtension(). Expected " + AUDIT_EXTENSION + "."
            );
        }

        // List of ByteBuffers belonging to a single Example, and there can be multiple Examples
        Function<String, List<ByteBuffer>> transformation = scoringExampleDecoder.andThen(s -> ListTransform.map(s, exampleEncoder));

        checkState(
                this.offlineTaskContext.options().sourceFiles.size() == 1 &&
                        this.offlineTaskContext.options().sourceFiles.keySet().iterator().next().equals("default")
                ,
                "Only one source file type is supported for audit tasks"
        );

        boolean orderedOutput = shouldWriteOrderedAudit();
        if (orderedOutput && this.offlineTaskContext.options().writerNumShards > 1) {
            throw new IllegalArgumentException(
                    "writer-num-shards > 1 may only be used with unordered audit output."
            );
        }
        Map<String, Object> metadata = new HashMap<>(orderedOutput
                ? performOrderedAudit(transformation)
                : performUnorderedAudit(transformation, extension));
        long totalRecordCount = ((Number) metadata.getOrDefault("total_record_count", metadata.getOrDefault("lines_written", 0L))).longValue();
        if (totalRecordCount == 0L) {
            throw new Exception("No rows have been written.");
        }

        metadata.putIfAbsent("total_record_count", totalRecordCount);
        metadata.putIfAbsent("lines_written", totalRecordCount);
        metadata.put("audit_output_ordering", orderedOutput ? "ordered" : "unordered");
        metadata.put("example_decoder", scoringExampleDecoder.getClass().getCanonicalName());
        metadata.put("example_encoder", exampleEncoder.getClass().getCanonicalName());

        return metadata;
    }

    private Map<String, Object> performOrderedAudit(Function<String, List<ByteBuffer>> transformation) throws Exception {
        File auditDirectory = ensureAuditOutputDirectory(this.offlineTaskContext.options().destinationFile);
        File orderedAuditPart = new File(
                auditDirectory,
                String.format(Locale.ROOT, UnorderedFileWriter.DEFAULT_OUTPUT_FILE_PATTERN, 0, AUDIT_EXTENSION)
        );
        OrderedFileMapper processor = OrderedFileMapper.mapper(
                this.offlineTaskContext.meterRegistry(),
                this.offlineTaskContext.options().sourceFiles.values().iterator().next(),
                orderedAuditPart,
                transformation,
                this.offlineTaskContext.options().maxThreads < 0 ? max(Runtime.getRuntime().availableProcessors() - 1, 1) : this.offlineTaskContext.options().maxThreads,
                this.offlineTaskContext.options().queueLength,
                this.offlineTaskContext.options().batchSize,
                this.offlineTaskContext.options().samples
        );

        Map<String, Object> metadata = new HashMap<>(callOrderedFileMapper(processor));
        metadata.putIfAbsent("audit_writer_num_shards", 1);
        return metadata;
    }

    private Map<String, Object> performUnorderedAudit(Function<String, List<ByteBuffer>> transformation, String extension) throws Exception {
        int nRecommendedComputationThreads = min(
                128,
                max(Runtime.getRuntime().availableProcessors() - 1, 1)
        );
        int effectiveWriterNumShards = resolveWriterNumShards(nRecommendedComputationThreads);
        File shardDirectory = ensureAuditOutputDirectory(this.offlineTaskContext.options().destinationFile);

        UnorderedFileMapper.Builder<String> mapperBuilder = UnorderedFileMapper.<String>builder(
                        this.offlineTaskContext.options().sourceFiles.values().iterator().next(),
                        shardDirectory,
                        transformation
                )
                .meterRegistry(this.offlineTaskContext.meterRegistry())
                .nThreads(this.offlineTaskContext.options().maxThreads < 0 ? nRecommendedComputationThreads : this.offlineTaskContext.options().maxThreads)
                .batchSize(this.offlineTaskContext.options().batchSize)
                .extension(extension)
                .numberOfShards(effectiveWriterNumShards);
        Integer readQueueSize = resolveReadQueueSize();
        Integer writeQueueSize = resolveWriteQueueSize();
        if (readQueueSize != null) {
            mapperBuilder = mapperBuilder.readQueueSize(readQueueSize);
        }
        if (writeQueueSize != null) {
            mapperBuilder = mapperBuilder.writeQueueSize(writeQueueSize);
        }
        UnorderedFileMapper<String> mapper = mapperBuilder.build();
        Map<String, Object> metadata = new HashMap<>(callUnorderedFileMapper(mapper));
        long linesWritten = ((Number) metadata.getOrDefault("lines_written", 0L)).longValue();
        metadata.put("audit_writer_num_shards", effectiveWriterNumShards);
        metadata.put("total_record_count", linesWritten);
        return metadata;
    }

    protected Map<String, Object> callOrderedFileMapper(OrderedFileMapper processor) throws Exception {
        return processor.call();
    }

    protected Map<String, Object> callUnorderedFileMapper(UnorderedFileMapper<String> mapper) throws Exception {
        return mapper.call();
    }

    private boolean shouldWriteOrderedAudit() {
        Optional<JsonNode> rawAlgorithmDefinition = Optional.ofNullable(this.offlineTaskContext.algorithmDefinition().rawAlgorithmDefinition());
        return resolveOrderedOutput(
                HyperparamUtils.getOrDefault(
                        rawAlgorithmDefinition,
                        JsonNode::asBoolean,
                        true,
                        "hotvect_execution_parameters",
                        "audit",
                        "ordered"
                ),
                "audit"
        );
    }

    private int resolveWriterNumShards(int nRecommendedComputationThreads) {
        if (this.offlineTaskContext.options().writerNumShards > 0) {
            return this.offlineTaskContext.options().writerNumShards;
        }

        Optional<JsonNode> rawAlgorithmDefinition = Optional.ofNullable(this.offlineTaskContext.algorithmDefinition().rawAlgorithmDefinition());
        int writerNumShards = HyperparamUtils.getOrDefault(
                rawAlgorithmDefinition,
                JsonNode::asInt,
                -1,
                "hotvect_execution_parameters",
                "audit",
                "writer_num_shards"
        );
        if (writerNumShards > 0) {
            return writerNumShards;
        }

        int effectiveWriterNumShards = max(1, min((int) (nRecommendedComputationThreads / 2.5), 16));
        LOGGER.info(
                "Auto-determined unordered audit writer-num-shards: {} (based on {} computation threads)",
                effectiveWriterNumShards,
                nRecommendedComputationThreads
        );
        return effectiveWriterNumShards;
    }

    private File ensureAuditOutputDirectory(File destinationFile) throws IOException {
        if (destinationFile.exists()) {
            if (!destinationFile.isDirectory()) {
                throw new IOException("Audit destination must be a directory path or a non-existent path: " + destinationFile);
            }
            return destinationFile;
        }

        if (!destinationFile.mkdirs()) {
            throw new IOException("Failed to create audit output directory: " + destinationFile);
        }
        return destinationFile;
    }

    private SUBJECT instantiateSubject(AlgorithmOfflineSupporterFactory algorithmSupporterFactory, AlgorithmDefinition algorithmDefinition, File parameters) throws Exception {
        return algorithmSupporterFactory.loadFeatureExtractionDependency(algorithmDefinition, parameters, Map.of());
    }


    @SuppressWarnings("removal")
    private ExampleEncoder<EXAMPLE> instantiateAuditEncoder(AlgorithmOfflineSupporterFactory algorithmSupporterFactory, SUBJECT subject, boolean includeFeatureStoreResponses) throws Exception {
        if(subject instanceof RankingVectorizer){
            throw new UnsupportedOperationException("Vectorizers are no longer supported");
        } else if (subject instanceof com.hotvect.api.algodefinition.ranking.RankingTransformer){
            return new com.hotvect.offlineutils.export.RankingTransformerAuditEncoder(
                    (com.hotvect.api.algodefinition.ranking.RankingTransformer) subject,
                    algorithmSupporterFactory.getRewardFunction(offlineTaskContext.algorithmDefinition()),
                    includeFeatureStoreResponses
            );
        } else {
            throw new UnsupportedOperationException("Unknown audit subject of type:" + subject.getClass().getCanonicalName());
        }
    }


}
