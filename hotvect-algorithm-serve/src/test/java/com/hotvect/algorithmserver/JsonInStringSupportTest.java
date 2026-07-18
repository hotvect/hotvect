package com.hotvect.algorithmserver;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class JsonInStringSupportTest {
    private static final ObjectMapper OM = new ObjectMapper();

    @Test
    void collapseVirtualJsonFieldsRoundTripsUiVirtualFields() throws Exception {
        ObjectNode root = OM.createObjectNode();
        root.put("data", "{\"request_id\":\"r-1\"}");

        JsonInStringSupport.injectVirtualJsonFields(root);
        ((ObjectNode) root.get("data__json")).put("extra", 42);

        JsonInStringSupport.collapseVirtualJsonFields(root);

        assertFalse(root.has("data__json"));
        assertEquals("{\"request_id\":\"r-1\",\"extra\":42}", OM.readTree(root.get("data").asText()).toString());
    }
}
