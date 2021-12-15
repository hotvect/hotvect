package com.eshioji.hotvect.onlineutils.hotdeploy;

import com.eshioji.hotvect.api.featurestate.FeatureStateCodec;
import com.eshioji.hotvect.api.featurestate.FeatureState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableMap;

import java.io.InputStream;
import java.util.*;
import java.util.function.BiFunction;

public class FeatureStateLoader<R> implements BiFunction<Optional<JsonNode>, Map<String, InputStream>, Map<String, FeatureState>> {
    private final ClassLoader classLoader;

    public FeatureStateLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    @Override
    public Map<String, FeatureState> apply(Optional<JsonNode> hyperparameters, Map<String, InputStream> serializedFeatureStates) {
        try {
            Map<String, ObjectNode> fsdInstructions = extract(hyperparameters);
            Map<String, FeatureStateCodec<? extends FeatureState>> featureStateDefinitions = instantiate(fsdInstructions);
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

    private Map<String, FeatureStateCodec<? extends FeatureState>> instantiate(Map<String, ObjectNode> fsdInstructions) throws Exception {
        Map<String, FeatureStateCodec<? extends FeatureState>> ret = new HashMap<>();
        for (Map.Entry<String, ObjectNode> entry : fsdInstructions.entrySet()) {
            String fsCodecName = entry.getValue().get("codec").asText();
            FeatureStateCodec<? extends FeatureState> fsd = (FeatureStateCodec<? extends FeatureState>) Class.forName(fsCodecName, true, classLoader).getDeclaredConstructor().newInstance();
            ret.put(entry.getKey(), fsd);
        }
        return Collections.unmodifiableMap(ret);
    }

    private Map<String, FeatureState> hydrate(Map<String, FeatureStateCodec<? extends FeatureState>> featureStateDefinitions, Map<String, InputStream> parameters) {
        Map<String, FeatureState> hydrated = new HashMap<>();
        for (Map.Entry<String, FeatureStateCodec<? extends FeatureState>> entry : featureStateDefinitions.entrySet()) {
            String featureStateName = entry.getKey();
            InputStream is = parameters.get(featureStateName);
            hydrated.put(featureStateName, entry.getValue().getDeserializer().apply(is));
        }
        return hydrated;
    }

}
