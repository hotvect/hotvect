package com.hotvect.api.data.topk;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TopKDataContractTest {

    @Test
    void rejectsNullActionId() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TopKDecision.builder(null, "action").build());

        assertEquals("actionId cannot be null or blank", error.getMessage());
    }

    @Test
    void rejectsBlankActionId() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TopKDecision.builder(" ", "action").build());

        assertEquals("actionId cannot be null or blank", error.getMessage());
    }

    @Test
    void builderNormalizesNullActionListMetadata() {
        ThemedTopKResponse<String> response = ThemedTopKResponse
                .builder("featured", List.of(TopKDecision.builder("sku-1", "action").build()))
                .withActionListMetadata(null)
                .build();

        assertEquals(Map.of(), response.getActionListMetadata());
    }
}
