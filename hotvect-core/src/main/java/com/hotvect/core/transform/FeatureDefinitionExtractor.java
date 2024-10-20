package com.hotvect.core.transform;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableSet;
import com.hotvect.api.data.FeatureNamespace;
import com.hotvect.core.combine.FeatureDefinition;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

public class FeatureDefinitionExtractor implements Function<Optional<JsonNode>, Set<FeatureDefinition>> {
    private final Set<FeatureNamespace> featureNamespaces;

    public FeatureDefinitionExtractor(Set<? extends FeatureNamespace> featureNamespaces) {
        this.featureNamespaces = ImmutableSet.copyOf(featureNamespaces);
    }

    @Override
    public Set<FeatureDefinition> apply(Optional<JsonNode> jsonNode) {
        checkArgument(jsonNode.isPresent(), "Hyperparameters that specify features are required.");
        return extractFeatureDefinitions(jsonNode.get());
    }

    public Set<FeatureDefinition> extractFeatureDefinitions(JsonNode hyperparameters) {
        JsonNode features = hyperparameters.get("features");
        checkNotNull(features);
        Set<FeatureDefinition> featureDefinitions = new HashSet<>();

        for (JsonNode feature : features) {
            Set<FeatureNamespace> fds = parse(feature);
            featureDefinitions.add(new FeatureDefinition(fds));
        }
        return featureDefinitions;
    }

    private Set<FeatureNamespace> parse(JsonNode node) {

        Function<String, FeatureNamespace> parseFun = s ->
                featureNamespaces.stream()
                        .filter(x -> x.toString().equals(s))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Could not find feature namespace with name " + s));

        Set<FeatureNamespace> ret = new HashSet<>();
        if (node.isArray()) {
            for (JsonNode jsonNode : node) {
                ret.add(parseFun.apply(jsonNode.asText()));
            }
        } else {
            ret.add(parseFun.apply(node.asText()));
        }
        return ret;
    }

}
