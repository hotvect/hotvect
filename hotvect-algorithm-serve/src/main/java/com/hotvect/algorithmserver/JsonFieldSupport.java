package com.hotvect.algorithmserver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Optional;

public final class JsonFieldSupport {
    private JsonFieldSupport() {
    }

    public static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public static Optional<String> nonEmptyStringField(JsonNode node, String field) {
        return Optional.ofNullable(textFieldOrNull(node, field));
    }

    public static String textFieldOrNull(JsonNode node, String field) {
        return textOrNull(node == null ? null : node.get(field));
    }

    public static String textOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return blankToNull(node.asText());
    }

    public static void putStringOrNull(ObjectNode dest, String field, String value) {
        if (blankToNull(value) == null) {
            dest.putNull(field);
            return;
        }
        dest.put(field, value);
    }

    public static void putNumberOrNull(ObjectNode dest, String field, Double value) {
        if (value == null || !Double.isFinite(value)) {
            dest.putNull(field);
            return;
        }
        dest.put(field, value);
    }
}
