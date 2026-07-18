package com.hotvect.example.product;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Set;

final class ProductCatalogJson {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> FIELDS = Set.of(
            "action_id", "title", "category", "price", "popularity", "novelty"
    );

    private ProductCatalogJson() {
    }

    static Entry decode(String raw) {
        JsonNode node;
        try {
            node = OBJECT_MAPPER.readTree(raw);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Catalog row must be valid JSON", error);
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("Catalog row must be a JSON object");
        }
        var fields = node.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!FIELDS.contains(field)) {
                throw new IllegalArgumentException("Unknown catalog field: " + field);
            }
        }
        String actionId = requiredText(node, "action_id");
        Product product = new Product(
                requiredText(node, "title"),
                requiredText(node, "category"),
                requiredNumber(node, "price"),
                requiredNumber(node, "popularity"),
                requiredNumber(node, "novelty")
        );
        return new Entry(actionId, product);
    }

    static String encode(Entry entry) {
        ObjectNode node = OBJECT_MAPPER.createObjectNode();
        node.put("action_id", entry.actionId());
        node.put("title", entry.product().title());
        node.put("category", entry.product().category());
        node.put("price", entry.product().price());
        node.put("popularity", entry.product().popularity());
        node.put("novelty", entry.product().novelty());
        return node.toString();
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

    record Entry(String actionId, Product product) {
    }
}
