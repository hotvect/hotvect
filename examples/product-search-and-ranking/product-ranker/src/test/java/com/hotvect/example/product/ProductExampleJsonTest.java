package com.hotvect.example.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductExampleJsonTest {
    private static final String VALID_EXAMPLE = """
            {
              "example_id": "example-1",
              "occurred_at": "2000-01-03T12:00:00Z",
              "shared": {
                "query": "wooden blocks",
                "preferred_category": "building",
                "budget": 30.0
              },
              "actions": [
                {
                  "action_id": "wooden-blocks",
                  "title": "Natural wooden building blocks",
                  "category": "building",
                  "price": 24.5,
                  "popularity": 0.76,
                  "novelty": 0.31,
                  "clicked": true
                },
                {
                  "action_id": "wooden-tea-set",
                  "title": "Wooden tea party set",
                  "category": "pretend-play",
                  "price": 31.5,
                  "popularity": 0.75,
                  "novelty": 0.63,
                  "clicked": false
                }
              ],
              "k": 1
            }
            """;

    @Test
    void decodesStableActionIdsAndLabels() {
        ProductExampleJson.Decoded decoded = ProductExampleJson.decode(VALID_EXAMPLE);

        assertEquals("example-1", decoded.exampleId());
        assertEquals("wooden blocks", decoded.query().query());
        assertEquals(2, decoded.actions().size());
        assertEquals("wooden-blocks", decoded.actions().getFirst().actionId());
        assertEquals("Natural wooden building blocks", decoded.actions().getFirst().additionalProperties().get("action_name"));
        assertEquals(java.util.List.of(true, false), decoded.clicked());
        assertEquals(1, decoded.requireK());
    }

    @Test
    @SuppressWarnings("removal")
    void rankingDecoderUsesTheCandidateContract() {
        var rankingExample = new ProductRankingDecoderFactory().apply(Optional.empty()).apply(VALID_EXAMPLE).getFirst();

        assertEquals("wooden-blocks", rankingExample.request().actions().getFirst().actionId());
        assertEquals("wooden-blocks", rankingExample.outcomes().getFirst().decision().actionId());
        assertTrue(rankingExample.outcomes().getFirst().outcome().clicked());
    }

    @Test
    void rejectsUnknownFields() {
        String withUnknownField = VALID_EXAMPLE.replace(
                "\"budget\": 30.0",
                "\"budget\": 30.0, \"currency\": \"EUR\""
        );

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> ProductExampleJson.decode(withUnknownField)
        );
        assertEquals("Unknown shared field: currency", error.getMessage());
    }

    @Test
    void rejectsPartiallyLabeledExamples() throws Exception {
        ObjectNode partiallyLabeled = (ObjectNode) new ObjectMapper().readTree(VALID_EXAMPLE);
        ((ObjectNode) partiallyLabeled.withArray("actions").get(1)).remove("clicked");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> ProductExampleJson.decode(partiallyLabeled.toString())
        );
        assertEquals("clicked must be present for every action or no actions", error.getMessage());
    }

    @Test
    void rejectsDuplicateActionIds() {
        String duplicate = VALID_EXAMPLE.replace("\"action_id\": \"wooden-tea-set\"", "\"action_id\": \"wooden-blocks\"");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> ProductExampleJson.decode(duplicate)
        );
        assertEquals("Duplicate action_id: wooden-blocks", error.getMessage());
    }

    @Test
    void topKDecoderRequiresK() {
        String withoutK = VALID_EXAMPLE.replace(",\n  \"k\": 1", "");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> ProductExampleJson.decode(withoutK).requireK()
        );
        assertEquals("k is required for a TopK example", error.getMessage());
    }

    @Test
    void rejectsFractionalK() {
        String fractionalK = VALID_EXAMPLE.replace("\"k\": 1", "\"k\": 1.5");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> ProductExampleJson.decode(fractionalK)
        );
        assertEquals("k must be a positive integer", error.getMessage());
    }

    @Test
    void rejectsQueryWithoutFeatureTokens() {
        String punctuationQuery = VALID_EXAMPLE.replace("\"query\": \"wooden blocks\"", "\"query\": \"!!!\"");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> ProductExampleJson.decode(punctuationQuery)
        );
        assertEquals("query must contain an ASCII letter or digit", error.getMessage());
    }
}
