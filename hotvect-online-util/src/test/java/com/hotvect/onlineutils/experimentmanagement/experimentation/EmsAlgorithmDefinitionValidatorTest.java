package com.hotvect.onlineutils.experimentmanagement.experimentation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algodefinition.AlgorithmRuntimeId;
import com.hotvect.api.algorithms.Algorithm;
import com.google.common.reflect.TypeToken;
import com.hotvect.utils.AlgorithmDefinitionReader;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EmsAlgorithmDefinitionValidatorTest {
    @Test
    void acceptsCommittedDefinitionGraph() throws Exception {
        AlgorithmDefinition child = definition("child", null);
        AlgorithmRuntimeId root = root(parentDefinition(), child);

        assertDoesNotThrow(() -> EmsAlgorithmDefinitionValidator.validateGraph(root));
    }

    @Test
    void rejectsHyperparameterVersionInDependencyGraph() throws Exception {
        AlgorithmDefinition child = definition("child", "candidate-a");
        AlgorithmRuntimeId root = root(parentDefinition(), child);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> EmsAlgorithmDefinitionValidator.validateGraph(root));

        assertEquals(
                "Live EMS algorithm child@1 declares offline-only hyperparameter_version candidate-a; "
                        + "commit the effective definition and publish a new algorithm version before online use",
                error.getMessage());
    }

    private static AlgorithmDefinition definition(String name, String hyperparameterVersion) throws Exception {
        String hyperparameterField = hyperparameterVersion == null
                ? ""
                : "\"hyperparameter_version\": \"" + hyperparameterVersion + "\",";
        return new AlgorithmDefinitionReader().parse("""
                {
                  "algorithm_name": "%s",
                  "algorithm_version": "1",
                  %s
                  "algorithm_factory_classname": "example.Factory"
                }
                """.formatted(name, hyperparameterField));
    }

    private static AlgorithmDefinition parentDefinition() throws Exception {
        return new AlgorithmDefinitionReader().parse("""
                {
                  "algorithm_name": "root",
                  "algorithm_version": "1",
                  "algorithm_factory_classname": "example.Factory",
                  "dependencies": ["child"]
                }
                """);
    }

    private static AlgorithmRuntimeId root(AlgorithmDefinition root, AlgorithmDefinition child) {
        AlgorithmInstance<NoopAlgorithm> childInstance = new AlgorithmInstance<>(
                child,
                null,
                new NoopAlgorithm(),
                TypeToken.of(NoopAlgorithm.class));
        AlgorithmInstance<NoopAlgorithm> rootInstance = new AlgorithmInstance<>(
                root,
                null,
                new NoopAlgorithm(),
                TypeToken.of(NoopAlgorithm.class));
        return AlgorithmRuntimeId.from(
                rootInstance,
                Map.of("child", AlgorithmRuntimeId.from(childInstance, Map.of())));
    }

    private static final class NoopAlgorithm implements Algorithm {
    }
}
