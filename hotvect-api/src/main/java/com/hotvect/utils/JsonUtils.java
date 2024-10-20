package com.hotvect.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;

public class JsonUtils {
    public static JsonNode deepMergeJsonNodeWithArrayReplacement(JsonNode mainNode, JsonNode updateNode) {
        Iterator<String> fieldNames = updateNode.fieldNames();
        while (fieldNames.hasNext()) {
            String fieldName = fieldNames.next();
            JsonNode jsonNode = mainNode.get(fieldName);

            if(jsonNode != null && jsonNode.isObject()){
                // If this node is an object, recursively apply deep merge
                deepMergeJsonNodeWithArrayReplacement(jsonNode, updateNode.get(fieldName));
            } else {
                // In all other cases, including if it's an array, replace it withe value from the updateNode
                // If it is non-null
                JsonNode updatedValue = updateNode.get(fieldName);
                if(updatedValue != null && !updatedValue.isNull()){
                    ((ObjectNode) mainNode).replace(fieldName, updatedValue);
                }
                // Otherwise don't do anything
            }
        }
        return mainNode;
    }
}
