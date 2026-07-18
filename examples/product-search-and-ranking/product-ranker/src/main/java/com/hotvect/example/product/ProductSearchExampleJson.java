package com.hotvect.example.product;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class ProductSearchExampleJson {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> ROOT_FIELDS = Set.of("example_id", "occurred_at", "shared", "outcomes", "k");
    private static final Set<String> SHARED_FIELDS = Set.of("query", "preferred_category", "budget");
    private static final Set<String> OUTCOME_FIELDS = Set.of("action_id", "clicked");

    private ProductSearchExampleJson() {
    }

    static Decoded decode(String raw) {
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(raw);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Search example must be valid JSON", error);
        }
        if (!root.isObject()) {
            throw new IllegalArgumentException("Search example JSON must be an object");
        }
        requireOnlyFields(root, ROOT_FIELDS, "search example");

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

        JsonNode rawOutcomes = root.get("outcomes");
        if (rawOutcomes == null || !rawOutcomes.isArray() || rawOutcomes.isEmpty()) {
            throw new IllegalArgumentException("outcomes must be a non-empty array");
        }
        List<LabeledAction> outcomes = new ArrayList<>(rawOutcomes.size());
        Set<String> actionIds = new HashSet<>();
        int clickedCount = 0;
        for (JsonNode rawOutcome : rawOutcomes) {
            if (!rawOutcome.isObject()) {
                throw new IllegalArgumentException("Each outcome must be an object");
            }
            requireOnlyFields(rawOutcome, OUTCOME_FIELDS, "outcome");
            String actionId = requiredText(rawOutcome, "action_id");
            if (!actionIds.add(actionId)) {
                throw new IllegalArgumentException("Duplicate outcome action_id: " + actionId);
            }
            JsonNode clickedNode = rawOutcome.get("clicked");
            if (clickedNode == null || !clickedNode.isBoolean()) {
                throw new IllegalArgumentException("clicked must be a boolean");
            }
            boolean clicked = clickedNode.booleanValue();
            if (clicked) {
                clickedCount++;
            }
            outcomes.add(new LabeledAction(actionId, clicked));
        }
        if (clickedCount != 1) {
            throw new IllegalArgumentException("outcomes must contain exactly one clicked product");
        }

        JsonNode kNode = root.get("k");
        if (kNode == null || !kNode.isIntegralNumber() || !kNode.canConvertToInt() || kNode.intValue() <= 0) {
            throw new IllegalArgumentException("k must be a positive integer");
        }
        return new Decoded(exampleId, occurredAt, query, List.copyOf(outcomes), kNode.intValue());
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

    record LabeledAction(String actionId, boolean clicked) {
    }

    record Decoded(
            String exampleId,
            Instant occurredAt,
            ProductQuery query,
            List<LabeledAction> outcomes,
            int k
    ) {
    }
}
