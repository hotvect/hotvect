package com.hotvect.api.algodefinition;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;

public class AlgorithmDefinition {
    private final String algorithmName;
    private final String decoderFactoryName;
    private final String vectorizerFactoryName;
    private final String rewardFunctionFactoryName;
    private final String encoderFactoryName;
    private final String algorithmFactoryName;

    private final Optional<JsonNode> vectorizerParameter;
    private final Optional<JsonNode> trainDecoderParameter;
    private final Optional<JsonNode> predictDecoderParameter;

    public AlgorithmDefinition(String algorithmName, String decoderFactoryName, String vectorizerFactoryName, String rewardFunctionFactoryName, String encoderFactoryName, String algorithmFactoryName, Optional<JsonNode> vectorizerParameter, Optional<JsonNode> trainDecoderParameter, Optional<JsonNode> predictDecoderParameter) {
        this.algorithmName = algorithmName;
        this.decoderFactoryName = decoderFactoryName;
        this.vectorizerFactoryName = vectorizerFactoryName;
        this.encoderFactoryName = encoderFactoryName;
        this.algorithmFactoryName = algorithmFactoryName;
        this.vectorizerParameter = vectorizerParameter;
        this.trainDecoderParameter = trainDecoderParameter;
        this.predictDecoderParameter = predictDecoderParameter;
        this.rewardFunctionFactoryName = rewardFunctionFactoryName;
    }

    public String getAlgorithmName() {
        return algorithmName;
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

    public Optional<JsonNode> getPredictDecoderParameter() {
        return predictDecoderParameter;
    }

    public String getRewardFunctionFactoryName() {
        return rewardFunctionFactoryName;
    }

    @Override
    public String toString() {
        return "AlgorithmDefinition{" +
                "algorithmName='" + algorithmName + '\'' +
                ", decoderFactoryName='" + decoderFactoryName + '\'' +
                ", vectorizerFactoryName='" + vectorizerFactoryName + '\'' +
                ", encoderFactoryName='" + encoderFactoryName + '\'' +
                ", algorithmFactoryName='" + algorithmFactoryName + '\'' +
                ", vectorizerParameter=" + vectorizerParameter +
                ", trainDecoderParameter=" + trainDecoderParameter +
                ", predictDecoderParameter=" + predictDecoderParameter +
                '}';
    }

    private static JsonNode ensureExtract(JsonNode root, String fieldName){
        JsonNode field = root.get(fieldName);
        checkArgument(field != null,"You must specify:%s. Full input:%s", fieldName, root);
        return field;
    }

    public static AlgorithmDefinition parse(String json) throws JsonProcessingException {
        ObjectMapper om = new ObjectMapper();
        JsonNode parsed = om.readTree(json);
        return new AlgorithmDefinition(
                ensureExtract(parsed, "algorithm_name").asText(),
                ensureExtract(parsed, "decoder_factory_classname").asText(),
                ensureExtract(parsed, "vectorizer_factory_classname").asText(),
                ensureExtract(parsed, "reward_function_factory_classname").asText(),
                ensureExtract(parsed, "encoder_factory_classname").asText(),
                ensureExtract(parsed, "algorithm_factory_classname").asText(),
                Optional.ofNullable(parsed.get("vectorizer_parameters")),
                Optional.ofNullable(parsed.get("train_decoder_parameters")),
                Optional.ofNullable(parsed.get("predict_decoder_parameters"))
        );
    }

}
