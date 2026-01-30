package com.hotvect.api.algodefinition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.google.common.collect.ImmutableMap;

import java.util.*;

public record AlgorithmDefinition(
        JsonNode rawAlgorithmDefinition,
        AlgorithmId algorithmId,
        Map<String, Optional<JsonNode>> dependencyAlgorithmOverrides,
        Map<String, AlgorithmDefinition> dependencies,
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

    private static final Map<String, AlgorithmDefinition> warnMap;

    static {
        Map<String, AlgorithmDefinition> warn = new HashMap<>();
        warn.put("dependencies have not been resolved yet", null);
        // ImmutableMap does not allow null values
        warnMap = Collections.unmodifiableMap(warn);
    }

    @Override
    public Map<String, AlgorithmDefinition> dependencies() {
        return Objects.requireNonNullElse(dependencies, warnMap);
    }

    public AlgorithmDefinition replace(Map<String, AlgorithmDefinition> dependencies) {
        return new AlgorithmDefinition(
                this.rawAlgorithmDefinition(),
                this.algorithmId(),
                this.dependencyAlgorithmOverrides(),
                ImmutableMap.copyOf(dependencies),
                this.generateStateFactoryName(),
                this.decoderFactoryName(),
                this.transformerFactoryName(),
                this.vectorizerFactoryName(),
                this.rewardFunctionFactoryName(),
                this.encoderFactoryName(),
                this.algorithmFactoryName(),
                this.transformerParameter(),
                this.vectorizerParameter(),
                this.trainDecoderParameter(),
                this.testDecoderParameter(),
                this.algorithmParameter()
        );
    }

    /**
     * Creates a synthetic AlgorithmDefinition for external dependencies that are not hotvect algorithms.
     * This allows external objects (like feature stores) to be passed through the dependency system
     * without requiring a full algorithm definition.
     *
     * @param algorithmName the name of the external algorithm
     * @return a synthetic AlgorithmDefinition suitable for external dependencies
     */
    public static AlgorithmDefinition externalAlgorithm(String algorithmName) {
        return new AlgorithmDefinition(
                JsonNodeFactory.instance.objectNode(),
                new AlgorithmId(algorithmName, "NA"),
                ImmutableMap.of(),
                ImmutableMap.of(),
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
                Optional.empty()
        );
    }
}
