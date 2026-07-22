package com.hotvect.example.product;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductSearchExampleJsonTest {
    private static final String VALID_EXAMPLE = """
            {
              "example_id": "search-example-1",
              "occurred_at": "2000-01-03T12:00:00Z",
              "shared": {
                "query": "wooden blocks",
                "preferred_category": "building",
                "budget": 30.0
              },
              "outcomes": [
                {"action_id": "wooden-blocks", "clicked": true},
                {"action_id": "wooden-tea-set", "clicked": false}
              ],
              "k": 4
            }
            """;

    @Test
    void decoderBuildsAQueryOnlyTopKRequestAndSeparateJudgments() {
        var example = new ProductSearchTopKDecoderFactory().apply(Optional.empty()).apply(VALID_EXAMPLE).getFirst();

        assertEquals("wooden blocks", example.request().shared().query());
        assertEquals(4, example.request().k());
        assertEquals(2, example.outcomes().size());
        assertEquals("wooden-blocks", example.outcomes().getFirst().decision().actionId());
        assertTrue(example.outcomes().getFirst().outcome().clicked());
    }

    @Test
    void rejectsCandidateActionsBecauseSearchOwnsItsCatalog() {
        String withActions = VALID_EXAMPLE.replace(
                "\"k\": 4",
                "\"actions\": [], \"k\": 4"
        );

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> ProductSearchExampleJson.decode(withActions)
        );
        assertEquals("Unknown search example field: actions", error.getMessage());
    }

    @Test
    void requiresExactlyOneClickedProduct() {
        String withoutClick = VALID_EXAMPLE.replace("\"clicked\": true", "\"clicked\": false");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> ProductSearchExampleJson.decode(withoutClick)
        );
        assertEquals("outcomes must contain exactly one clicked product", error.getMessage());
    }

    @Test
    void rejectsDuplicateOutcomeIds() {
        String duplicate = VALID_EXAMPLE.replace("wooden-tea-set", "wooden-blocks");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> ProductSearchExampleJson.decode(duplicate)
        );
        assertEquals("Duplicate outcome action_id: wooden-blocks", error.getMessage());
    }
}
