package com.hotvect.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;

public final class AlgorithmDefinitionOverrideUtils {
    private static final Set<String> PROTECTED_FIELDS = Set.of("algorithm_name", "algorithm_version");

    private AlgorithmDefinitionOverrideUtils() {
    }

    public static JsonNode applyOverride(JsonNode baseDefinition, JsonNode overrideNode) {
        ObjectNode target = asObjectNode(baseDefinition, "Base algorithm definition");
        ObjectNode patch = asObjectNode(overrideNode, "Algorithm definition override");
        ObjectNode merged = target.deepCopy();
        mergeAlgorithmDefinitionObject(merged, patch, true);
        return merged;
    }

    public static JsonNode mergeOverrideFragments(JsonNode baseFragment, JsonNode overrideNode) {
        ObjectNode target = asObjectNode(baseFragment, "Base algorithm definition override fragment");
        ObjectNode patch = asObjectNode(overrideNode, "Algorithm definition override fragment");
        ObjectNode merged = target.deepCopy();
        mergeAlgorithmDefinitionObject(merged, patch, false);
        return merged;
    }

    private static ObjectNode asObjectNode(JsonNode node, String description) {
        checkArgument(node != null && node.isObject(), "%s must be a JSON object but was %s", description, node);
        return (ObjectNode) node;
    }

    private static void mergeAlgorithmDefinitionObject(ObjectNode target, ObjectNode patch, boolean validateDependenciesAgainstBase) {
        for (var fields = patch.fields(); fields.hasNext(); ) {
            var field = fields.next();
            String fieldName = field.getKey();
            JsonNode patchValue = field.getValue();

            if (PROTECTED_FIELDS.contains(fieldName)) {
                validateProtectedField(target, fieldName, patchValue);
                continue;
            }

            if ("dependencies".equals(fieldName)) {
                mergeDependencies(target, patchValue, validateDependenciesAgainstBase);
            } else {
                mergeGenericField(target, fieldName, patchValue);
            }
        }
    }

    private static void validateProtectedField(ObjectNode target, String fieldName, JsonNode patchValue) {
        if (patchValue == null || patchValue.isNull()) {
            throw new IllegalArgumentException("You may not delete " + fieldName);
        }

        JsonNode existing = target.get(fieldName);
        if (existing == null || !existing.equals(patchValue)) {
            throw new IllegalArgumentException("You may not override " + fieldName);
        }
    }

    private static void mergeGenericField(ObjectNode target, String fieldName, JsonNode patchValue) {
        if (patchValue == null || patchValue.isNull()) {
            target.remove(fieldName);
            return;
        }

        JsonNode existing = target.get(fieldName);
        if (existing != null && existing.isObject() && patchValue.isObject()) {
            mergeGenericObject((ObjectNode) existing, (ObjectNode) patchValue);
        } else {
            target.set(fieldName, patchValue.deepCopy());
        }
    }

    private static void mergeGenericObject(ObjectNode target, ObjectNode patch) {
        for (var fields = patch.fields(); fields.hasNext(); ) {
            var field = fields.next();
            String fieldName = field.getKey();
            JsonNode patchValue = field.getValue();
            if (patchValue == null || patchValue.isNull()) {
                target.remove(fieldName);
                continue;
            }

            JsonNode existing = target.get(fieldName);
            if (existing != null && existing.isObject() && patchValue.isObject()) {
                mergeGenericObject((ObjectNode) existing, (ObjectNode) patchValue);
            } else {
                target.set(fieldName, patchValue.deepCopy());
            }
        }
    }

    private static void mergeDependencies(ObjectNode target, JsonNode patchValue, boolean validateDependenciesAgainstBase) {
        checkArgument(
                patchValue != null && patchValue.isObject(),
                "dependencies override must be a JSON object keyed by child algorithm name"
        );

        LinkedHashMap<String, ObjectNode> mergedDependencies = validateDependenciesAgainstBase
                ? normalizeDeclaredDependencies(target.get("dependencies"))
                : normalizeOverrideFragmentDependencies(target.get("dependencies"));
        ObjectNode dependencyPatch = (ObjectNode) patchValue;

        for (var fields = dependencyPatch.fields(); fields.hasNext(); ) {
            var field = fields.next();
            String childName = AlgorithmDefinitionReader.extractAlgorithmName(field.getKey());
            JsonNode childPatch = field.getValue();
            checkArgument(
                    childPatch != null && childPatch.isObject(),
                    "Override for dependency %s must be a JSON object",
                    childName
            );
            if (validateDependenciesAgainstBase && !mergedDependencies.containsKey(childName)) {
                throw new IllegalArgumentException("Override references unknown dependency: " + childName);
            }

            ObjectNode baseChildOverride = mergedDependencies.getOrDefault(childName, JsonNodeFactory.instance.objectNode());
            ObjectNode mergedChildOverride = (ObjectNode) mergeOverrideFragments(baseChildOverride, childPatch);
            mergedDependencies.put(childName, mergedChildOverride);
        }

        ObjectNode normalized = JsonNodeFactory.instance.objectNode();
        for (var entry : mergedDependencies.entrySet()) {
            normalized.set(entry.getKey(), entry.getValue());
        }
        target.set("dependencies", normalized);
    }

    private static LinkedHashMap<String, ObjectNode> normalizeDeclaredDependencies(JsonNode baseDependencies) {
        LinkedHashMap<String, ObjectNode> normalized = new LinkedHashMap<>();
        if (baseDependencies == null || baseDependencies.isNull()) {
            return normalized;
        }

        if (baseDependencies.isArray()) {
            for (JsonNode dependency : baseDependencies) {
                checkArgument(dependency.isTextual(), "Dependency entries must be strings but found %s", dependency);
                String childName = AlgorithmDefinitionReader.extractAlgorithmName(dependency.asText());
                normalized.put(childName, JsonNodeFactory.instance.objectNode());
            }
            return normalized;
        }

        checkArgument(baseDependencies.isObject(), "dependencies must be an array or object but found %s", baseDependencies);
        for (var fields = baseDependencies.fields(); fields.hasNext(); ) {
            var field = fields.next();
            String childName = AlgorithmDefinitionReader.extractAlgorithmName(field.getKey());
            JsonNode childOverride = field.getValue();
            checkArgument(
                    childOverride != null && childOverride.isObject(),
                    "Embedded dependency override for %s must be a JSON object",
                    childName
            );
            normalized.put(childName, ((ObjectNode) childOverride).deepCopy());
        }
        return normalized;
    }

    private static LinkedHashMap<String, ObjectNode> normalizeOverrideFragmentDependencies(JsonNode baseDependencies) {
        LinkedHashMap<String, ObjectNode> normalized = new LinkedHashMap<>();
        if (baseDependencies == null || baseDependencies.isNull()) {
            return normalized;
        }

        checkArgument(baseDependencies.isObject(), "dependencies override fragment must be a JSON object");
        for (var fields = baseDependencies.fields(); fields.hasNext(); ) {
            var field = fields.next();
            String childName = AlgorithmDefinitionReader.extractAlgorithmName(field.getKey());
            JsonNode childOverride = field.getValue();
            checkArgument(
                    childOverride != null && childOverride.isObject(),
                    "Embedded dependency override for %s must be a JSON object",
                    childName
            );
            normalized.put(childName, ((ObjectNode) childOverride).deepCopy());
        }
        return normalized;
    }
}
