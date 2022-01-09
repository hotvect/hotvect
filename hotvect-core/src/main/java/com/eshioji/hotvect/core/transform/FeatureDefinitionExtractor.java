package com.eshioji.hotvect.core.transform;

import com.eshioji.hotvect.api.data.FeatureNamespace;
import com.eshioji.hotvect.core.combine.FeatureDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

public class FeatureDefinitionExtractor<FEATURE extends Enum<FEATURE> & FeatureNamespace> implements Function<JsonNode, Set<FeatureDefinition<FEATURE>>> {
    private final Class<FEATURE> featureKeyClass;

    public FeatureDefinitionExtractor(Class<FEATURE> featureKeyClass) {
        this.featureKeyClass = featureKeyClass;
    }

    @Override
    public Set<FeatureDefinition<FEATURE>> apply(JsonNode jsonNode) {
        return extractFeatureDefinitions(featureKeyClass, jsonNode);

    }

    public Set<FeatureDefinition<FEATURE>> extractFeatureDefinitions(Class<FEATURE> featureKeyClass, JsonNode hyperparameters) {
        JsonNode features = hyperparameters.get("features");
        checkNotNull(features);
        Set<FeatureDefinition<FEATURE>> featureDefinitions = new HashSet<>();

        for (JsonNode feature : features) {
            EnumSet<FEATURE> fds = parse(featureKeyClass, (ArrayNode) feature);
            featureDefinitions.add(new FeatureDefinition<>(fds));
        }
        return featureDefinitions;
    }

    private EnumSet<FEATURE> parse(Class<FEATURE> featureKeyClass, ArrayNode node) {

        Function<String, FEATURE> parseFun = s -> {
            try {
                return (FEATURE) featureKeyClass.getDeclaredMethod("valueOf", String.class).invoke(featureKeyClass, s);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };

        EnumSet<FEATURE> ret = EnumSet.noneOf(featureKeyClass);
        for (JsonNode jsonNode : node) {
            ret.add(parseFun.apply(jsonNode.asText()));
        }
        checkArgument(ret.size() == node.size(), "Duplicate feature key specified? : %s", node);
        return ret;
    }

}
