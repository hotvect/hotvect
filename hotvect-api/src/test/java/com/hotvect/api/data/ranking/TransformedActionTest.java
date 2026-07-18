package com.hotvect.api.data.ranking;

import com.hotvect.api.data.Namespace;
import com.hotvect.api.data.common.NamespacedRecord;
import com.hotvect.api.data.common.NamespacedRecordImpl;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class TransformedActionTest {
    private final NamespacedRecord<Namespace, Object> transformed = new NamespacedRecordImpl<>();

    @Test
    void legacyConstructorKeepsNullActionId() {
        TransformedAction<String> action = new TransformedAction<>("sku", transformed);

        assertNull(action.actionId());
        assertEquals("sku", action.action());
        assertSame(transformed, action.transformed());
    }

    @Test
    void legacyConstructorKeepsAdditionalProperties() {
        Map<String, Object> additionalProperties = Map.of("source", "legacy");

        TransformedAction<String> action = new TransformedAction<>("sku", transformed, additionalProperties);

        assertNull(action.actionId());
        assertEquals(additionalProperties, action.additionalProperties());
    }

    @Test
    void legacyFactoryKeepsNullActionId() {
        TransformedAction<String> action = TransformedAction.of("sku", transformed);

        assertNull(action.actionId());
        assertEquals("sku", action.action());
    }

    @Test
    void actionIdFactoryKeepsActionId() {
        TransformedAction<String> action = TransformedAction.of("sku-1", "sku", transformed);

        assertEquals("sku-1", action.actionId());
        assertEquals("sku", action.action());
    }
}
