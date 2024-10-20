package com.hotvect.utils;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Arrays;
import java.util.Optional;
import java.util.function.Function;

public class HyperparamUtils {
    private HyperparamUtils() {
    }

    public static JsonNode getOrFail(Optional<JsonNode> hyperparameter, String... paths) {
        if (hyperparameter.isEmpty()) {
            throw new IllegalArgumentException("Hyperparameter of path " + Arrays.toString(paths) + " is required but not present");
        }
        JsonNode root = hyperparameter.get();
        for (String path : paths) {
            root = root.get(path);
        }
        if(root == null){
            throw new IllegalArgumentException("Hyperparameter of path " + Arrays.toString(paths) + " is null");
        }
        return root;
    }

    public static <V> V getOrFail(Optional<JsonNode> hyperparameter, Function<JsonNode, V> converter, String... paths) {
        JsonNode v = getOrFail(hyperparameter, paths);
        return converter.apply(v);
    }

    public static <V> V getOrDefault(Optional<JsonNode> hyperparameter, Function<JsonNode, V> converter, V defaultVal, String... paths) {
        if(hyperparameter.isEmpty()){
            return defaultVal;
        }
        JsonNode root = hyperparameter.get();
        for (String path : paths) {
            root = root.get(path);
            if (root == null){
                return defaultVal;
            }
        }
        return converter.apply(root);
    }


}
