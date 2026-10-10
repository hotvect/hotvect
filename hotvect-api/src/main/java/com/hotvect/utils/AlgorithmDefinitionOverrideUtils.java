package com.hotvect.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static com.google.common.base.Preconditions.checkArgument;

public final class AlgorithmDefinitionOverrideUtils {
    private static final Set<String> PROTECTED_FIELDS = Set.of("algorithm_name", "algorithm_version");
    private static final Set<String> ALGORITHM_DEFINITION_FIELDS = Set.of(
            "algorithm_name",
            "algorithm_version",
            "hyperparameter_version",
            "hotvect_version",
            "dependencies",
            "generator_factory_classname",
            "decoder_factory_classname",
            "transformer_factory_classname",
            "vectorizer_factory_classname",
            "reward_function_factory_classname",
            "encoder_factory_classname",
            "algorithm_factory_classname",
            "transformer_parameters",
            "vectorizer_parameters",
            "train_decoder_parameters",
            "test_decoder_parameters",
            "algorithm_parameters",
            "catboost_options",
            "train_data_spec",
            "test_data_spec",
            "prediction_spec",
            "performance_data_spec",
            "train_data_prefix",
            "test_data_prefix",
            "number_of_training_days",
            "training_lag_days",
            "training_command",
            "training_container",
            "git_describe",
            "source_data",
            "state_output_filename",
            "hotvect_execution_parameters",
            "sagemaker_training_job_definition");

    private AlgorithmDefinitionOverrideUtils() {
    }

    public static JsonNode applyOverride(JsonNode baseDefinition, JsonNode overrideNode) {
        return applyOverride(
                baseDefinition,
                overrideNode,
                AlgorithmDefinitionReader.DependencyResolution.ONLINE);
    }

    /**
     * Applies one override using the dependency rules for the selected execution mode.
     *
     * <p>Offline execution treats statically shared dependencies as private. Their version suffixes
     * are therefore accepted but normalized away, and their definitions may be patched by the
     * enclosing experiment.</p>
     */
    public static JsonNode applyOverride(
            JsonNode baseDefinition,
            JsonNode overrideNode,
            AlgorithmDefinitionReader.DependencyResolution dependencyResolution) {
        ObjectNode target = asObjectNode(baseDefinition, "Base algorithm definition");
        ObjectNode patch = asObjectNode(overrideNode, "Algorithm definition override");
        AlgorithmDefinitionReader.DependencyResolution resolvedDependencyResolution =
                java.util.Objects.requireNonNull(dependencyResolution, "dependencyResolution must not be null");
        validateOverrideFields(target, patch);
        ObjectNode merged = target.deepCopy();
        mergeAlgorithmDefinitionObject(merged, patch, true, resolvedDependencyResolution);
        return merged;
    }

    public static JsonNode mergeOverrideFragments(JsonNode baseFragment, JsonNode overrideNode) {
        return mergeOverrideFragments(
                baseFragment,
                overrideNode,
                AlgorithmDefinitionReader.DependencyResolution.ONLINE);
    }

    /** Merges two override fragments using the dependency rules for the selected execution mode. */
    public static JsonNode mergeOverrideFragments(
            JsonNode baseFragment,
            JsonNode overrideNode,
            AlgorithmDefinitionReader.DependencyResolution dependencyResolution) {
        ObjectNode target = asObjectNode(baseFragment, "Base algorithm definition override fragment");
        ObjectNode patch = asObjectNode(overrideNode, "Algorithm definition override fragment");
        AlgorithmDefinitionReader.DependencyResolution resolvedDependencyResolution =
                java.util.Objects.requireNonNull(dependencyResolution, "dependencyResolution must not be null");
        validateOverrideFragmentDependencyScopes(target, resolvedDependencyResolution);
        validateOverrideFragmentDependencyScopes(patch, resolvedDependencyResolution);
        return mergeOverrideObjects(target, patch, resolvedDependencyResolution);
    }

    private static ObjectNode mergeOverrideObjects(
            ObjectNode target,
            ObjectNode patch,
            AlgorithmDefinitionReader.DependencyResolution dependencyResolution) {
        validateOverrideFields(target, patch);
        ObjectNode merged = target.deepCopy();
        mergeAlgorithmDefinitionObject(merged, patch, false, dependencyResolution);
        return merged;
    }

    private static ObjectNode asObjectNode(JsonNode node, String description) {
        checkArgument(node != null && node.isObject(), "%s must be a JSON object but was %s", description, node);
        return (ObjectNode) node;
    }

    private static void validateOverrideFields(ObjectNode target, ObjectNode patch) {
        TreeSet<String> unknown = new TreeSet<>();
        patch.fieldNames().forEachRemaining(fieldName -> {
            if (!target.has(fieldName) && !ALGORITHM_DEFINITION_FIELDS.contains(fieldName)) {
                unknown.add(fieldName);
            }
        });
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Unknown algorithm definition override fields: " + unknown);
        }
    }

    private static void mergeAlgorithmDefinitionObject(
            ObjectNode target,
            ObjectNode patch,
            boolean validateDependenciesAgainstBase,
            AlgorithmDefinitionReader.DependencyResolution dependencyResolution) {
        for (var fields = patch.fields(); fields.hasNext(); ) {
            var field = fields.next();
            String fieldName = field.getKey();
            JsonNode patchValue = field.getValue();

            if (PROTECTED_FIELDS.contains(fieldName)) {
                throw new IllegalArgumentException(
                        "Algorithm definition override must not contain identity field: " + fieldName);
            }

            if ("dependencies".equals(fieldName)) {
                mergeDependencies(target, patchValue, validateDependenciesAgainstBase, dependencyResolution);
            } else {
                mergeGenericField(target, fieldName, patchValue);
            }
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

    private static void mergeDependencies(
            ObjectNode target,
            JsonNode patchValue,
            boolean validateDependenciesAgainstBase,
            AlgorithmDefinitionReader.DependencyResolution dependencyResolution) {
        checkArgument(
                patchValue != null && patchValue.isObject(),
                "dependencies override must be a JSON object keyed by child algorithm name"
        );

        LinkedHashMap<String, DependencyEntry> mergedDependencies = validateDependenciesAgainstBase
                ? normalizeDeclaredDependencies(target.get("dependencies"))
                : normalizeOverrideFragmentDependencies(target.get("dependencies"), dependencyResolution);
        ObjectNode dependencyPatch = (ObjectNode) patchValue;

        for (var fields = dependencyPatch.fields(); fields.hasNext(); ) {
            var field = fields.next();
            String childName = requireDependencyOverrideKey(field.getKey(), dependencyResolution);
            JsonNode childPatch = field.getValue();
            checkArgument(
                    childPatch != null && childPatch.isObject(),
                    "Override for dependency %s must be a JSON object",
                    childName
            );
            checkArgument(
                    !childPatch.has("scope"),
                    "Override for dependency %s must not declare scope",
                    childName
            );
            if (validateDependenciesAgainstBase && !mergedDependencies.containsKey(childName)) {
                throw new IllegalArgumentException("Override references unknown dependency: " + childName);
            }

            DependencyEntry existing = mergedDependencies.get(childName);
            boolean scoped = existing != null && existing.declaration().has("scope");
            boolean staticShared = scoped && "shared".equals(existing.declaration().path("scope").asText());
            if (scoped
                    && (!staticShared || dependencyResolution == AlgorithmDefinitionReader.DependencyResolution.ONLINE)
                    && !childPatch.isEmpty()) {
                throw new IllegalArgumentException(
                        "Override for scoped dependency " + childName + " must be empty");
            }
            ObjectNode baseChildOverride = existing == null
                    ? JsonNodeFactory.instance.objectNode()
                    : existing.declaration().deepCopy();
            String declarationKey = existing == null ? field.getKey() : existing.declarationKey();
            if (staticShared && dependencyResolution == AlgorithmDefinitionReader.DependencyResolution.OFFLINE) {
                baseChildOverride.remove("scope");
                declarationKey = childName;
            } else if (existing == null && dependencyResolution == AlgorithmDefinitionReader.DependencyResolution.OFFLINE) {
                declarationKey = childName;
            }
            ObjectNode mergedChildOverride = mergeOverrideObjects(
                    baseChildOverride,
                    (ObjectNode) childPatch,
                    dependencyResolution);
            mergedDependencies.put(childName, new DependencyEntry(declarationKey, mergedChildOverride));
        }

        ObjectNode normalized = JsonNodeFactory.instance.objectNode();
        for (var entry : mergedDependencies.entrySet()) {
            normalized.set(entry.getValue().declarationKey(), entry.getValue().declaration());
        }
        target.set("dependencies", normalized);
    }

    private static void validateOverrideFragmentDependencyScopes(
            ObjectNode fragment,
            AlgorithmDefinitionReader.DependencyResolution dependencyResolution) {
        JsonNode dependencies = fragment.get("dependencies");
        if (dependencies == null || dependencies.isNull() || !dependencies.isObject()) {
            return;
        }
        for (var fields = dependencies.fields(); fields.hasNext(); ) {
            var field = fields.next();
            JsonNode childOverride = field.getValue();
            if (childOverride == null || !childOverride.isObject()) {
                continue;
            }
            String childName = requireDependencyOverrideKey(field.getKey(), dependencyResolution);
            if (childOverride.has("scope")) {
                throw new IllegalArgumentException(
                        "Override for dependency " + childName + " must not declare scope");
            }
            validateOverrideFragmentDependencyScopes((ObjectNode) childOverride, dependencyResolution);
        }
    }

    private static LinkedHashMap<String, DependencyEntry> normalizeDeclaredDependencies(JsonNode baseDependencies) {
        LinkedHashMap<String, DependencyEntry> normalized = new LinkedHashMap<>();
        if (baseDependencies == null || baseDependencies.isNull()) {
            return normalized;
        }

        if (baseDependencies.isArray()) {
            for (JsonNode dependency : baseDependencies) {
                checkArgument(dependency.isTextual(), "Dependency entries must be strings but found %s", dependency);
                String childName = AlgorithmDefinitionReader.extractAlgorithmName(dependency.asText());
                normalized.put(childName, new DependencyEntry(
                        dependency.asText(),
                        JsonNodeFactory.instance.objectNode()));
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
            normalized.put(childName, new DependencyEntry(
                    field.getKey(),
                    ((ObjectNode) childOverride).deepCopy()));
        }
        return normalized;
    }

    private static LinkedHashMap<String, DependencyEntry> normalizeOverrideFragmentDependencies(
            JsonNode baseDependencies,
            AlgorithmDefinitionReader.DependencyResolution dependencyResolution) {
        LinkedHashMap<String, DependencyEntry> normalized = new LinkedHashMap<>();
        if (baseDependencies == null || baseDependencies.isNull()) {
            return normalized;
        }

        checkArgument(baseDependencies.isObject(), "dependencies override fragment must be a JSON object");
        for (var fields = baseDependencies.fields(); fields.hasNext(); ) {
            var field = fields.next();
            String childName = requireDependencyOverrideKey(field.getKey(), dependencyResolution);
            JsonNode childOverride = field.getValue();
            checkArgument(
                    childOverride != null && childOverride.isObject(),
                    "Embedded dependency override for %s must be a JSON object",
                    childName
            );
            String declarationKey = dependencyResolution == AlgorithmDefinitionReader.DependencyResolution.OFFLINE
                    ? childName
                    : field.getKey();
            normalized.put(childName, new DependencyEntry(
                    declarationKey,
                    ((ObjectNode) childOverride).deepCopy()));
        }
        return normalized;
    }

    private static String requireDependencyOverrideKey(
            String key,
            AlgorithmDefinitionReader.DependencyResolution dependencyResolution) {
        String childName = AlgorithmDefinitionReader.extractAlgorithmName(key);
        if (dependencyResolution == AlgorithmDefinitionReader.DependencyResolution.ONLINE) {
            checkArgument(
                    childName.equals(key),
                    "Dependency override key %s must be an unversioned dependency name",
                    key);
        }
        return childName;
    }

    private record DependencyEntry(String declarationKey, ObjectNode declaration) {
    }
}
