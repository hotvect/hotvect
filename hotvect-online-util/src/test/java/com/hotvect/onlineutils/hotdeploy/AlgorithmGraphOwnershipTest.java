package com.hotvect.onlineutils.hotdeploy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.reflect.TypeToken;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.algodefinition.AlgorithmDependencies;
import com.hotvect.api.algodefinition.AlgorithmDependencyDeclaration;
import com.hotvect.api.algodefinition.AlgorithmId;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algorithms.Algorithm;
import com.hotvect.api.execution.ExecutionContext;
import com.hotvect.api.execution.InputSemantic;
import com.hotvect.api.execution.WorkloadMode;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AlgorithmGraphOwnershipTest {
    private static final ExecutionContext EXECUTION_CONTEXT =
            ExecutionContext.of(WorkloadMode.BATCH, InputSemantic.OFFLINE);

    @Test
    void closesNestedDirectGraphDependentsBeforeDependencies() throws Exception {
        List<String> closeOrder = new ArrayList<>();
        TestProvider provider = provider(closeOrder, Map.of(
                "root", definition("root", Map.of(
                        "child", new AlgorithmDependencyDeclaration.Private(
                                "child", Optional.empty()))),
                "child", definition("child", Map.of())));

        try (AlgorithmGraph<?> graph = AlgorithmGraphResolver.resolve(
                new TestCatalog(provider),
                provider.readDefinition("root"),
                AlgorithmDependencies.empty(),
                AlgorithmGraphDependencies.empty(),
                ExecutionContext.realtime(InputSemantic.ONLINE))) {
            assertEquals(List.of(), closeOrder);
        }

        assertEquals(List.of("root", "child"), closeOrder);
    }

    @Test
    void rejectsAFactoryReturningItsPrivateDependencyWithoutClosingItTwice() {
        List<String> closeOrder = new ArrayList<>();
        TrackingAlgorithm aliased = new TrackingAlgorithm("aliased", closeOrder);
        TestProvider provider = provider(
                closeOrder,
                Map.of(
                        "root", definition("root", Map.of(
                                "child", new AlgorithmDependencyDeclaration.Private(
                                        "child", Optional.empty()))),
                        "child", definition("child", Map.of())),
                Map.of("root", aliased, "child", aliased),
                null);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> AlgorithmGraphResolver.resolve(
                        new TestCatalog(provider),
                        provider.readDefinition("root"),
                        AlgorithmDependencies.empty(),
                        AlgorithmGraphDependencies.empty(),
                        ExecutionContext.realtime(InputSemantic.ONLINE)));
        assertEquals("Factory for root@1 returned dependency child; factories must return a newly owned algorithm instance",
                failure.getMessage());
        assertEquals(List.of("aliased"), closeOrder);
    }

    @Test
    void rejectsAFactoryReturningAnApplicationBindingWithoutClosingTheBinding() {
        List<String> closeOrder = new ArrayList<>();
        TrackingAlgorithm host = new TrackingAlgorithm("host", closeOrder);
        TestProvider provider = provider(closeOrder, Map.of("root", definition("root", Map.of())),
                Map.of("root", host), null);
        AlgorithmDependencies bindings = new AlgorithmDependencies(Map.of("host",
                AlgorithmInstance.externalAlgorithm("host", TrackingAlgorithm.class, host)));

        assertThrows(IllegalArgumentException.class, () -> AlgorithmGraphResolver.resolve(
                new TestCatalog(provider), provider.readDefinition("root"), bindings,
                AlgorithmGraphDependencies.empty(), ExecutionContext.realtime(InputSemantic.ONLINE)));

        assertEquals(List.of(), closeOrder);
    }

    @Test
    void rejectsAFactoryReturningASlotDependencyWithoutClosingTheRetainedGraph() throws Exception {
        List<String> closeOrder = new ArrayList<>();
        TrackingAlgorithm child = new TrackingAlgorithm("child", closeOrder);
        TestProvider provider = provider(closeOrder, Map.of(
                "root", definition("root", Map.of("child-slot", new AlgorithmDependencyDeclaration.Slot("child-slot"))),
                "child", definition("child", Map.of())), Map.of("root", child, "child", child), null);
        TestCatalog catalog = new TestCatalog(provider);
        try (AlgorithmGraph<?> childGraph = AlgorithmGraphResolver.resolve(
                catalog, provider.readDefinition("child"), AlgorithmDependencies.empty(),
                AlgorithmGraphDependencies.empty(), ExecutionContext.realtime(InputSemantic.ONLINE))) {
            assertThrows(IllegalArgumentException.class, () -> AlgorithmGraphResolver.resolve(
                    catalog, provider.readDefinition("root"), AlgorithmDependencies.empty(),
                    new AlgorithmGraphDependencies(Map.of("child-slot", childGraph)),
                    ExecutionContext.realtime(InputSemantic.ONLINE)));

            assertEquals(child, childGraph.algorithm());
            assertEquals(List.of(), closeOrder);
        }
        assertEquals(List.of("child"), closeOrder);
    }

    @Test
    void rejectsAFactoryReturningASharedDependencyWithoutClosingAnotherParentsInstance() throws Exception {
        List<String> closeOrder = new ArrayList<>();
        TrackingAlgorithm shared = new TrackingAlgorithm("shared", closeOrder);
        TestProvider provider = provider(closeOrder, Map.of(
                "root", definition("root", Map.of("shared",
                        new AlgorithmDependencyDeclaration.Shared(new AlgorithmId("shared", "1")))),
                "shared", definition("shared", Map.of())), Map.of("root", shared, "shared", shared), null);
        TestCatalog catalog = new TestCatalog(provider);
        try (AlgorithmGraph<?> retainedSharedGraph = AlgorithmGraphResolver.resolve(
                catalog, provider.readDefinition("shared"), AlgorithmDependencies.empty(),
                AlgorithmGraphDependencies.empty(), ExecutionContext.realtime(InputSemantic.ONLINE))) {
            int[] releasedLeases = {0};
            SharedNodeInterner interner = (identity, factory) -> new SharedNodeInterner.SharedNode() {
                @Override
                public AlgorithmInstance<?> instance() { return retainedSharedGraph.root(); }

                @Override
                public com.hotvect.api.algodefinition.AlgorithmRuntimeId runtimeId() {
                    return retainedSharedGraph.runtimeId();
                }

                @Override
                public void close() { releasedLeases[0]++; }
            };
            assertThrows(IllegalArgumentException.class, () -> AlgorithmGraphResolver.resolve(
                    catalog, provider.readDefinition("root"), AlgorithmDependencies.empty(),
                    AlgorithmGraphDependencies.empty(), interner, ExecutionContext.realtime(InputSemantic.ONLINE)));

            assertEquals(1, releasedLeases[0]);
            assertEquals(List.of(), closeOrder);
        }
        assertEquals(List.of("shared"), closeOrder);
    }

    @Test
    void closesOneSharedNodeAfterAllOfItsDependents() throws Exception {
        List<String> closeOrder = new ArrayList<>();
        TestProvider provider = provider(closeOrder, Map.of(
                "root", definition("root", Map.of(
                        "left", new AlgorithmDependencyDeclaration.Private(
                                "left", Optional.empty()),
                        "right", new AlgorithmDependencyDeclaration.Private(
                                "right", Optional.empty()))),
                "left", definition("left", Map.of(
                        "shared", new AlgorithmDependencyDeclaration.Shared(new AlgorithmId("shared", "1")))),
                "right", definition("right", Map.of(
                        "shared", new AlgorithmDependencyDeclaration.Shared(new AlgorithmId("shared", "1")))),
                "shared", definition("shared", Map.of())));

        try (AlgorithmGraph<?> ignored = AlgorithmGraphResolver.resolve(
                new TestCatalog(provider),
                provider.readDefinition("root"),
                AlgorithmDependencies.empty(),
                AlgorithmGraphDependencies.empty(),
                ExecutionContext.realtime(InputSemantic.ONLINE))) {
            assertEquals(List.of(), closeOrder);
        }

        assertEquals(List.of("root", "right", "left", "shared"), closeOrder);
    }

    @Test
    void neverClosesApplicationOwnedHostBindings() throws Exception {
        List<String> closeOrder = new ArrayList<>();
        TestProvider provider = provider(closeOrder, Map.of(
                "root", definition("root", Map.of(
                        "child", new AlgorithmDependencyDeclaration.Private(
                                "child", Optional.empty()))),
                "child", definition("child", Map.of())));
        TrackingAlgorithm hostAlgorithm = new TrackingAlgorithm("host", closeOrder);
        AlgorithmInstance<TrackingAlgorithm> hostBinding = new AlgorithmInstance<>(
                definition("host", Map.of()),
                null,
                hostAlgorithm,
                TypeToken.of(TrackingAlgorithm.class));

        try (AlgorithmGraph<?> ignored = AlgorithmGraphResolver.resolve(
                new TestCatalog(provider),
                provider.readDefinition("root"),
                new AlgorithmDependencies(Map.of("child", hostBinding)),
                AlgorithmGraphDependencies.empty(),
                ExecutionContext.realtime(InputSemantic.ONLINE))) {
            // The graph owns the constructed root, but only borrows the host binding.
        }

        assertEquals(List.of("root"), closeOrder);
    }

    @Test
    void closesAlreadyConstructedChildrenWhenParentConstructionFails() {
        List<String> closeOrder = new ArrayList<>();
        TestProvider provider = provider(
                closeOrder,
                Map.of(
                        "root", definition("root", Map.of(
                                "child", new AlgorithmDependencyDeclaration.Private(
                                        "child", Optional.empty()))),
                        "child", definition("child", Map.of())),
                Map.of(),
                "root");

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> AlgorithmGraphResolver.resolve(
                        new TestCatalog(provider),
                        provider.readDefinition("root"),
                        AlgorithmDependencies.empty(),
                        AlgorithmGraphDependencies.empty(),
                        ExecutionContext.realtime(InputSemantic.ONLINE)));

        assertEquals("Cannot construct root", failure.getMessage());
        assertEquals(List.of("child"), closeOrder);
    }

    @Test
    void closesAlreadyConstructedChildrenWhenFeatureConstructionFails() {
        List<String> closeOrder = new ArrayList<>();
        TestProvider provider = provider(
                closeOrder,
                Map.of(
                        "root", definition("root", Map.of(
                                "child", new AlgorithmDependencyDeclaration.Private(
                                        "child", Optional.empty()))),
                        "child", definition("child", Map.of())),
                Map.of(),
                "root");

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> AlgorithmGraphResolver.resolveFeatureExtraction(
                        new TestCatalog(provider),
                        provider.readDefinition("root"),
                        AlgorithmDependencies.empty(),
                        ExecutionContext.realtime(InputSemantic.ONLINE)));

        assertEquals("Cannot construct root", failure.getMessage());
        assertEquals(List.of("child"), closeOrder);
    }

    @Test
    void retainsSuccessfulFeatureChildrenUntilFeatureGraphCloses() throws Exception {
        List<String> closeOrder = new ArrayList<>();
        TestProvider provider = provider(closeOrder, Map.of(
                "root", definition("root", Map.of(
                        "child", new AlgorithmDependencyDeclaration.Private(
                                "child", Optional.empty()))),
                "child", definition("child", Map.of())));

        AlgorithmGraphResolver.ResolvedFeatureExtraction<Object> resolved =
                AlgorithmGraphResolver.resolveFeatureExtraction(
                        new TestCatalog(provider),
                        provider.readDefinition("root"),
                        AlgorithmDependencies.empty(),
                        ExecutionContext.realtime(InputSemantic.ONLINE));

        assertEquals(List.of(), closeOrder);
        resolved.ownership().close();
        assertEquals(List.of("child"), closeOrder);
    }

    private TestProvider provider(
            List<String> closeOrder,
            Map<String, AlgorithmDefinition> definitions) {
        return provider(closeOrder, definitions, Map.of(), null);
    }

    private TestProvider provider(
            List<String> closeOrder,
            Map<String, AlgorithmDefinition> definitions,
            Map<String, TrackingAlgorithm> suppliedAlgorithms,
            String failingAlgorithmName) {
        TrackingFactory factory = new TrackingFactory(
                closeOrder,
                suppliedAlgorithms,
                failingAlgorithmName,
                getClass().getClassLoader());
        return new TestProvider(factory, definitions);
    }

    private static AlgorithmDefinition definition(
            String name,
            Map<String, AlgorithmDependencyDeclaration> declarations) {
        return new AlgorithmDefinition(
                JsonNodeFactory.instance.objectNode(),
                new AlgorithmId(name, "1"),
                declarations,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static final class TrackingFactory extends AlgorithmInstanceFactory {
        private final List<String> closeOrder;
        private final Map<String, TrackingAlgorithm> suppliedAlgorithms;
        private final String failingAlgorithmName;

        private TrackingFactory(
                List<String> closeOrder,
                Map<String, TrackingAlgorithm> suppliedAlgorithms,
                String failingAlgorithmName,
                ClassLoader classLoader) {
            super(classLoader, new Options(EXECUTION_CONTEXT.inputSemantic(), true, false, Optional.empty()));
            this.closeOrder = closeOrder;
            this.suppliedAlgorithms = suppliedAlgorithms;
            this.failingAlgorithmName = failingAlgorithmName;
        }

        @Override
        protected <ALGO extends Algorithm> AlgorithmInstance<ALGO> construct(
                AlgorithmDefinition algorithmDefinition,
                File parameterFile,
                AlgorithmDependencies dependencies,
                AlgorithmDependencies applicationBindings,
                ExecutionContext executionContext) {
            String name = algorithmDefinition.algorithmId().algorithmName();
            if (name.equals(failingAlgorithmName)) {
                throw new IllegalStateException("Cannot construct " + name);
            }
            TrackingAlgorithm algorithm = suppliedAlgorithms.get(name);
            if (algorithm == null) {
                algorithm = new TrackingAlgorithm(name, closeOrder);
            }
            @SuppressWarnings("unchecked")
            AlgorithmInstance<ALGO> instance = (AlgorithmInstance<ALGO>) new AlgorithmInstance<>(
                    algorithmDefinition,
                    null,
                    algorithm,
                    TypeToken.of(TrackingAlgorithm.class));
            return instance;
        }

        @Override
        @SuppressWarnings("unchecked")
        protected <DEPENDENCY> DEPENDENCY constructFeatureExtractionDependency(
                AlgorithmDefinition algorithmDefinition,
                File parameterFile,
                AlgorithmDependencies dependencies,
                AlgorithmDependencies applicationBindings,
                ExecutionContext executionContext) {
            String name = algorithmDefinition.algorithmId().algorithmName();
            if (name.equals(failingAlgorithmName)) {
                throw new IllegalStateException("Cannot construct " + name);
            }
            return (DEPENDENCY) new Object();
        }
    }

    private record TestProvider(
            TrackingFactory factory,
            Map<String, AlgorithmDefinition> definitions) implements AlgorithmGraphResolver.Provider {
        @Override
        public AlgorithmDefinition readDefinition(String algorithmName) {
            AlgorithmDefinition definition = definitions.get(algorithmName);
            if (definition == null) {
                throw new IllegalArgumentException("No definition for " + algorithmName);
            }
            return definition;
        }

        @Override
        public ClassLoader classLoader() {
            return factory.classLoader;
        }

        @Override
        public File parameterFile() {
            return null;
        }
    }

    private record TestCatalog(TestProvider provider) implements AlgorithmGraphResolver.ProviderCatalog {
        @Override
        public AlgorithmGraphResolver.Provider rootProvider(AlgorithmDefinition rootDefinition) {
            return provider;
        }

        @Override
        public AlgorithmGraphResolver.Provider privateProvider(
                AlgorithmGraphResolver.Provider parent,
                AlgorithmDependencyDeclaration.Private declaration) {
            return provider;
        }

        @Override
        public AlgorithmGraphResolver.Provider sharedProvider(AlgorithmDependencyDeclaration.Shared declaration) {
            return provider;
        }
    }

    private static final class TrackingAlgorithm implements Algorithm {
        private final String name;
        private final List<String> closeOrder;

        private TrackingAlgorithm(String name, List<String> closeOrder) {
            this.name = name;
            this.closeOrder = closeOrder;
        }

        @Override
        public void close() {
            closeOrder.add(name);
        }
    }
}
