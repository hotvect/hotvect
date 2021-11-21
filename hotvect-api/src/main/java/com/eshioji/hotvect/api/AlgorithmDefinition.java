package com.eshioji.hotvect.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Optional;

public class AlgorithmDefinition {
    private final String algorithmName;
    private final String decoderFactoryName;
    private final String vectorizerFactoryName;
    private final String encoderFactoryName;
    private final String scorerFactoryName;

    private final Optional<JsonNode> vectorizerParameter;
    private final Optional<JsonNode> trainDecoderParameter;
    private final Optional<JsonNode> predictDecoderParameter;

    public AlgorithmDefinition(String algorithmName, String decoderFactoryName, String vectorizerFactoryName, String encoderFactoryName, String scorerFactoryName, Optional<JsonNode> vectorizerParameter, Optional<JsonNode> trainDecoderParameter, Optional<JsonNode> predictDecoderParameter) {
        this.algorithmName = algorithmName;
        this.decoderFactoryName = decoderFactoryName;
        this.vectorizerFactoryName = vectorizerFactoryName;
        this.encoderFactoryName = encoderFactoryName;
        this.scorerFactoryName = scorerFactoryName;
        this.vectorizerParameter = vectorizerParameter;
        this.trainDecoderParameter = trainDecoderParameter;
        this.predictDecoderParameter = predictDecoderParameter;
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

    public String getScorerFactoryName() {
        return scorerFactoryName;
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

    @Override
    public String toString() {
        return "AlgorithmDefinition{" +
                "algorithmName='" + algorithmName + '\'' +
                ", decoderFactoryName='" + decoderFactoryName + '\'' +
                ", vectorizerFactoryName='" + vectorizerFactoryName + '\'' +
                ", encoderFactoryName='" + encoderFactoryName + '\'' +
                ", scorerFactoryName='" + scorerFactoryName + '\'' +
                ", vectorizerParameter=" + vectorizerParameter +
                ", trainDecoderParameter=" + trainDecoderParameter +
                ", predictDecoderParameter=" + predictDecoderParameter +
                '}';
    }

    public static AlgorithmDefinition parse(String json) throws JsonProcessingException {
        ObjectMapper om = new ObjectMapper();
        JsonNode parsed = om.readTree(json);
        return new AlgorithmDefinition(
                parsed.get("algorithm_name").asText(),
                parsed.get("decoder_factory_classname").asText(),
                parsed.get("vectorizer_factory_classname").asText(),
                parsed.get("encoder_factory_classname").asText(),
                parsed.get("scorer_factory_classname").asText(),
                Optional.ofNullable(parsed.get("vectorizer_parameters")),
                Optional.ofNullable(parsed.get("train_decoder_parameters")),
                Optional.ofNullable(parsed.get("predict_decoder_parameters"))
        );
    }

}
