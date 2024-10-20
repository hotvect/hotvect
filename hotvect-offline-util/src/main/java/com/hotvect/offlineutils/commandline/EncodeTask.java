package com.hotvect.offlineutils.commandline;


import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.codec.common.ExampleDecoder;
import com.hotvect.api.codec.common.ExampleEncoder;
import com.hotvect.api.data.common.Example;
import com.hotvect.offlineutils.hotdeploy.AlgorithmOfflineSupporterFactory;
import com.hotvect.offlineutils.util.OrderedFileMapper;
import com.hotvect.offlineutils.util.UnorderedFileMapper;
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

public class EncodeTask<EXAMPLE extends Example> extends Task {

    protected EncodeTask(OfflineTaskContext offlineTaskContext) {
        super(offlineTaskContext);
    }

    @Override
    protected Map<String, Object> perform() throws Exception {

        AlgorithmOfflineSupporterFactory algorithmSupporterFactory = new AlgorithmOfflineSupporterFactory(this.offlineTaskContext.getClassLoader());

        ExampleDecoder<EXAMPLE> scoringExampleDecoder = algorithmSupporterFactory.getTrainDecoder(offlineTaskContext.getAlgorithmDefinition());

        ExampleEncoder<EXAMPLE> exampleEncoder = algorithmSupporterFactory.getTrainEncoder(offlineTaskContext.getAlgorithmDefinition(), this.offlineTaskContext.getOptions().parameters);

        Optional<String> schemaDescription = exampleEncoder.schemaDescription();
        if(schemaDescription.isPresent()){
            checkArgument(
                    this.offlineTaskContext.getOptions().schemaDescriptionFile != null,
                    "Your algorithm needs a schema description, but your script did not provide --schema file location. This is e.g. column_description.tsv"
            );
            Files.writeString(this.offlineTaskContext.getOptions().schemaDescriptionFile.toPath(), schemaDescription.get(), StandardCharsets.UTF_8);
        }

        Function<String, List<String>> transformation = scoringExampleDecoder.andThen(s -> ListTransform.map(s, exampleEncoder));

        Optional<String> orderingSpec = this.offlineTaskContext.getAlgorithmDefinition().getTrainDecoderParameter()
                .flatMap(hp -> Optional.ofNullable(hp.get("ordering")).map(JsonNode::asText));

        Map<String, Object> metadata;

        checkState(
                this.offlineTaskContext.getOptions().sourceFiles.size() == 1 &&
                        this.offlineTaskContext.getOptions().sourceFiles.keySet().iterator().next().equals("default")
                ,
                "Only one source file type is supported for encode tasks"
        );



        if (orderingSpec.map("ordered"::equalsIgnoreCase).orElse(offlineTaskContext.getOptions().ordered)) {
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
                    this.offlineTaskContext.getMetricRegistry(),
                    this.offlineTaskContext.getOptions().sourceFiles.values().iterator().next(),
                    this.offlineTaskContext.getOptions().destinationFile,
                    transformation,
                    this.offlineTaskContext.getOptions().maxThreads <= 0 ? nRecommendedComputationThread : this.offlineTaskContext.getOptions().maxThreads,
                    this.offlineTaskContext.getOptions().queueLength <= 0 ? nRecommendedComputationThread * 4 : this.offlineTaskContext.getOptions().queueLength,
                    this.offlineTaskContext.getOptions().batchSize,
                    this.offlineTaskContext.getOptions().samples
            );
            metadata = processor.call();

            if ((long) metadata.getOrDefault("total_record_count", 0L) == 0) {
                throw new Exception("No rows have been written.");
            }

        } else {
            // Ordering spec is "unordered" or unspecified

            // 30 Appears to be about the max on our environment
            // After that performance degrades
            // This is likely due to the fact that the writer has to write to a single file
            int nRecommendedComputationThread = min(
                    30,
                    max(Runtime.getRuntime().availableProcessors() - 1, 1)
            );

            UnorderedFileMapper mapper = UnorderedFileMapper.mapper(
                    this.offlineTaskContext.getMetricRegistry(),
                    this.offlineTaskContext.getOptions().sourceFiles.values().iterator().next(),
                    this.offlineTaskContext.getOptions().destinationFile,
                    transformation,
                    this.offlineTaskContext.getOptions().maxThreads < 0 ? nRecommendedComputationThread : this.offlineTaskContext.getOptions().maxThreads,
                    this.offlineTaskContext.getOptions().batchSize
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
