package com.hotvect.offlineutils.commandline;


import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.codec.common.ExampleDecoder;
import com.hotvect.api.codec.common.ExampleEncoder;
import com.hotvect.api.data.common.Example;
import com.hotvect.api.data.OfflineRequest;import com.hotvect.offlineutils.hotdeploy.AlgorithmOfflineSupporterFactory;
import com.hotvect.onlineutils.concurrency.fileutils.OrderedFileMapper;
import com.hotvect.onlineutils.concurrency.fileutils.UnorderedFileMapper;
import com.hotvect.utils.ListTransform;

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

        Optional<String> schemaDescription = exampleEncoder.schemaDescription();
        if(schemaDescription.isPresent()){
            checkArgument(
                    this.offlineTaskContext.options().schemaDescriptionFile != null,
                    "Your algorithm needs a schema description, but your script did not provide --schema file location. This is e.g. column_description.tsv"
            );
            Files.writeString(this.offlineTaskContext.options().schemaDescriptionFile.toPath(), schemaDescription.get(), StandardCharsets.UTF_8);
        }

        Function<String, List<String>> transformation = scoringExampleDecoder.andThen(s -> ListTransform.map(s, exampleEncoder));

        Optional<String> orderingSpec = this.offlineTaskContext.algorithmDefinition().trainDecoderParameter()
                .flatMap(hp -> Optional.ofNullable(hp.get("ordering")).map(JsonNode::asText));

        Map<String, Object> metadata;

        checkState(
                this.offlineTaskContext.options().sourceFiles.size() == 1 &&
                        this.offlineTaskContext.options().sourceFiles.keySet().iterator().next().equals("default")
                ,
                "Only one source file type is supported for encode tasks"
        );



        if (orderingSpec.map("ordered"::equalsIgnoreCase).orElse(offlineTaskContext.options().ordered)) {
            // Ordering spec is "ordered"

            // 8 Appears to be about the max on our environment
            // After that performance degrades
            // This is likely due to the fact that the writer has to write to a single file
            // And for ordered processing we only read from one file at a time
            int nRecommendedComputationThread = min(
                    8,
                    max(Runtime.getRuntime().availableProcessors() - 1, 1)
            );
            OrderedFileMapper processor = OrderedFileMapper.mapper(
                    this.offlineTaskContext.meterRegistry(),
                    this.offlineTaskContext.options().sourceFiles.values().iterator().next(),
                    this.offlineTaskContext.options().destinationFile,
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
            // Ordering spec is "unordered" or unspecified

            int nRecommendedComputationThread = min(
                    64,
                    max(Runtime.getRuntime().availableProcessors() - 1, 1)
            );

            UnorderedFileMapper mapper = UnorderedFileMapper.mapper(
                    this.offlineTaskContext.meterRegistry(),
                    this.offlineTaskContext.options().sourceFiles.values().iterator().next(),
                    this.offlineTaskContext.options().destinationFile,
                    transformation,
                    this.offlineTaskContext.options().maxThreads < 0 ? nRecommendedComputationThread : this.offlineTaskContext.options().maxThreads,
                    this.offlineTaskContext.options().batchSize
            );
            metadata = mapper.call();

            if ((Long) metadata.getOrDefault("lines_written", 0L) == 0) {
                throw new Exception("No rows have been written.");
            }
        }
        metadata.put("example_decoder", scoringExampleDecoder.getClass().getCanonicalName());
        metadata.put("example_encoder", exampleEncoder.getClass().getCanonicalName());
        return metadata;
    }


}
