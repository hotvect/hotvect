package com.hotvect.core.transform;

import com.hotvect.api.data.FeatureNamespace;
import com.hotvect.core.combine.FeatureDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

public class FeatureDefinitionExtractor<K extends Enum<K> & FeatureNamespace> implements Function<JsonNode, Set<FeatureDefinition<K>>> {
    private final Class<K> featureKeyClass;
    private final Function<String, K> parseFun;

    public FeatureDefinitionExtractor(Class<K> featureKeyClass) {
        this.featureKeyClass = featureKeyClass;

        // Don't know of a cleaner way
        this.parseFun = s -> {
            try {
                return (K) featureKeyClass.getDeclaredMethod("valueOf", String.class).invoke(featureKeyClass, s);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }

    @Override
    public Set<FeatureDefinition<K>> apply(JsonNode jsonNode) {
        return extractFeatureDefinitions(featureKeyClass, jsonNode);

    }

    public Set<FeatureDefinition<K>> extractFeatureDefinitions(Class<K> featureKeyClass, JsonNode hyperparameters) {
        JsonNode features = hyperparameters.get("features");
        checkNotNull(features);
        Set<FeatureDefinition<K>> featureDefinitions = new HashSet<>();

        for (JsonNode feature : features) {
            EnumSet<K> fds = parse(featureKeyClass, (ArrayNode) feature);
            featureDefinitions.add(new FeatureDefinition<>(fds));
        }
        return featureDefinitions;
    }

    private EnumSet<K> parse(Class<K> featureKeyClass, ArrayNode node) {

        Function<String, K> parseFun = s -> {
            try {
                return (K) featureKeyClass.getDeclaredMethod("valueOf", String.class).invoke(featureKeyClass, s);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };

        EnumSet<K> ret = EnumSet.noneOf(featureKeyClass);
        for (JsonNode jsonNode : node) {
            ret.add(parseFun.apply(jsonNode.asText()));
        }
        checkArgument(ret.size() == node.size(), "Duplicate feature key specified? : %s", node);
        return ret;
    }

}
