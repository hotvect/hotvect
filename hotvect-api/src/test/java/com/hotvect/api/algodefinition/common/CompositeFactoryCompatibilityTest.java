package com.hotvect.api.algodefinition.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.AlgorithmDependencies;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algorithms.Algorithm;
import com.hotvect.api.data.Namespace;
import com.hotvect.api.execution.ExecutionContext;
import com.hotvect.api.execution.InputSemantic;
import com.hotvect.api.execution.WorkloadMode;
import com.hotvect.api.transformation.CompositeTransformerFactory;
import java.io.InputStream;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;

@SuppressWarnings("removal")
class CompositeFactoryCompatibilityTest {
    private static final ExecutionContext EXECUTION_CONTEXT =
            ExecutionContext.of(WorkloadMode.BATCH, InputSemantic.OFFLINE);

    @Test
    void publishedCompositeAlgorithmFactoryReceivesSingletonDependencies() {
        TestAlgorithm child = new TestAlgorithm();
        PublishedCompositeAlgorithmFactory factory = new PublishedCompositeAlgorithmFactory();

        TestAlgorithm result = factory.create(
                EXECUTION_CONTEXT,
                Optional.empty(),
                Optional.empty(),
                Map.of(),
                new AlgorithmDependencies(Map.of("child", instance("child", child))));

        assertSame(child, result);
    }

    @Test
    void publishedCompositeTransformerAndVectorizerFactoriesReceiveSingletonDependencies() {
        TestAlgorithm child = new TestAlgorithm();
        AlgorithmDependencies dependencies =
                new AlgorithmDependencies(Map.of("child", instance("child", child)));

        assertSame(
                child,
                new PublishedCompositeTransformerFactory().create(
                        EXECUTION_CONTEXT,
                        Optional.empty(),
                        Map.of(),
                        dependencies));
        assertSame(
                child,
                new PublishedCompositeVectorizerFactory().create(
                        EXECUTION_CONTEXT,
                        Optional.empty(),
                        Map.of(),
                        dependencies).child());
    }

    private static AlgorithmInstance<TestAlgorithm> instance(
            String name,
            TestAlgorithm algorithm) {
        return AlgorithmInstance.externalAlgorithm(name, TestAlgorithm.class, algorithm);
    }

    private static final class TestAlgorithm implements Algorithm {
    }

    private record TestVectorizer(TestAlgorithm child) implements Vectorizer {
    }

    private static final class PublishedCompositeAlgorithmFactory
            implements CompositeAlgorithmFactory<TestAlgorithm> {
        @Override
        public TestAlgorithm apply(
                Optional<JsonNode> hyperparameters,
                Map<String, InputStream> parameters,
                Map<String, AlgorithmInstance<?>> algorithmDependencies) {
            return (TestAlgorithm) algorithmDependencies.get("child").algorithm();
        }
    }

    private static final class PublishedCompositeTransformerFactory
            implements CompositeTransformerFactory<TestAlgorithm> {
        @Override
        public TestAlgorithm apply(
                Optional<JsonNode> hyperparameters,
                Map<String, InputStream> parameters,
                Map<String, AlgorithmInstance<?>> algorithmDependencies) {
            return (TestAlgorithm) algorithmDependencies.get("child").algorithm();
        }

        @Override
        public SortedSet<? extends Namespace> getUsedFeatures(Optional<JsonNode> transformerHyperparameters) {
            return Collections.emptySortedSet();
        }
    }

    private static final class PublishedCompositeVectorizerFactory
            implements CompositeVectorizerFactory<TestVectorizer> {
        @Override
        public TestVectorizer apply(
                Optional<JsonNode> hyperparameters,
                Map<String, InputStream> parameters,
                Map<String, AlgorithmInstance<?>> algorithmDependencies) {
            return new TestVectorizer((TestAlgorithm) algorithmDependencies.get("child").algorithm());
        }
    }
}
