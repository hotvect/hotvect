package com.hotvect.core.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hotvect.api.algodefinition.ranking.RankingTransformer;
import com.hotvect.api.data.Namespace;
import com.hotvect.api.data.ValueType;

import java.util.SortedSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * Generates JSON feature schema for ML training pipelines.
 *
 * Transparently outputs all features with their type names as-is.
 * No validation or mapping - just extracts feature information and formats as JSON.
 *
 * Output format:
 * {
 *   "features": [
 *     {"name": "request_cos_hour", "type": "CATEGORICAL"},
 *     {"name": "candidate_price", "type": "NUMERICAL"},
 *     {"name": "history_config_sku_list", "type": "CATEGORICAL_SEQUENCE"}
 *   ]
 * }
 */
public class JsonFeatureSchemaGenerator implements Function<RankingTransformer<?,?>, String> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public String apply(RankingTransformer<?, ?> transformer) {
        SortedSet<? extends Namespace> usedFeatures = transformer.getUsedFeatures();
        checkArgument(!usedFeatures.isEmpty(), "Used features cannot be empty");

        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        ArrayNode featuresArray = OBJECT_MAPPER.createArrayNode();

        for (Namespace feature : usedFeatures) {
            ValueType valueType = feature.getFeatureValueType();

            ObjectNode featureNode = OBJECT_MAPPER.createObjectNode();
            featureNode.put("name", feature.getName());
            featureNode.put("type", valueType.toString());

            featuresArray.add(featureNode);
        }

        root.set("features", featuresArray);

        try {
            return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize schema to JSON", e);
        }
    }
}
