package com.hotvect.offlineutils.export;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TopKResultFormatterAdditionalPropertiesTest {

    private static final class WithNullAdditionalProperties {
        @SuppressWarnings("unused")
        public Map<String, Object> getAdditionalProperties() {
            return null;
        }
    }

    private static final class ExposingFormatter extends TopKResultFormatter<Object, Object, Object> {
        public Map<String, Object> expose(Object object) {
            return getAdditionalProperties(object);
        }
    }

    @Test
    void getAdditionalPropertiesTreatsNullAsEmpty() {
        assertEquals(Collections.emptyMap(), new ExposingFormatter().expose(new WithNullAdditionalProperties()));
    }
}

