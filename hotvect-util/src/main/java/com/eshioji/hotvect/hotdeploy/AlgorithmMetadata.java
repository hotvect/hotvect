package com.eshioji.hotvect.hotdeploy;

public class AlgorithmMetadata {
    private final String name;
    private final String instanceId;
    private final String exampleDecoderFactoryName;
    private final String exampleEncoderFactoryName;
    private final String scorerFactoryName;

    public AlgorithmMetadata(String name, String instanceId, String exampleDecoderFactoryName, String exampleEncoderFactoryName, String scorerFactoryName) {
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
