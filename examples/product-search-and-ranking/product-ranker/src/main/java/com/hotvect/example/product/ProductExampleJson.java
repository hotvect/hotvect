package com.hotvect.example.product;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotvect.api.data.AvailableAction;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ProductExampleJson {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> ROOT_FIELDS = Set.of("example_id", "occurred_at", "shared", "actions", "k");
    private static final Set<String> SHARED_FIELDS = Set.of("query", "preferred_category", "budget");
    private static final Set<String> ACTION_FIELDS = Set.of(
            "action_id", "title", "category", "price", "popularity", "novelty", "clicked"
    );

    private ProductExampleJson() {
    }

    static Decoded decode(String raw) {
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(raw);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Example must be valid JSON", error);
        }
        if (!root.isObject()) {
            throw new IllegalArgumentException("Example JSON must be an object");
        }
        requireOnlyFields(root, ROOT_FIELDS, "example");

        String exampleId = requiredText(root, "example_id");
        Instant occurredAt;
        try {
            occurredAt = Instant.parse(requiredText(root, "occurred_at"));
        } catch (DateTimeParseException error) {
            throw new IllegalArgumentException("occurred_at must be an ISO-8601 instant", error);
        }

        JsonNode shared = requiredObject(root, "shared");
        requireOnlyFields(shared, SHARED_FIELDS, "shared");
        ProductQuery query = new ProductQuery(
                requiredText(shared, "query"),
                requiredText(shared, "preferred_category"),
                requiredNumber(shared, "budget")
        );

        JsonNode rawActions = root.get("actions");
        if (rawActions == null || !rawActions.isArray() || rawActions.isEmpty()) {
            throw new IllegalArgumentException("actions must be a non-empty array");
        }

        List<AvailableAction<Product>> actions = new ArrayList<>(rawActions.size());
        List<Boolean> clicked = new ArrayList<>(rawActions.size());
        Set<String> actionIds = new HashSet<>();
        int labeledActions = 0;
        for (JsonNode rawAction : rawActions) {
            if (!rawAction.isObject()) {
                throw new IllegalArgumentException("Each action must be an object");
            }
            requireOnlyFields(rawAction, ACTION_FIELDS, "action");
            String actionId = requiredText(rawAction, "action_id");
            if (!actionIds.add(actionId)) {
                throw new IllegalArgumentException("Duplicate action_id: " + actionId);
            }
            Product product = new Product(
                    requiredText(rawAction, "title"),
                    requiredText(rawAction, "category"),
                    requiredNumber(rawAction, "price"),
                    requiredNumber(rawAction, "popularity"),
                    requiredNumber(rawAction, "novelty")
            );
            Map<String, Object> additionalProperties = new LinkedHashMap<>();
            additionalProperties.put("action_name", product.title());
            actions.add(AvailableAction.of(actionId, product, Map.copyOf(additionalProperties)));

            JsonNode clickedNode = rawAction.get("clicked");
            if (clickedNode == null) {
                clicked.add(false);
            } else {
                if (!clickedNode.isBoolean()) {
                    throw new IllegalArgumentException("clicked must be a boolean");
                }
                clicked.add(clickedNode.booleanValue());
                labeledActions++;
            }
        }
        if (labeledActions != 0 && labeledActions != actions.size()) {
            throw new IllegalArgumentException("clicked must be present for every action or no actions");
        }

        Integer k = null;
        JsonNode kNode = root.get("k");
        if (kNode != null) {
            if (!kNode.isIntegralNumber() || !kNode.canConvertToInt() || kNode.intValue() <= 0) {
                throw new IllegalArgumentException("k must be a positive integer");
            }
            k = kNode.intValue();
        }
        return new Decoded(
                exampleId,
                occurredAt,
                query,
                List.copyOf(actions),
                labeledActions == 0 ? List.of() : List.copyOf(clicked),
                k
        );
    }

    private static JsonNode requiredObject(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException(field + " must be an object");
        }
        return value;
    }

    private static String requiredText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException(field + " must be a non-blank string");
        }
        return value.textValue();
    }

    private static double requiredNumber(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isNumber()) {
            throw new IllegalArgumentException(field + " must be a number");
        }
        return value.doubleValue();
    }

    private static void requireOnlyFields(JsonNode object, Set<String> allowedFields, String objectName) {
        var fields = object.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!allowedFields.contains(field)) {
                throw new IllegalArgumentException("Unknown " + objectName + " field: " + field);
            }
        }
    }

    record Decoded(
            String exampleId,
            Instant occurredAt,
            ProductQuery query,
            List<AvailableAction<Product>> actions,
            List<Boolean> clicked,
            Integer k
    ) {
        boolean labeled() {
            return !clicked.isEmpty();
        }

        int requireK() {
            if (k == null) {
                throw new IllegalArgumentException("k is required for a TopK example");
            }
            return k;
        }
    }
}
