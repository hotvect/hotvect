package com.hotvect.tensorflow;

import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.hotvect.api.algodefinition.common.RewardFunction;
import com.hotvect.api.codec.ranking.RankingExampleEncoder;
import com.hotvect.api.data.Namespace;
import com.hotvect.api.data.common.NamespacedRecord;
import com.hotvect.api.data.ranking.RankingExample;
import com.hotvect.api.data.ranking.RankingOutcome;
import com.hotvect.api.data.ranking.TransformedAction;
import com.hotvect.core.transform.ranking.ComputingRankingRequest;
import com.hotvect.core.transform.ranking.ComputingRankingTransformer;
import com.hotvect.utils.ListTransform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tensorflow.proto.Example;
import org.tensorflow.proto.Feature;
import org.tensorflow.proto.Features;
import org.tensorflow.proto.Int64List;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.stream.Collectors.toSet;

/**
 * Production-ready TFRecord encoder that converts HotVect ranking examples to TFRecord format.
 * This encoder creates TensorFlow Example protos and packages them in proper TFRecord binary format
 * compatible with tf.data.TFRecordDataset.
 *
 * Key features:
 * - Returns single ByteBuffer containing all actions in TFRecord format
 * - Uses TensorFlow proto classes for feature encoding
 * - Proper TFRecord format with CRC32C checksums and length prefixes
 * - Compatible with TensorFlow Python training pipelines
 */
public class TFRecordRankingEncoder<SHARED, ACTION, OUTCOME> implements RankingExampleEncoder<SHARED, ACTION, OUTCOME> {
    private static final Logger logger = LoggerFactory.getLogger(TFRecordRankingEncoder.class);

    private final ComputingRankingTransformer<SHARED, ACTION> transformer;
    private final RewardFunction<OUTCOME> rewardFunction;
    private final TFRecordWriter tfRecordWriter;
    private final TensorFlowJsonFeatureSchemaGenerator schemaGenerator;

    public TFRecordRankingEncoder(ComputingRankingTransformer<SHARED, ACTION> transformer, RewardFunction<OUTCOME> rewardFunction) {
        this.transformer = transformer;
        this.rewardFunction = rewardFunction;

        // Validate that all features have TensorFlow feature types
        Set<Namespace> notOfTensorFlowType = transformer.getUsedFeatures().stream()
                .filter(x -> !(x.getFeatureValueType() instanceof TensorFlowFeatureType))
                .collect(toSet());
        checkArgument(notOfTensorFlowType.isEmpty(),
                "All features must have a TensorFlowFeatureType defined. Offending features: %s",
                notOfTensorFlowType);

        this.tfRecordWriter = new TFRecordWriter();
        this.schemaGenerator = new TensorFlowJsonFeatureSchemaGenerator();
        logger.info("TfRecordRankingEncoder initialized with {} features", transformer.getUsedFeatures().size());
    }

    @Override
    public Optional<String> schemaDescription() {
        return Optional.of(schemaGenerator.apply(transformer));
    }

    @Override
    public String encodedFileExtension() {
        return ".tfrecord";
    }

    /**
     * Main encoding method that returns a single ByteBuffer containing all actions in TFRecord format.
     * Each action becomes one TensorFlow Example proto, and all protos are packaged in TFRecord format
     * compatible with tf.data.TFRecordDataset.
     */
    @Override
    public ByteBuffer apply(RankingExample<SHARED, ACTION, OUTCOME> toEncode) {
        try {
            ComputingRankingRequest<SHARED, ACTION> memoized = transformer.prepare(toEncode.request());
            List<TransformedAction<ACTION>> transformedActions = transformer.transform(memoized);
            List<NamespacedRecord<Namespace, Object>> transformed = ListTransform.map(transformedActions, TransformedAction::transformed);

            List<ByteBuffer> buffers = new ArrayList<>();

            for (int i = 0; i < transformed.size(); i++) {
                RankingOutcome<OUTCOME, ACTION> outcome = toEncode.outcomes().get(i);
                double reward = rewardFunction.applyAsDouble(outcome.outcome());
                NamespacedRecord<Namespace, Object> record = transformed.get(i);

                Example example = convertToTensorFlowExample(record, reward, toEncode.exampleId(), i);
                buffers.add(ByteBuffer.wrap(example.toByteArray()));
            }

            return tfRecordWriter.createTfRecordByteBuffer(buffers);

        } catch (Exception e) {
            logger.error("Failed to encode ranking example: {}", toEncode.exampleId(), e);
            throw new RuntimeException("TFRecord encoding failed for example: " + toEncode.exampleId(), e);
        }
    }


    /**
     * Converts a single record to a TensorFlow Example proto.
     * Feature names are important because they map to TensorFlow model inputs.
     */
    private Example convertToTensorFlowExample(NamespacedRecord<Namespace, Object> record,
                                              double reward,
                                              String exampleId,
                                              int actionIndex) {
        Features.Builder featuresBuilder = Features.newBuilder();

        // Add label as binarized int64 (>0 is 1, <=0 is 0)
        long binarizedLabel = reward > 0 ? 1L : 0L;
        featuresBuilder.putFeature("Label",
            Feature.newBuilder()
                .setInt64List(Int64List.newBuilder().addValue(binarizedLabel))
                .build());

        // Add transformer features - names are crucial for TensorFlow model input mapping
        try {
            TensorFlowExampleEncoder.putTransformerFeatures(featuresBuilder, transformer.getUsedFeatures(), record);
        } catch (Exception e) {
            Throwable cause = Throwables.getRootCause(e);
            throw new RuntimeException(Strings.lenientFormat("Error encoding transformer features (exampleId=%s)", exampleId), cause);
        }

        return Example.newBuilder()
            .setFeatures(featuresBuilder.build())
            .build();
    }
}
