package com.eshioji.hotvect.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class AlgorithmDefinitionTest {

    @Test
    void example() throws Exception {
        AlgorithmDefinition example = new AlgorithmDefinition(
                "exampleName",
                "com.exampleDecoderFactory",
                "com.exampleVectorizerFactory",
                "com.exampleEncoderFactory",
                "com.exampleScorerFactory",
                Optional.empty(),
                Optional.empty(),
                Optional.empty()
        );
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        String s = objectMapper.writeValueAsString(example);
        System.out.println(s);
        System.out.println(objectMapper.readValue(s, AlgorithmDefinition.class));
    }
}