package com.hotvect.example.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductExplorationDefinitionTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void publishesAnotherVersionOfTheSameRankerContract() throws Exception {
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("example-product-ranker-algorithm-definition.json")) {
            assertNotNull(input);
            JsonNode definition = OBJECT_MAPPER.readTree(input);

            assertEquals("example-product-ranker", definition.get("algorithm_name").textValue());
            assertEquals("1.3.0", definition.get("algorithm_version").textValue());
            assertEquals(
                    "com.hotvect.example.product.ProductRankingDecoderFactory",
                    definition.get("decoder_factory_classname").textValue()
            );
            assertEquals(
                    "com.hotvect.example.product.ProductExplorationRankerFactory",
                    definition.get("algorithm_factory_classname").textValue()
            );
            assertEquals(1, definition.get("dependencies").size());
            assertTrue(definition.get("dependencies").has("example-product-scorer"));
        }
    }

    @Test
    void publishesAnotherVersionOfTheSameSearchTopKContract() throws Exception {
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("example-product-search-topk-algorithm-definition.json")) {
            assertNotNull(input);
            JsonNode definition = OBJECT_MAPPER.readTree(input);

            assertEquals("example-product-search-topk", definition.get("algorithm_name").textValue());
            assertEquals("1.3.0", definition.get("algorithm_version").textValue());
            assertEquals(
                    "com.hotvect.example.product.ProductSearchTopKDecoderFactory",
                    definition.get("decoder_factory_classname").textValue()
            );
            assertEquals(
                    "com.hotvect.example.product.ProductSearchTopKFactory",
                    definition.get("algorithm_factory_classname").textValue()
            );
            assertEquals(2, definition.get("dependencies").size());
            assertTrue(definition.get("dependencies").has("example-product-search-index"));
            assertTrue(definition.get("dependencies").has("example-product-ranker"));
        }
    }
}
