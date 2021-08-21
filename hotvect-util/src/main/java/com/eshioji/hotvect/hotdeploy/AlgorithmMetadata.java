package com.eshioji.hotvect.hotdeploy;

import com.fasterxml.jackson.annotation.JsonProperty;

public class AlgorithmMetadata {
    private final String name;
    private final String instanceId;
    private final String exampleDecoderFactoryName;
    private final String exampleEncoderFactoryName;
    private final String scorerFactoryName;

    public AlgorithmMetadata(@JsonProperty("name") String name,
                             @JsonProperty("instanceId")String instanceId,
                             @JsonProperty("exampleDecoderFactoryName")String exampleDecoderFactoryName,
                             @JsonProperty("exampleEncoderFactoryName")String exampleEncoderFactoryName,
                             @JsonProperty("scorerFactoryName")String scorerFactoryName) {
        this.name = name;
        this.instanceId = instanceId;
        this.exampleDecoderFactoryName = exampleDecoderFactoryName;
        this.exampleEncoderFactoryName = exampleEncoderFactoryName;
        this.scorerFactoryName = scorerFactoryName;
    }

    public String getName() {
        return name;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public String getExampleDecoderFactoryName() {
        return exampleDecoderFactoryName;
    }

    public String getExampleEncoderFactoryName() {
        return exampleEncoderFactoryName;
    }

    public String getScorerFactoryName() {
        return scorerFactoryName;
    }
}
