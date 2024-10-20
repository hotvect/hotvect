package com.hotvect.api.algodefinition;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableMap;

import java.util.*;

public class AlgorithmDefinition {
    private final JsonNode rawAlgorithmDefinition;
    private final AlgorithmId algorithmId;

    private final Map<String, Optional<JsonNode>> dependencyAlgorithmOverrides;
    private final Map<String, AlgorithmDefinition> dependencies;

    private final String decoderFactoryName;

    private final String transformerFactoryName;
    private final String vectorizerFactoryName;
    private final String rewardFunctionFactoryName;
    private final String encoderFactoryName;
    private final String algorithmFactoryName;


    private final Optional<JsonNode> vectorizerParameter;
    private final Optional<JsonNode> transformerParameter;
    private final Optional<JsonNode> trainDecoderParameter;
    private final Optional<JsonNode> testDecoderParameter;

    private final Optional<JsonNode> algorithmParameter;

    public AlgorithmDefinition(
            JsonNode rawAlgorithmDefinition,
            AlgorithmId algorithmId,
            Map<String, Optional<JsonNode>> dependencyAlgorithmOverrides,
            Map<String, AlgorithmDefinition> dependencies,
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
        this.rawAlgorithmDefinition = rawAlgorithmDefinition;
        this.algorithmId = algorithmId;
        this.decoderFactoryName = decoderFactoryName;
        this.transformerFactoryName = transformerFactoryName;
        this.vectorizerFactoryName = vectorizerFactoryName;
        this.encoderFactoryName = encoderFactoryName;
        this.algorithmFactoryName = algorithmFactoryName;
        this.transformerParameter = transformerParameter;
        this.vectorizerParameter = vectorizerParameter;
        this.trainDecoderParameter = trainDecoderParameter;
        this.testDecoderParameter = testDecoderParameter;
        this.rewardFunctionFactoryName = rewardFunctionFactoryName;
        this.dependencyAlgorithmOverrides = dependencyAlgorithmOverrides;
        this.dependencies = dependencies;
        this.algorithmParameter = algorithmParameter;
    }

    public JsonNode getRawAlgorithmDefinition() {
        return rawAlgorithmDefinition;
    }

    public AlgorithmId getAlgorithmId() {
        return algorithmId;
    }

    private static final Map<String, AlgorithmDefinition> warnMap;
    static {
        Map<String, AlgorithmDefinition> warn = new HashMap<>();
        warn.put("dependencies have not been resolved yet", null);
        // ImmutableMap does not allow null values
        warnMap = Collections.unmodifiableMap(warn);
    }

    public Map<String, AlgorithmDefinition> getDependencies() {
        if (dependencies == null) {
            return warnMap;
        } else {
            return dependencies;
        }
    }

    public Map<String, Optional<JsonNode>> getDependencyAlgorithmOverrides(){
        return this.dependencyAlgorithmOverrides;
    }

    public String getDecoderFactoryName() {
        return decoderFactoryName;
    }

    public String getVectorizerFactoryName() {
        return vectorizerFactoryName;
    }

    public String getEncoderFactoryName() {
        return encoderFactoryName;
    }

    public String getAlgorithmFactoryName() {
        return algorithmFactoryName;
    }

    public Optional<JsonNode> getVectorizerParameter() {
        return vectorizerParameter;
    }

    public Optional<JsonNode> getTrainDecoderParameter() {
        return trainDecoderParameter;
    }

    public Optional<JsonNode> getTestDecoderParameter() {
        return testDecoderParameter;
    }

    public String getRewardFunctionFactoryName() {
        return rewardFunctionFactoryName;
    }

    public Optional<JsonNode> getAlgorithmParameter() {
        return algorithmParameter;
    }

    public String getTransformerFactoryName() {
        return transformerFactoryName;
    }

    public Optional<JsonNode> getTransformerParameter() {
        return transformerParameter;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AlgorithmDefinition that = (AlgorithmDefinition) o;
        return Objects.equals(rawAlgorithmDefinition, that.rawAlgorithmDefinition) && Objects.equals(algorithmId, that.algorithmId) && Objects.equals(dependencyAlgorithmOverrides, that.dependencyAlgorithmOverrides) && Objects.equals(dependencies, that.dependencies) && Objects.equals(decoderFactoryName, that.decoderFactoryName) && Objects.equals(transformerFactoryName, that.transformerFactoryName) && Objects.equals(vectorizerFactoryName, that.vectorizerFactoryName) && Objects.equals(rewardFunctionFactoryName, that.rewardFunctionFactoryName) && Objects.equals(encoderFactoryName, that.encoderFactoryName) && Objects.equals(algorithmFactoryName, that.algorithmFactoryName) && Objects.equals(vectorizerParameter, that.vectorizerParameter) && Objects.equals(transformerParameter, that.transformerParameter) && Objects.equals(trainDecoderParameter, that.trainDecoderParameter) && Objects.equals(testDecoderParameter, that.testDecoderParameter) && Objects.equals(algorithmParameter, that.algorithmParameter);
    }

    @Override
    public int hashCode() {
        return Objects.hash(rawAlgorithmDefinition, algorithmId, dependencyAlgorithmOverrides, dependencies, decoderFactoryName, transformerFactoryName, vectorizerFactoryName, rewardFunctionFactoryName, encoderFactoryName, algorithmFactoryName, vectorizerParameter, transformerParameter, trainDecoderParameter, testDecoderParameter, algorithmParameter);
    }

    @Override
    public String toString() {
        return "AlgorithmDefinition{" +
                "rawAlgorithmDefinition=" + rawAlgorithmDefinition +
                ", algorithmId=" + algorithmId +
                ", dependencyAlgorithmOverrides=" + dependencyAlgorithmOverrides +
                ", dependencies=" + dependencies +
                ", decoderFactoryName='" + decoderFactoryName + '\'' +
                ", transformerFactoryName='" + transformerFactoryName + '\'' +
                ", vectorizerFactoryName='" + vectorizerFactoryName + '\'' +
                ", rewardFunctionFactoryName='" + rewardFunctionFactoryName + '\'' +
                ", encoderFactoryName='" + encoderFactoryName + '\'' +
                ", algorithmFactoryName='" + algorithmFactoryName + '\'' +
                ", vectorizerParameter=" + vectorizerParameter +
                ", transformerParameter=" + transformerParameter +
                ", trainDecoderParameter=" + trainDecoderParameter +
                ", testDecoderParameter=" + testDecoderParameter +
                ", algorithmParameter=" + algorithmParameter +
                '}';
    }

    public AlgorithmDefinition replace(Map<String, AlgorithmDefinition> dependencies){
        return new AlgorithmDefinition(
                this.getRawAlgorithmDefinition(),
                this.getAlgorithmId(),
                this.getDependencyAlgorithmOverrides(),
                ImmutableMap.copyOf(dependencies),
                this.getDecoderFactoryName(),
                this.getTransformerFactoryName(),
                this.getVectorizerFactoryName(),
                this.getRewardFunctionFactoryName(),
                this.getEncoderFactoryName(),
                this.getAlgorithmFactoryName(),
                this.getTransformerParameter(),
                this.getVectorizerParameter(),
                this.getTrainDecoderParameter(),
                this.getTestDecoderParameter(),
                this.getAlgorithmParameter()
        );
    }
}
