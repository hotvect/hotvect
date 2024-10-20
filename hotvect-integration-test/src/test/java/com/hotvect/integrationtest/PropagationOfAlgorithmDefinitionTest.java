package com.hotvect.integrationtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.CharStreams;
import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.onlineutils.hotdeploy.AlgorithmInstanceFactory;
import com.hotvect.utils.AlgorithmDefinitionReader;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Disabled
public class PropagationOfAlgorithmDefinitionTest {
    @Test
    public void when_algorithm_definition_is_not_passed_then_use_stored_algorithm_definition() throws Exception {
        // Check the outer algorithm
        {
            AlgorithmDefinition outerAlgorithmExpected = new AlgorithmDefinitionReader().parse(CharStreams.toString(new InputStreamReader(ClassLoader.getSystemResourceAsStream("com-hotvect-test-algorithm-definition.json"), Charsets.UTF_8)));
            AlgorithmDefinition innerAlgorithmExpected = new AlgorithmDefinitionReader().parse(CharStreams.toString(new InputStreamReader(ClassLoader.getSystemResourceAsStream("com-hotvect-test-iris-model-algorithm-definition.json"), Charsets.UTF_8)));

            outerAlgorithmExpected = outerAlgorithmExpected.replace(ImmutableMap.of("com-hotvect-test-iris-model", innerAlgorithmExpected));

            AlgorithmInstanceFactory algoAlgorithmInstanceFactory = new AlgorithmInstanceFactory(
                    Thread.currentThread().getContextClassLoader(),
                    false

            );

            var algorithmInstance = algoAlgorithmInstanceFactory.load(
                    "com-hotvect-test",
                    new File(ClassLoader.getSystemResource("mock.parameters.1.0.0.zip").getFile())
            );

            assertEquals(outerAlgorithmExpected, algorithmInstance.getAlgorithmDefinition());
        }

        // The inner algorithm (dependency)
        {
            var innerAlgorithmExpected = new AlgorithmDefinitionReader().parse(CharStreams.toString(new InputStreamReader(ClassLoader.getSystemResourceAsStream("com-hotvect-test-iris-model-algorithm-definition.json"), Charsets.UTF_8)));

            AlgorithmInstanceFactory innerAlgorithmInstanceFactory = new AlgorithmInstanceFactory(
                    Thread.currentThread().getContextClassLoader(),
                    false
            );

            var algorithmInstance = innerAlgorithmInstanceFactory.load(
                    "com-hotvect-test-iris-model",
                    new File(ClassLoader.getSystemResource("mock.parameters.1.0.0.zip").getFile())
            );
            assertEquals(innerAlgorithmExpected, algorithmInstance.getAlgorithmDefinition());
        }

    }


    @Test
    public void when_algorithm_definition_is_passed_then_overwrite_stored_algorithm_definition() throws Exception {
        AlgorithmDefinition outerAlgorithmExpected = new AlgorithmDefinitionReader().parse(CharStreams.toString(new InputStreamReader(ClassLoader.getSystemResourceAsStream("com-hotvect-test-algorithm-definition.json"), Charsets.UTF_8)));
        AlgorithmDefinition innerAlgorithmExpected = new AlgorithmDefinitionReader().parse(CharStreams.toString(new InputStreamReader(ClassLoader.getSystemResourceAsStream("com-hotvect-test-iris-model-algorithm-definition.json"), Charsets.UTF_8)));
        outerAlgorithmExpected = outerAlgorithmExpected.replace(ImmutableMap.of("com-hotvect-test-iris-model", innerAlgorithmExpected));

        ObjectNode testDecoderProp = (ObjectNode) outerAlgorithmExpected.getVectorizerParameter().orElseThrow();
        testDecoderProp.put("vectorizer_test_parameter", "overwritten");

        JsonNode innerAlgorithmExpectedJsonNode = new ObjectMapper().readTree(new InputStreamReader(ClassLoader.getSystemResourceAsStream("com-hotvect-test-iris-model-algorithm-definition.json"), Charsets.UTF_8));
        ObjectNode testVectorizerProp = (ObjectNode) innerAlgorithmExpectedJsonNode.get("vectorizer_parameters");
        testVectorizerProp.put("vectorizer_test_parameter", "overwritten");

        outerAlgorithmExpected.getDependencyAlgorithmOverrides().put("com-hotvect-test-iris-model", Optional.of(innerAlgorithmExpectedJsonNode));
        ((ObjectNode)(outerAlgorithmExpected.getDependencies().get("com-hotvect-test-iris-model").getRawAlgorithmDefinition().get("vectorizer_parameters"))).put("vectorizer_test_parameter", "overwritten");

        AlgorithmInstanceFactory algoAlgorithmInstanceFactory = new AlgorithmInstanceFactory(
                Thread.currentThread().getContextClassLoader(),
                false
        );

        AlgorithmInstance<Ranker<String, Map<String, String>>> algorithmInstance = algoAlgorithmInstanceFactory.load(outerAlgorithmExpected, new File(ClassLoader.getSystemResource("mock.parameters.1.0.0.zip").getFile()));
        assertEquals(outerAlgorithmExpected, algorithmInstance.getAlgorithmDefinition());

        algorithmInstance.getAlgorithm().rank(new RankingRequest<>("example1", "shared", ImmutableList.of(ImmutableMap.of(
                "iris.model.parameter.id", "test_iris_model_parameter_id_123",
                "iris.model.vectorizer_test_parameter", "overwritten"
        ))));

    }

}
