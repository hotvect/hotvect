package com.hotvect.utils;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AdditionalPropertiesTest {

    private static final class WithNullAdditionalProperties {
        @SuppressWarnings("unused")
        public Map<String, Object> getAdditionalProperties() {
            return null;
        }
    }

    private static final class WithBuilderStyleAdditionalProperties {
        @SuppressWarnings("unused")
        public Map<String, Object> additionalProperties() {
            return Map.of("k", "v");
        }
    }

    @Test
    void getAdditionalPropertiesTreatsNullAsEmpty() {
        assertEquals(Collections.emptyMap(), AdditionalProperties.getAdditionalProperties(new WithNullAdditionalProperties()));
    }

    @Test
    void getAdditionalPropertiesSupportsBuilderStyleAccessor() {
        assertEquals(Map.of("k", "v"), AdditionalProperties.getAdditionalProperties(new WithBuilderStyleAdditionalProperties()));
    }
}
