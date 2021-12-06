package com.eshioji.hotvect.util;

import com.eshioji.hotvect.commandline.FeatureStateDefinition;
import com.eshioji.hotvect.core.transform.FeatureState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableMap;

import java.io.InputStream;
import java.util.*;
import java.util.function.BiFunction;

public class FeatureStateLoader<R> implements BiFunction<Optional<JsonNode>, Map<String, InputStream>, Map<String, FeatureState>> {
    @Override
    public Map<String, FeatureState> apply(Optional<JsonNode> hyperparameters, Map<String, InputStream> serializedFeatureStates) {
        try {
            Map<String, ObjectNode> fsdInstructions = extract(hyperparameters);
            Map<String, FeatureStateDefinition<R, ? extends FeatureState>> featureStateDefinitions = instantiate(fsdInstructions);
            return hydrate(featureStateDefinitions, serializedFeatureStates);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


    private Map<String, ObjectNode> extract(Optional<JsonNode> hyperparameters) {
        Optional<ObjectNode> featureStateDefinitions = hyperparameters.map(x -> (ObjectNode) x.get("feature_states"));
        if (!featureStateDefinitions.isPresent()) {
            return ImmutableMap.of();
        } else {
            return extract(featureStateDefinitions.get());
        }
    }

    private Map<String, ObjectNode> extract(ObjectNode featureStateDefinition) {
        ImmutableMap.Builder<String, ObjectNode> b = ImmutableMap.builder();
        for (Iterator<String> i = featureStateDefinition.fieldNames(); i.hasNext(); ) {
            String featureStateName = i.next();
            b.put(featureStateName, (ObjectNode) featureStateDefinition.get(featureStateName));
        }
        return b.build();
    }

    private Map<String, FeatureStateDefinition<R, ? extends FeatureState>> instantiate(Map<String, ObjectNode> fsdInstructions) throws Exception {
        Map<String, FeatureStateDefinition<R, ? extends FeatureState>> ret = new HashMap<>();
        for (Map.Entry<String, ObjectNode> entry : fsdInstructions.entrySet()) {
            String fsdName = entry.getValue().get("definition").asText();
            FeatureStateDefinition<R, ? extends FeatureState> fsd = (FeatureStateDefinition<R, ? extends FeatureState>) Class.forName(fsdName).getDeclaredConstructor().newInstance();
            ret.put(fsdName, fsd);
        }
        return Collections.unmodifiableMap(ret);
    }

    private Map<String, FeatureState> hydrate(Map<String, FeatureStateDefinition<R, ? extends FeatureState>> featureStateDefinitions, Map<String, InputStream> parameters) {
        Map<String, FeatureState> hydrated = new HashMap<>();
        for (Map.Entry<String, FeatureStateDefinition<R, ? extends FeatureState>> entry : featureStateDefinitions.entrySet()) {
            String featureStateName = entry.getKey();
            InputStream is = parameters.get(featureStateName);
            hydrated.put(featureStateName, entry.getValue().getDeserializer().apply(is));
        }
        return hydrated;
    }

}
