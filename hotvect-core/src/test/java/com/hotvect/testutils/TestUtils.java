package com.hotvect.testutils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public enum TestUtils {
    ;

    private static final ObjectMapper mapper = new ObjectMapper();

    public static void assertJsonEquals(String json1, String json2) {
        try {
            JsonNode tree1 = mapper.readTree(json1);
            JsonNode tree2 = mapper.readTree(json2);
            if (!tree1.equals(tree2)) {
                throw new AssertionError(String.format("%s differs from %s", tree1, tree2));
            }
        } catch (JsonProcessingException e) {
            throw new AssertionError(String.format("Could not parse %s or %s", json1, json2));
        }
    }
}
