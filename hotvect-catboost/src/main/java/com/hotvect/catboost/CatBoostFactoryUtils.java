package com.hotvect.catboost;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.utils.HyperparamUtils;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;

final class CatBoostFactoryUtils {
    static final String MODEL_PARAMETER_KEY_V10 = "model_parameter/model.parameter";
    static final String MODEL_PARAMETER_KEY_V9 = "model.parameter";

    private CatBoostFactoryUtils() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    static InputStream getModelStream(Map<String, InputStream> parameters) {
        InputStream modelStream = parameters.get(MODEL_PARAMETER_KEY_V10);
        if (modelStream == null) {
            modelStream = parameters.get(MODEL_PARAMETER_KEY_V9);
        }
        return modelStream;
    }

    static String getTaskType(Optional<JsonNode> hyperparameter) {
        return HyperparamUtils.getOrDefault(hyperparameter, JsonNode::asText, "classification", "task_type");
    }
}
