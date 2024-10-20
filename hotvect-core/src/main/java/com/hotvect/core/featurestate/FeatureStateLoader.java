package com.hotvect.core.featurestate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableMap;
import com.hotvect.api.state.State;
import com.hotvect.api.state.StateCodec;

import java.io.InputStream;
import java.util.*;
import java.util.function.BiFunction;

public class FeatureStateLoader implements BiFunction<Optional<JsonNode>, Map<String, InputStream>, Map<String, State>> {
    private final ClassLoader classLoader;

    public FeatureStateLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    @Override
    public Map<String, State> apply(Optional<JsonNode> hyperparameters, Map<String, InputStream> serializedFeatureStates) {
        try {
            Map<String, ObjectNode> fsdInstructions = extract(hyperparameters);
            Map<String, StateCodec<? extends State>> featureStateDefinitions = instantiate(fsdInstructions);
            return hydrate(featureStateDefinitions, serializedFeatureStates);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


    private Map<String, ObjectNode> extract(Optional<JsonNode> hyperparameters) {
        Optional<ObjectNode> featureStateDefinitions = hyperparameters.map(x -> (ObjectNode) x.get("feature_states"));
        if (featureStateDefinitions.isEmpty()) {
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

    private Map<String, StateCodec<? extends State>> instantiate(Map<String, ObjectNode> fsdInstructions) throws Exception {
        Map<String, StateCodec<? extends State>> ret = new HashMap<>();
        for (Map.Entry<String, ObjectNode> entry : fsdInstructions.entrySet()) {
            String fsCodecName = entry.getValue().get("codec").asText();
            StateCodec<? extends State> fsd = (StateCodec<? extends State>) Class.forName(fsCodecName, true, classLoader).getDeclaredConstructor().newInstance();
            ret.put(entry.getKey(), fsd);
        }
        return Collections.unmodifiableMap(ret);
    }

    private Map<String, State> hydrate(Map<String, StateCodec<? extends State>> featureStateDefinitions, Map<String, InputStream> parameters) {
        Map<String, State> hydrated = new HashMap<>();
        for (Map.Entry<String, StateCodec<? extends State>> entry : featureStateDefinitions.entrySet()) {
            String featureStateName = entry.getKey();
            InputStream is = parameters.get(featureStateName);
            hydrated.put(featureStateName, entry.getValue().getDeserializer().apply(is));
        }
        return hydrated;
    }

}
