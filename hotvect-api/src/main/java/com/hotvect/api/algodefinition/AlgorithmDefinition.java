package com.hotvect.api.algodefinition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * One effective, declarative algorithm definition.
 *
 * <p>A definition describes the artifact and its requested dependency policies. It never contains
 * constructed children. Resolved child values are passed through {@link AlgorithmDependencies}
 * during construction; topology, classloaders, and lifecycle belong to the runtime graph.</p>
 */
public record AlgorithmDefinition(
        JsonNode rawAlgorithmDefinition,
        AlgorithmId algorithmId,
        Map<String, AlgorithmDependencyDeclaration> dependencyDeclarations,
        String generateStateFactoryName,
        String decoderFactoryName,
        String transformerFactoryName,
        String vectorizerFactoryName,
        String rewardFunctionFactoryName,
        String encoderFactoryName,
        String algorithmFactoryName,
        Optional<JsonNode> transformerParameter,
        Optional<JsonNode> vectorizerParameter,
        Optional<JsonNode> trainDecoderParameter,
        Optional<JsonNode> testDecoderParameter,
        Optional<JsonNode> algorithmParameter
) {

    /** Validates and freezes the declarative part of one algorithm definition. */
    public AlgorithmDefinition {
        rawAlgorithmDefinition = Objects.requireNonNull(
                rawAlgorithmDefinition,
                "rawAlgorithmDefinition must not be null").deepCopy();
        if (!rawAlgorithmDefinition.isObject()) {
            throw new IllegalArgumentException("rawAlgorithmDefinition must be a JSON object");
        }
        algorithmId = Objects.requireNonNull(algorithmId, "algorithmId must not be null");
        dependencyDeclarations = immutableDeclarations(dependencyDeclarations);
        transformerParameter = copyOptional(transformerParameter, "transformerParameter");
        vectorizerParameter = copyOptional(vectorizerParameter, "vectorizerParameter");
        trainDecoderParameter = copyOptional(trainDecoderParameter, "trainDecoderParameter");
        testDecoderParameter = copyOptional(testDecoderParameter, "testDecoderParameter");
        algorithmParameter = copyOptional(algorithmParameter, "algorithmParameter");
    }

    /** Returns a defensive snapshot of the effective JSON definition. */
    @Override
    public JsonNode rawAlgorithmDefinition() {
        return rawAlgorithmDefinition.deepCopy();
    }

    @Override
    public Optional<JsonNode> transformerParameter() {
        return copyOptional(transformerParameter, "transformerParameter");
    }

    @Override
    public Optional<JsonNode> vectorizerParameter() {
        return copyOptional(vectorizerParameter, "vectorizerParameter");
    }

    @Override
    public Optional<JsonNode> trainDecoderParameter() {
        return copyOptional(trainDecoderParameter, "trainDecoderParameter");
    }

    @Override
    public Optional<JsonNode> testDecoderParameter() {
        return copyOptional(testDecoderParameter, "testDecoderParameter");
    }

    @Override
    public Optional<JsonNode> algorithmParameter() {
        return copyOptional(algorithmParameter, "algorithmParameter");
    }

    /** Returns the normalized optional offline hyperparameter version. */
    public String hyperparameterVersion() {
        JsonNode value = rawAlgorithmDefinition.get("hyperparameter_version");
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException("hyperparameter_version must be a string");
        }
        String version = value.asText();
        return version.isBlank() ? null : version;
    }

    /** Returns this effective definition's code and hyperparameter identity. */
    public HyperparameterizedAlgorithmId hyperparameterizedAlgorithmId() {
        return new HyperparameterizedAlgorithmId(algorithmId, hyperparameterVersion());
    }

    /**
     * Creates a declarative placeholder for an application-owned dependency that is not a Hotvect
     * artifact.
     */
    public static AlgorithmDefinition externalAlgorithm(String algorithmName) {
        return new AlgorithmDefinition(
                JsonNodeFactory.instance.objectNode(),
                new AlgorithmId(algorithmName, "NA"),
                Map.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static Map<String, AlgorithmDependencyDeclaration> immutableDeclarations(
            Map<String, AlgorithmDependencyDeclaration> declarations) {
        Objects.requireNonNull(declarations, "dependencyDeclarations must not be null");
        TreeMap<String, AlgorithmDependencyDeclaration> sorted = new TreeMap<>();
        declarations.forEach((name, declaration) -> {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("dependency declaration name must not be blank");
            }
            AlgorithmDependencyDeclaration resolved = Objects.requireNonNull(
                    declaration,
                    "dependency declaration must not be null");
            if (!name.equals(resolved.name())) {
                throw new IllegalArgumentException(
                        "dependency declaration map key " + name
                                + " does not match declaration name " + resolved.name());
            }
            if (sorted.put(name, resolved) != null) {
                throw new IllegalArgumentException("duplicate dependency declaration: " + name);
            }
        });
        return Collections.unmodifiableMap(sorted);
    }

    private static Optional<JsonNode> copyOptional(Optional<JsonNode> value, String fieldName) {
        return Objects.requireNonNull(value, fieldName + " must not be null").map(JsonNode::deepCopy);
    }
}
