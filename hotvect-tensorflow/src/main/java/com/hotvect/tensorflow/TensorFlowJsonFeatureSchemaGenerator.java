package com.hotvect.tensorflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hotvect.api.algodefinition.ranking.RankingTransformer;
import com.hotvect.api.data.Namespace;

import java.util.SortedSet;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * Generates a TensorFlow-specific JSON schema that fully specifies dtype and shape for each feature.
 *
 * <p>Output format:
 * <pre>
 * {
 *   "features": [
 *     { "name": "candidate_price", "dtype": "float32", "shape": [] },
 *     { "name": "all_contact_action_type_categorical_sequence", "dtype": "int64", "shape": [50] }
 *   ]
 * }
 * </pre>
 *
 * <p>This schema is intended to be consumed by both:
 * <ul>
 *     <li>Training ingestion (building {@code tf.io.parse_example} feature specs)</li>
 *     <li>Online serving workers (direct IPC ingestion)</li>
 * </ul>
 */
public final class TensorFlowJsonFeatureSchemaGenerator implements Function<RankingTransformer<?, ?>, String> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public String apply(RankingTransformer<?, ?> transformer) {
        SortedSet<? extends Namespace> usedFeatures = transformer.getUsedFeatures();
        checkArgument(!usedFeatures.isEmpty(), "Used features cannot be empty");

        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        ArrayNode featuresArray = OBJECT_MAPPER.createArrayNode();

        for (Namespace feature : usedFeatures) {
            Object featureValueTypeObj = feature.getFeatureValueType();
            if (!(featureValueTypeObj instanceof TensorFlowFeatureType featureType)) {
                String actual = featureValueTypeObj == null ? "null" : featureValueTypeObj.getClass().getName();
                throw new IllegalArgumentException(
                        "All features must have TensorFlowFeatureType defined. Offending feature: "
                                + feature.getName() + " (was " + actual + ")"
                );
            }

            ObjectNode featureNode = OBJECT_MAPPER.createObjectNode();
            featureNode.put("name", feature.getName());
            featureNode.put("dtype", featureType.tensorflowDTypeName());
            ArrayNode shapeArray = OBJECT_MAPPER.createArrayNode();
            for (int dim : featureType.shape()) {
                shapeArray.add(dim);
            }
            featureNode.set("shape", shapeArray);
            featuresArray.add(featureNode);
        }

        root.set("features", featuresArray);

        try {
            return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize TensorFlow schema to JSON", e);
        }
    }
}

