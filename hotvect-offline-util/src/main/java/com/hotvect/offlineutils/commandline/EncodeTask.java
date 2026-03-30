package com.hotvect.offlineutils.commandline;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.codec.common.ExampleDecoder;
import com.hotvect.api.codec.common.ExampleEncoder;
import com.hotvect.api.data.common.Example;
import com.hotvect.api.data.OfflineRequest;
import com.hotvect.offlineutils.hotdeploy.AlgorithmOfflineSupporterFactory;
import com.hotvect.onlineutils.concurrency.fileutils.OrderedFileMapper;
import com.hotvect.onlineutils.concurrency.fileutils.UnorderedFileMapper;
import com.hotvect.utils.ListTransform;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.Math.max;
import static java.lang.Math.min;

public class EncodeTask<EXAMPLE extends Example<? extends OfflineRequest, ?>> extends Task {

    protected EncodeTask(OfflineTaskContext offlineTaskContext) {
        super(offlineTaskContext);
    }

    @Override
    protected Map<String, Object> perform() throws Exception {

        AlgorithmOfflineSupporterFactory algorithmSupporterFactory = new AlgorithmOfflineSupporterFactory(this.offlineTaskContext.classLoader());

        ExampleDecoder<EXAMPLE> scoringExampleDecoder = algorithmSupporterFactory.getTrainDecoder(offlineTaskContext.algorithmDefinition());

        ExampleEncoder<EXAMPLE> exampleEncoder = algorithmSupporterFactory.getTrainEncoder(offlineTaskContext.algorithmDefinition(), this.offlineTaskContext.options().parameters);

        String extension;
        try {
            extension = exampleEncoder.encodedFileExtension();
        } catch (UnsupportedOperationException e) {
            LOGGER.warn(
                    "Legacy encoder {} does not implement encodedFileExtension(); writing shard files without an extension.",
                    exampleEncoder.getClass().getName()
            );
            extension = "";
        }
        if (extension == null) {
            throw new IllegalStateException(
                    "Encoder " + exampleEncoder.getClass().getName() + " returned null from encodedFileExtension(). "
                            + "Return \"\" or a leading dot, e.g. \".tfrecord\", \".tsv\", \".jsonl\""
            );
        }
        if (!extension.isEmpty() && !extension.startsWith(".")) {
            throw new IllegalStateException(
                    "Encoder " + exampleEncoder.getClass().getName() + " returned an invalid file extension from encodedFileExtension(): "
                            + extension + ". Expected \"\" or a leading dot, e.g. \".tfrecord\", \".tsv\", \".jsonl\""
            );
        }

        Optional<String> schemaDescription = exampleEncoder.schemaDescription();
        if(schemaDescription.isPresent()){
            checkArgument(
                    this.offlineTaskContext.options().schemaDescriptionFile != null,
                    "Your algorithm needs a schema description, but your script did not provide --schema file location. This is e.g. column_description.tsv"
            );
            Files.writeString(this.offlineTaskContext.options().schemaDescriptionFile.toPath(), schemaDescription.get(), StandardCharsets.UTF_8);
        }

        Function<String, List<ByteBuffer>> transformation = scoringExampleDecoder.andThen(s -> ListTransform.map(s, exampleEncoder));

        Optional<String> orderingSpec = this.offlineTaskContext.algorithmDefinition().trainDecoderParameter()
                .flatMap(hp -> Optional.ofNullable(hp.get("ordering")).map(JsonNode::asText));

        int writerNumShardsFromAlgoDef = this.offlineTaskContext.algorithmDefinition().transformerParameter()
                .flatMap(hp -> Optional.ofNullable(hp.get("writer_num_shards")).map(JsonNode::asInt))
                .orElse(-1);

        int writerNumShards = this.offlineTaskContext.options().writerNumShards != -1
                ? this.offlineTaskContext.options().writerNumShards
                : writerNumShardsFromAlgoDef;

        File destinationDir = buildDestinationDirectory(
                this.offlineTaskContext.options().destinationFile,
                extension
        );

        Map<String, Object> metadata;

        checkState(
                this.offlineTaskContext.options().sourceFiles.size() == 1 &&
                        this.offlineTaskContext.options().sourceFiles.keySet().iterator().next().equals("default")
                ,
                "Only one source file type is supported for encode tasks"
        );



        if (orderingSpec.map("ordered"::equalsIgnoreCase).orElse(offlineTaskContext.options().ordered)) {
            int nRecommendedComputationThread = min(
                    8,
                    max(Runtime.getRuntime().availableProcessors() - 1, 1)
            );

            File orderedDest = new File(destinationDir, "shard_0" + extension);
            OrderedFileMapper processor = OrderedFileMapper.mapper(
                    this.offlineTaskContext.meterRegistry(),
                    this.offlineTaskContext.options().sourceFiles.values().iterator().next(),
                    orderedDest,
                    transformation,
                    this.offlineTaskContext.options().maxThreads <= 0 ? nRecommendedComputationThread : this.offlineTaskContext.options().maxThreads,
                    this.offlineTaskContext.options().queueLength <= 0 ? nRecommendedComputationThread * 4 : this.offlineTaskContext.options().queueLength,
                    this.offlineTaskContext.options().batchSize,
                    this.offlineTaskContext.options().samples
            );
            metadata = processor.call();

            if ((long) metadata.getOrDefault("total_record_count", 0L) == 0) {
                throw new Exception("No rows have been written.");
            }

        } else {
            int nRecommendedComputationThread = min(
                    128,
                    max(Runtime.getRuntime().availableProcessors() - 1, 1)
            );

            int effectiveWriterNumShards = writerNumShards;
            if (effectiveWriterNumShards <= 0) {
                effectiveWriterNumShards = max(1, min((int) (nRecommendedComputationThread / 2.5), 16));
                LOGGER.info("Auto-determined writer-num-shards: {} (based on {} computation threads)",
                           effectiveWriterNumShards, nRecommendedComputationThread);
            }

            UnorderedFileMapper.Builder<String> mapperBuilder = UnorderedFileMapper.builder(
                    this.offlineTaskContext.options().sourceFiles.values().iterator().next(),
                    destinationDir,
                    transformation
            )
                    .meterRegistry(this.offlineTaskContext.meterRegistry())
                    .nThreads(this.offlineTaskContext.options().maxThreads < 0 ? nRecommendedComputationThread : this.offlineTaskContext.options().maxThreads)
                    .batchSize(this.offlineTaskContext.options().batchSize)
                    .extension(extension)
                    .numberOfShards(effectiveWriterNumShards);
            if (this.offlineTaskContext.options().queueLength > 0) {
                mapperBuilder = mapperBuilder
                        .readQueueSize(this.offlineTaskContext.options().queueLength)
                        .writeQueueSize(this.offlineTaskContext.options().queueLength);
            }
            UnorderedFileMapper<String> mapper = mapperBuilder.build();
            metadata = callUnorderedFileMapper(mapper);

            if ((Long) metadata.getOrDefault("lines_written", 0L) == 0) {
                throw new Exception("No rows have been written.");
            }
        }
        metadata.put("example_decoder", scoringExampleDecoder.getClass().getCanonicalName());
        metadata.put("example_encoder", exampleEncoder.getClass().getCanonicalName());
        return metadata;
    }

    protected Map<String, Object> callUnorderedFileMapper(UnorderedFileMapper<String> mapper) throws Exception {
        return mapper.call();
    }

    private File buildDestinationDirectory(File baseDestination, String extension) {
        String baseName = baseDestination.getName();
        String parent = baseDestination.getParent();

        File shardDirectory = parent == null ? new File(baseName) : new File(parent, baseName);
        if (!shardDirectory.exists() && !shardDirectory.mkdirs()) {
            throw new IllegalStateException("Failed to create destination directory: " + shardDirectory);
        }
        return shardDirectory;
    }

}
