package com.eshioji.hotvect.api;

public class AlgorithmDefinition {
    private String algorithmName;
    private String exampleDecoderFactoryClassName;
    private String exampleEncoderFactoryClassName;
    private String exampleScorerFactoryClassName;

    public String getAlgorithmName() {
        return algorithmName;
    }

    public void setAlgorithmName(String algorithmName) {
        this.algorithmName = algorithmName;
    }

    public String getExampleDecoderFactoryClassName() {
        return exampleDecoderFactoryClassName;
    }

    public void setExampleDecoderFactoryClassName(String exampleDecoderFactoryClassName) {
        this.exampleDecoderFactoryClassName = exampleDecoderFactoryClassName;
    }

    public String getExampleEncoderFactoryClassName() {
        return exampleEncoderFactoryClassName;
    }

    public void setExampleEncoderFactoryClassName(String exampleEncoderFactoryClassName) {
        this.exampleEncoderFactoryClassName = exampleEncoderFactoryClassName;
    }

    public String getExampleScorerFactoryClassName() {
        return exampleScorerFactoryClassName;
    }

    public void setExampleScorerFactoryClassName(String exampleScorerFactoryClassName) {
        this.exampleScorerFactoryClassName = exampleScorerFactoryClassName;
    }
}
