package com.hotvect.api.data.scoring;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScoringDecisionTest {
    @Test
    void carriesActionIdWhenProvided() {
        var decision = ScoringDecision.of("sku-a", "a", 1.0, Map.of("source", "test"));

        assertEquals("sku-a", decision.actionId());
        assertEquals("a", decision.action());
        assertEquals(1.0, decision.score());
        assertEquals(Map.of("source", "test"), decision.additionalProperties());
    }

    @Test
    void legacyFactoriesKeepNullActionId() {
        assertNull(ScoringDecision.of("a", 1.0).actionId());
        assertNull(ScoringDecision.of("a", 1.0, Map.of()).actionId());
        assertNull(new ScoringDecision<>("a", 1.0).actionId());
        assertNull(new ScoringDecision<>("a", 1.0, Map.of()).actionId());
    }

    @Test
    void rejectsBlankActionIdWhenProvided() {
        assertThrows(IllegalArgumentException.class, () -> ScoringDecision.of(" ", "a", 1.0));
    }
}
