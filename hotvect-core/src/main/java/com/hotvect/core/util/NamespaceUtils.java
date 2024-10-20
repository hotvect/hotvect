package com.hotvect.core.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.google.common.collect.ImmutableSortedSet;
import com.hotvect.api.data.FeatureNamespace;
import com.hotvect.utils.HyperparamUtils;

import java.util.*;
import java.util.stream.Collectors;

public class NamespaceUtils {
    public static <V extends FeatureNamespace> SortedSet<V> findFeatureNamespaces(Optional<JsonNode> transformerHyperparameters, SortedSet<V> availableFeatures) {
        Set<String> features = extractFeatureNamespaces(transformerHyperparameters);
        return findFeatureNamespaces(features, availableFeatures);
    }

    public static <V extends FeatureNamespace> SortedSet<V> findFeatureNamespaces(Set<String> features, SortedSet<V> availableFeatures) {
        SortedSet<V> featureIds = new TreeSet<>(FeatureNamespace.alphabetical());
        for (String feature : features) {
            Set<V> correspondingComputation = availableFeatures.stream().filter(at -> at.toString().equals(feature)).collect(Collectors.toSet());
            if (correspondingComputation.isEmpty()) {
                throw new IllegalArgumentException("Could not find transformation ID with the name " + feature + " among the available transformation IDs:" + availableFeatures);
            } else if (correspondingComputation.size() > 1) {
                throw new IllegalArgumentException("More than one computation with the name " + feature + " was found. Computation names used as features must be unique. Found:" + correspondingComputation);
            } else {
                featureIds.add(correspondingComputation.stream().findFirst().get());
            }
        }
        return ImmutableSortedSet.copyOf(FeatureNamespace.alphabetical(), featureIds);
    }

    @SafeVarargs
    public static <V extends FeatureNamespace> SortedSet<V> findFeatureNamespaces(Set<String> features, Class<? extends Enum<?>>... candidateEnums) {
        SortedSet<V> featureIds = new TreeSet<>(FeatureNamespace.alphabetical());
        Set<Enum<?>> availableTransformations = Arrays.stream(candidateEnums).flatMap(e -> Arrays.stream(e.getEnumConstants())).collect(Collectors.toSet());

        for (String feature : features) {
            Set<Enum<?>> correspondingComputation = availableTransformations.stream().filter(at -> at.toString().equals(feature)).collect(Collectors.toSet());
            if (correspondingComputation.isEmpty()) {
                throw new IllegalArgumentException("Could not find transformation ID with the name " + feature + " among the available transformation IDs:" + availableTransformations);
            } else if (correspondingComputation.size() > 1) {
                throw new IllegalArgumentException("More than one computation with the name " + feature + " was found. Computation names used as features must be unique. Found:" + correspondingComputation);
            } else {
                featureIds.add(correspondingComputation.stream().map(x -> (V) x).findFirst().get());
            }
        }
        return ImmutableSortedSet.copyOf(FeatureNamespace.alphabetical(), featureIds);
    }

    @SafeVarargs
    public static <V extends FeatureNamespace> SortedSet<V> findFeatureNamespaces(Optional<JsonNode> transformerHyperparameters, Class<? extends Enum<?>>... candidateEnums) {
        Set<String> features = extractFeatureNamespaces(transformerHyperparameters);
        return findFeatureNamespaces(features, candidateEnums);
    }

    public static Set<String> extractFeatureNamespaces(Optional<JsonNode> transformerHyperparameters) {
        return extractNamespaces(HyperparamUtils.getOrFail(transformerHyperparameters, "features"));
    }

    public static Set<String> extractComputationNamespacesToCache(Optional<JsonNode> transformerHyperparameters) {
        return extractNamespaces(HyperparamUtils.getOrFail(transformerHyperparameters, "features"));
    }



    public static Set<String> extractNamespaces(JsonNode features) {
        return extractNamespaces((ArrayNode) features);
    }


    public static Set<String> extractNamespaces(ArrayNode featureList) {
        Set<String> ret = new HashSet<>();
        for (JsonNode featureNode : featureList) {
            if (featureNode.isArray()) {
                for (JsonNode interactedFeatures : featureNode) {
                    ret.add(interactedFeatures.asText());
                }
            } else {
                ret.add(featureNode.asText());
            }
        }
        return ret;
    }

}
