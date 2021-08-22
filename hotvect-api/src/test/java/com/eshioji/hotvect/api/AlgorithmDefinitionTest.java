package com.eshioji.hotvect.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AlgorithmDefinitionTest {

    @Test
    void example() throws Exception {
        var example = new AlgorithmDefinition();
        example.setAlgorithmName("exampleName");
        example.setExampleDecoderFactoryClassName("com.exampleDecoderFactory");
        example.setExampleEncoderFactoryClassName("com.exampleEncoderFactory");
        example.setExampleScorerFactoryClassName("com.exampleScorerFactory");
        var objectMapper = new ObjectMapper();
        objectMapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        var s = objectMapper.writeValueAsString(example);
        System.out.println(s);
        System.out.println(objectMapper.readValue(s, AlgorithmDefinition.class));
    }
}