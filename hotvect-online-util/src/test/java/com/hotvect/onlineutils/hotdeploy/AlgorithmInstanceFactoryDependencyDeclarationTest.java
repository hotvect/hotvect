package com.hotvect.onlineutils.hotdeploy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.google.common.reflect.TypeToken;
import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.algodefinition.AlgorithmDependencies;
import com.hotvect.api.algodefinition.AlgorithmDependencyDeclaration;
import com.hotvect.api.algodefinition.AlgorithmId;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algodefinition.AlgorithmParameterMetadata;
import com.hotvect.api.algodefinition.common.CompositeAlgorithmFactory;
import com.hotvect.api.algodefinition.common.SimpleAlgorithmFactory;
import com.hotvect.api.algodefinition.storage.LocalStateStorage;
import com.hotvect.api.algorithms.Algorithm;
import com.hotvect.api.algorithms.Scorer;
import com.hotvect.api.execution.ExecutionContext;
import com.hotvect.api.execution.InputSemantic;
import com.hotvect.api.execution.WorkloadMode;
import com.hotvect.onlineutils.util.Closeables;
import com.hotvect.utils.AlgorithmDefinitionReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlgorithmInstanceFactoryDependencyDeclarationTest {
    private static final ExecutionContext EXECUTION_CONTEXT =
            ExecutionContext.of(WorkloadMode.BATCH, InputSemantic.OFFLINE);

    @Test
    void resolvesPrivateAndSharedDeclarationsOnceBeforeConstructingTheParent() throws Exception {
        List<Construction> constructions = new ArrayList<>();
        TestProvider rootProvider = provider(
                "root-provider",
                constructions,
                Map.of(
                        "root", definition("root", Map.of(
                                "private-child", new AlgorithmDependencyDeclaration.Private(
                                        "private-child", Optional.empty()),
                                "shared-child", new AlgorithmDependencyDeclaration.Shared(
                                        new AlgorithmId("shared-child", "1")))),
                        "private-child", definition("private-child", Map.of())));
        TestProvider sharedProvider = provider(
                "shared-provider",
                constructions,
                Map.of("shared-child", definition("shared-child", Map.of())));
        TestCatalog catalog = new TestCatalog(rootProvider, Map.of("shared-child", sharedProvider));

        try (AlgorithmGraph<?> graph = AlgorithmGraphResolver.resolve(
                catalog,
                rootProvider.readDefinition("root"),
                AlgorithmDependencies.empty(),
                AlgorithmGraphDependencies.empty(),
                ExecutionContext.realtime(InputSemantic.ONLINE))) {
            assertEquals(
                    List.of("private-child", "shared-child", "root"),
                    constructions.stream().map(construction -> construction.algorithmName()).toList());
            assertSame(
                    constructions.getFirst().instance(),
                    constructions.getLast().dependencies().asMap().get("private-child"));
            assertSame(
                    constructions.get(1).instance(),
                    constructions.getLast().dependencies().asMap().get("shared-child"));
            assertSame(
                    rootProvider.classLoader(),
                    graph.rootArtifactClassLoader());
        }
    }

    @Test
    void passesTheParameterArchiveToEveryResolvedPackagedNode() throws Exception {
        List<Construction> constructions = new ArrayList<>();
        File parameterArchive = new File("root-parameters.zip");
        TestProvider rootProvider = provider(
                "root-provider",
                constructions,
                Map.of(
                        "root", definition("root", Map.of(
                                "private-child", new AlgorithmDependencyDeclaration.Private(
                                        "private-child", Optional.empty()),
                                "shared-child", new AlgorithmDependencyDeclaration.Shared(
                                        new AlgorithmId("shared-child", "1")))),
                        "private-child", definition("private-child", Map.of())),
                parameterArchive);
        TestProvider sharedProvider = provider(
                "shared-provider",
                constructions,
                Map.of("shared-child", definition("shared-child", Map.of())),
                parameterArchive);

        try (AlgorithmGraph<?> ignored = AlgorithmGraphResolver.resolve(
                new TestCatalog(rootProvider, Map.of("shared-child", sharedProvider)),
                rootProvider.readDefinition("root"),
                AlgorithmDependencies.empty(),
                AlgorithmGraphDependencies.empty(),
                ExecutionContext.realtime(InputSemantic.ONLINE))) {
            assertSame(parameterArchive, parameterFile(constructions, "root"));
            assertSame(parameterArchive, parameterFile(constructions, "private-child"));
            assertSame(parameterArchive, parameterFile(constructions, "shared-child"));
        }
    }

    @Test
    void rejectsSharedVersionMismatches() {
        TestProvider sharedProvider = provider(
                "shared-provider",
                new ArrayList<>(),
                Map.of("shared-child", definition("shared-child", Map.of())));
        TestProvider sharedRoot = provider(
                "root-provider",
                new ArrayList<>(),
                Map.of("root", definition("root", Map.of(
                        "shared-child", new AlgorithmDependencyDeclaration.Shared(
                                new AlgorithmId("shared-child", "2"))))));
        TestCatalog catalog = new TestCatalog(sharedRoot, Map.of("shared-child", sharedProvider));

        IllegalArgumentException inspectionMismatch = assertThrows(
                IllegalArgumentException.class,
                () -> AlgorithmGraphResolver.inspect(
                        catalog,
                        sharedRoot.readDefinition("root"),
                        AlgorithmDependencies.empty()));
        IllegalArgumentException sharedMismatch = assertThrows(
                IllegalArgumentException.class,
                () -> AlgorithmGraphResolver.resolve(
                        catalog,
                        sharedRoot.readDefinition("root"),
                        AlgorithmDependencies.empty(),
                        AlgorithmGraphDependencies.empty(),
                        ExecutionContext.realtime(InputSemantic.ONLINE)));
        assertEquals(
                "Dependency shared-child requires shared-child@2 but resolved shared-child@1",
                sharedMismatch.getMessage());
        assertEquals(sharedMismatch.getMessage(), inspectionMismatch.getMessage());
    }

    @Test
    void reusesOneSharedInstanceAcrossIndependentParentEdges() throws Exception {
        List<Construction> constructions = new ArrayList<>();
        TestProvider rootProvider = provider(
                "root-provider",
                constructions,
                Map.of(
                        "root", definition("root", Map.of(
                                "left", new AlgorithmDependencyDeclaration.Private(
                                        "left", Optional.empty()),
                                "right", new AlgorithmDependencyDeclaration.Private(
                                        "right", Optional.empty()))),
                        "left", definition("left", Map.of(
                                "shared-child", new AlgorithmDependencyDeclaration.Shared(
                                        new AlgorithmId("shared-child", "1")))),
                        "right", definition("right", Map.of(
                                "shared-child", new AlgorithmDependencyDeclaration.Shared(
                                        new AlgorithmId("shared-child", "1"))))));
        TestProvider sharedProvider = provider(
                "shared-provider",
                constructions,
                Map.of("shared-child", definition("shared-child", Map.of())));

        try (AlgorithmGraph<?> graph = AlgorithmGraphResolver.resolve(
                new TestCatalog(rootProvider, Map.of("shared-child", sharedProvider)),
                rootProvider.readDefinition("root"),
                AlgorithmDependencies.empty(),
                AlgorithmGraphDependencies.empty(),
                ExecutionContext.realtime(InputSemantic.ONLINE))) {
            AlgorithmInstance<?> left = constructions.get(1).instance();
            AlgorithmInstance<?> right = constructions.get(2).instance();

            assertSame(
                    constructions.get(1).dependencies().asMap().get("shared-child"),
                    constructions.get(2).dependencies().asMap().get("shared-child"));
            assertEquals(
                    List.of("shared-child", "left", "right", "root"),
                    constructions.stream().map(Construction::algorithmName).toList());
        }
    }

    @Test
    void requiresAnExternalBindingForASlotDeclaration() {
        TestProvider provider = provider(
                "root-provider",
                new ArrayList<>(),
                Map.of("root", definition("root", Map.of(
                        "candidate-slot",
                        new AlgorithmDependencyDeclaration.Slot("candidate-slot")))));

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> {
                    try (AlgorithmGraph<?> ignored = AlgorithmGraphResolver.resolve(
                            new TestCatalog(provider, Map.of()),
                            provider.readDefinition("root"),
                            AlgorithmDependencies.empty(),
                            AlgorithmGraphDependencies.empty(),
                            ExecutionContext.realtime(InputSemantic.ONLINE))) {
                        throw new AssertionError("Expected the slot declaration to require a host binding");
                    }
                });

        assertEquals(
                "Slot-backed dependency root.candidate-slot requires an externally selected algorithm for EMS slot candidate-slot",
                error.getMessage());
    }

    @Test
    void hostBindingDisplacesTheDeclaredChildBeforeItIsConstructed() throws Exception {
        List<Construction> constructions = new ArrayList<>();
        TestProvider provider = provider(
                "root-provider",
                constructions,
                Map.of(
                        "root", definition("root", Map.of(
                                "child", new AlgorithmDependencyDeclaration.Private(
                                        "child", Optional.empty()))),
                        "child", definition("child", Map.of())));
        AlgorithmInstance<NoopAlgorithm> hostChild = instance("host-child", provider.classLoader());

        try (AlgorithmGraph<?> graph = AlgorithmGraphResolver.resolve(
                new TestCatalog(provider, Map.of()),
                provider.readDefinition("root"),
                new AlgorithmDependencies(Map.of("child", hostChild)),
                AlgorithmGraphDependencies.empty(),
                ExecutionContext.realtime(InputSemantic.ONLINE))) {
            assertEquals(List.of("root"), constructions.stream().map(Construction::algorithmName).toList());
            assertSame(hostChild, constructions.getFirst().dependencies().asMap().get("child"));
        }
    }

    @Test
    void validatesInjectedHostBindingsAcrossArtifactBoundaries() throws Exception {
        TestProvider provider = provider(
                "root-provider",
                new ArrayList<>(),
                Map.of("root", definition("root", Map.of(
                        "child", new AlgorithmDependencyDeclaration.Private(
                                "child", Optional.empty())))));
        ClassLoader bindingLoader = isolatedLoader(
                DomainModelBoundaryValidatorTest.IsolatedScorerFactory.class,
                DomainModelBoundaryValidatorTest.PrivateDomain.class);
        Class<?> isolatedFactory = bindingLoader.loadClass(
                DomainModelBoundaryValidatorTest.IsolatedScorerFactory.class.getName());
        @SuppressWarnings("unchecked")
        SimpleAlgorithmFactory<Algorithm> factory = (SimpleAlgorithmFactory<Algorithm>)
                isolatedFactory.getDeclaredConstructor().newInstance();
        Algorithm childAlgorithm = factory.apply(Optional.empty());
        @SuppressWarnings("unchecked")
        TypeToken<Algorithm> childContract = (TypeToken<Algorithm>) (TypeToken<?>)
                AlgorithmContractResolver.resolve(isolatedFactory);
        AlgorithmInstance<Algorithm> hostChild = new AlgorithmInstance<>(
                definition(
                        "host-child",
                        Map.of(),
                        DomainModelBoundaryValidatorTest.IsolatedScorerFactory.class.getName()),
                null,
                childAlgorithm,
                childContract);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> {
                    try (AlgorithmGraph<?> ignored = AlgorithmGraphResolver.resolve(
                            new TestCatalog(provider, Map.of()),
                            provider.readDefinition("root"),
                            new AlgorithmDependencies(Map.of("child", hostChild)),
                            AlgorithmGraphDependencies.empty(),
                            ExecutionContext.realtime(InputSemantic.ONLINE))) {
                        throw new AssertionError("Expected cross-artifact host binding validation to fail");
                    }
                });

        assertTrue(error.getMessage().contains(DomainModelBoundaryValidatorTest.PrivateDomain.class.getName()));
    }

    @Test
    void acceptsApplicationOwnedHostBindingsAcrossArtifactBoundaries() throws Exception {
        List<Construction> constructions = new ArrayList<>();
        ClassLoader artifactClassLoader = new ClassLoader(getClass().getClassLoader()) {
        };
        TestProvider provider = provider(
                "root-provider",
                constructions,
                artifactClassLoader,
                Map.of("root", definition("root", Map.of(
                        "child", new AlgorithmDependencyDeclaration.Private(
                                "child", Optional.empty())))));
        AlgorithmInstance<NoopAlgorithm> applicationDependency = AlgorithmInstance.externalAlgorithm(
                "child",
                NoopAlgorithm.class,
                new NoopAlgorithm());

        try (AlgorithmGraph<?> graph = AlgorithmGraphResolver.resolve(
                new TestCatalog(provider, Map.of()),
                provider.readDefinition("root"),
                new AlgorithmDependencies(Map.of("child", applicationDependency)),
                AlgorithmGraphDependencies.empty(),
                ExecutionContext.realtime(InputSemantic.ONLINE))) {
            assertEquals(List.of("root"), constructions.stream().map(Construction::algorithmName).toList());
            assertSame(
                    applicationDependency,
                    constructions.getFirst().dependencies().asMap().get("child"));
        }
    }

    @Test
    void appliesPrivateDefinitionOverridesBeforeConstructingTheChild() throws Exception {
        List<Construction> constructions = new ArrayList<>();
        TestProvider provider = provider(
                "root-provider",
                constructions,
                Map.of(
                        "root", definition("root", Map.of(
                                "child", new AlgorithmDependencyDeclaration.Private(
                                        "child",
                                        Optional.of(JsonNodeFactory.instance.objectNode().put(
                                                "algorithm_factory_classname",
                                                "fixtures.OverrideFactory"))))),
                        "child", new AlgorithmDefinitionReader().parse("""
                                {
                                  "algorithm_name": "child",
                                  "algorithm_version": "1",
                                  "algorithm_factory_classname": "fixtures.BaseFactory"
                                }
                                """)));
        TestCatalog catalog = new TestCatalog(provider, Map.of());

        AlgorithmGraphInspection inspection = AlgorithmGraphResolver.inspect(
                catalog,
                provider.readDefinition("root"),
                AlgorithmDependencies.empty());
        assertTrue(constructions.isEmpty());
        assertEquals(
                List.of(new AlgorithmId("root", "1"), new AlgorithmId("child", "1")),
                new ArrayList<>(inspection.packagedDefinitions().keySet()));

        try (AlgorithmGraph<?> ignored = AlgorithmGraphResolver.resolve(
                catalog,
                provider.readDefinition("root"),
                AlgorithmDependencies.empty(),
                AlgorithmGraphDependencies.empty(),
                ExecutionContext.realtime(InputSemantic.ONLINE))) {
            assertEquals(List.of("child", "root"), constructions.stream().map(Construction::algorithmName).toList());
            assertEquals(
                    "fixtures.OverrideFactory",
                    constructions.getFirst().instance().algorithmDefinition().algorithmFactoryName());
        }
    }

    @Test
    void offlineTreatsSharedDeclarationsAsPrivateAndAppliesTheirOverrides() throws Exception {
        List<Construction> constructions = new ArrayList<>();
        AlgorithmDefinitionReader reader = new AlgorithmDefinitionReader(EXECUTION_CONTEXT.inputSemantic());
        TestProvider provider = provider(
                "root-provider",
                constructions,
                Map.of(
                        "root", reader.parse("""
                                {
                                  "algorithm_name": "root",
                                  "algorithm_version": "1",
                                  "algorithm_factory_classname": "fixtures.RootFactory",
                                  "dependencies": {
                                    "shared-child@different": {
                                      "scope": "shared",
                                      "algorithm_parameters": {"threshold": 0.7}
                                    }
                                  }
                                }
                                """),
                        "shared-child", reader.parse("""
                                {
                                  "algorithm_name": "shared-child",
                                  "algorithm_version": "1",
                                  "algorithm_factory_classname": "fixtures.ChildFactory"
                                }
                                """)));

        try (AlgorithmGraph<?> ignored = AlgorithmGraphResolver.resolve(
                new TestCatalog(provider, Map.of()),
                provider.readDefinition("root"),
                AlgorithmDependencies.empty(),
                AlgorithmGraphDependencies.empty(),
                ExecutionContext.realtime(InputSemantic.ONLINE))) {
            assertEquals(List.of("shared-child", "root"), constructions.stream()
                    .map(Construction::algorithmName)
                    .toList());
            assertEquals(
                    0.7,
                    constructions.getFirst().instance().algorithmDefinition().algorithmParameter()
                            .orElseThrow()
                            .path("threshold")
                            .asDouble());
        }
    }

    @Test
    void ignoresHostBindingsNotConsumedByTheResolvedGraph() throws Exception {
        TestProvider provider = provider(
                "root-provider",
                new ArrayList<>(),
                Map.of("root", definition("root", Map.of())));

        try (AlgorithmGraph<?> graph = AlgorithmGraphResolver.resolve(
                new TestCatalog(provider, Map.of()),
                provider.readDefinition("root"),
                new AlgorithmDependencies(Map.of("unrelated", instance(
                        "unrelated",
                        provider.classLoader()))),
                AlgorithmGraphDependencies.empty(),
                ExecutionContext.realtime(InputSemantic.ONLINE))) {
            assertEquals("root", graph.root().algorithmDefinition().algorithmId().algorithmName());
        }
    }

    @Test
    void rejectsIndirectCyclesWithTheCompletePath() {
        AlgorithmDefinition root = definition("root", Map.of(
                "child", new AlgorithmDependencyDeclaration.Private(
                        "child", Optional.empty())));
        AlgorithmDefinition child = definition("child", Map.of(
                "root", new AlgorithmDependencyDeclaration.Private(
                        "root", Optional.empty())));
        TestProvider provider = provider(
                "root-provider",
                new ArrayList<>(),
                Map.of(
                        "root", root,
                        "child", child));
        TestCatalog catalog = new TestCatalog(provider, Map.of());

        IllegalArgumentException inspectionError = assertThrows(
                IllegalArgumentException.class,
                () -> AlgorithmGraphResolver.inspect(
                        catalog,
                        provider.readDefinition("root"),
                        AlgorithmDependencies.empty()));

        RuntimeException error = assertThrows(
                RuntimeException.class,
                () -> {
                    try (AlgorithmGraph<?> ignored = AlgorithmGraphResolver.resolve(
                            catalog,
                            provider.readDefinition("root"),
                            AlgorithmDependencies.empty(),
                            AlgorithmGraphDependencies.empty(),
                            ExecutionContext.realtime(InputSemantic.ONLINE))) {
                        throw new AssertionError("Expected cyclic dependency validation to fail");
                    }
                });

        assertEquals("Cyclic algorithm dependency: root -> child -> root", error.getMessage());
        assertEquals(error.getMessage(), inspectionError.getMessage());
    }

    @Test
    void rejectsStaticSharedCyclesBeforeTheRuntimeInternerCanReenterItself() {
        TestProvider provider = provider(
                "root-provider",
                new ArrayList<>(),
                Map.of(
                        "root", definition("root", Map.of(
                                "shared", new AlgorithmDependencyDeclaration.Shared(new AlgorithmId("shared", "1")))),
                        "shared", definition("shared", Map.of(
                                "shared", new AlgorithmDependencyDeclaration.Shared(new AlgorithmId("shared", "1"))))));
        SharedNodeInterner interner = (identity, graphFactory) -> {
            AlgorithmGraph<?> graph = graphFactory.get();
            return new SharedNodeInterner.SharedNode() {
                @Override
                public AlgorithmInstance<?> instance() {
                    return graph.root();
                }

                @Override
                public com.hotvect.api.algodefinition.AlgorithmRuntimeId runtimeId() {
                    return graph.runtimeId();
                }

                @Override
                public void close() {
                    Closeables.closeAll("Could not close shared test graph", graph);
                }
            };
        };

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> AlgorithmGraphResolver.resolve(
                        new TestCatalog(provider, Map.of("shared", provider)),
                        provider.readDefinition("root"),
                        AlgorithmDependencies.empty(),
                        AlgorithmGraphDependencies.empty(),
                        interner,
                        ExecutionContext.realtime(InputSemantic.ONLINE)));

        assertEquals("Cyclic algorithm dependency: shared -> shared", error.getMessage());
    }

    @Test
    void preservesFactoryGenericContractsForCompositeDependencyLookup() {
        AlgorithmDefinition root = definition("root", Map.of(
                "child", new AlgorithmDependencyDeclaration.Private("child", Optional.empty())),
                IntegerGenericConsumerFactory.class.getName());
        AlgorithmDefinition child = definition(
                "child",
                Map.of(),
                StringGenericAlgorithmFactory.class.getName());
        AlgorithmInstanceFactory factory = new AlgorithmInstanceFactory(
                getClass().getClassLoader(),
                new AlgorithmInstanceFactory.Options(EXECUTION_CONTEXT.inputSemantic(), true, false, Optional.empty()));
        RealProvider provider = new RealProvider("real-provider", factory, Map.of("root", root, "child", child));

        RuntimeException error = assertThrows(
                RuntimeException.class,
                () -> AlgorithmGraphResolver.resolve(
                        new RealCatalog(provider),
                        root,
                        AlgorithmDependencies.empty(),
                        AlgorithmGraphDependencies.empty(),
                        ExecutionContext.realtime(InputSemantic.ONLINE)));

        assertTrue(error.getMessage().contains("GenericAlgorithm<java.lang.String>"));
        assertTrue(error.getMessage().contains("GenericAlgorithm<java.lang.Integer>"));
    }

    @Test
    void composesAGenericFactoryThroughTypedDependencyLookup() throws Exception {
        AlgorithmDefinition root = definition("root", Map.of(
                "child", new AlgorithmDependencyDeclaration.Private("child", Optional.empty())),
                IntegerGenericConsumerFactory.class.getName());
        AlgorithmDefinition child = definition("child", Map.of(), GenericAlgorithmFactory.class.getName());
        try (AlgorithmInstanceFactory factory = new AlgorithmInstanceFactory(
                getClass().getClassLoader(),
                new AlgorithmInstanceFactory.Options(EXECUTION_CONTEXT.inputSemantic(), true, false, Optional.empty()))) {
            RealProvider provider = new RealProvider("real-provider", factory, Map.of("root", root, "child", child));
            try (AlgorithmGraph<Scorer<String>> graph = AlgorithmGraphResolver.resolve(
                    new RealCatalog(provider),
                    root,
                    AlgorithmDependencies.empty(),
                    AlgorithmGraphDependencies.empty(),
                    EXECUTION_CONTEXT)) {
                assertEquals(0.0, graph.algorithm().applyAsDouble("example"));
            }
        }
    }

    @Test
    void exposesAmbientApplicationBindingsOnlyThroughThePublishedSingletonFactoryContract() throws Exception {
        AlgorithmDependencies applicationBindings = new AlgorithmDependencies(Map.of(
                "feature-store",
                instance("feature-store", getClass().getClassLoader())));

        AlgorithmDefinition legacyRoot = definition(
                "legacy-root",
                Map.of(),
                LegacyAmbientConsumerFactory.class.getName());
        AlgorithmInstanceFactory legacyFactory = new AlgorithmInstanceFactory(
                getClass().getClassLoader(),
                new AlgorithmInstanceFactory.Options(EXECUTION_CONTEXT.inputSemantic(), true, false, Optional.empty()));
        RealProvider legacyProvider = new RealProvider(
                "legacy-provider",
                legacyFactory,
                Map.of("legacy-root", legacyRoot));
        try (AlgorithmGraph<DependencyNamesAlgorithm> graph = AlgorithmGraphResolver.resolve(
                new RealCatalog(legacyProvider),
                legacyRoot,
                applicationBindings,
                AlgorithmGraphDependencies.empty(),
                ExecutionContext.realtime(InputSemantic.ONLINE))) {
            assertEquals(Set.of("feature-store"), graph.algorithm().dependencyNames());
        }

        AlgorithmDefinition currentRoot = definition(
                "current-root",
                Map.of(),
                DeclaredDependencyConsumerFactory.class.getName());
        AlgorithmInstanceFactory currentFactory = new AlgorithmInstanceFactory(
                getClass().getClassLoader(),
                new AlgorithmInstanceFactory.Options(EXECUTION_CONTEXT.inputSemantic(), true, false, Optional.empty()));
        RealProvider currentProvider = new RealProvider(
                "current-provider",
                currentFactory,
                Map.of("current-root", currentRoot));
        try (AlgorithmGraph<DependencyNamesAlgorithm> graph = AlgorithmGraphResolver.resolve(
                new RealCatalog(currentProvider),
                currentRoot,
                applicationBindings,
                AlgorithmGraphDependencies.empty(),
                ExecutionContext.realtime(InputSemantic.ONLINE))) {
            assertEquals(Set.of(), graph.algorithm().dependencyNames());
        }
    }

    private static TestProvider provider(
            String name,
            List<Construction> constructions,
            Map<String, AlgorithmDefinition> definitions) {
        return provider(
                name,
                constructions,
                AlgorithmInstanceFactoryDependencyDeclarationTest.class.getClassLoader(),
                null,
                definitions);
    }

    private static TestProvider provider(
            String name,
            List<Construction> constructions,
            ClassLoader classLoader,
            Map<String, AlgorithmDefinition> definitions) {
        return provider(name, constructions, classLoader, null, definitions);
    }

    private static TestProvider provider(
            String name,
            List<Construction> constructions,
            Map<String, AlgorithmDefinition> definitions,
            File parameterFile) {
        return provider(
                name,
                constructions,
                AlgorithmInstanceFactoryDependencyDeclarationTest.class.getClassLoader(),
                parameterFile,
                definitions);
    }

    private static TestProvider provider(
            String name,
            List<Construction> constructions,
            ClassLoader classLoader,
            File parameterFile,
            Map<String, AlgorithmDefinition> definitions) {
        RecordingFactory factory = new RecordingFactory(constructions, classLoader);
        return new TestProvider(name, factory, parameterFile, definitions);
    }

    private static AlgorithmDefinition definition(
            String name,
            Map<String, AlgorithmDependencyDeclaration> declarations) {
        return definition(name, declarations, null);
    }

    private static AlgorithmDefinition definition(
            String name,
            Map<String, AlgorithmDependencyDeclaration> declarations,
            String algorithmFactoryName) {
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
                algorithmFactoryName,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static AlgorithmInstance<NoopAlgorithm> instance(String name, ClassLoader classLoader) {
        return new AlgorithmInstance<>(
                definition(name, Map.of()),
                null,
                new NoopAlgorithm(),
                TypeToken.of(NoopAlgorithm.class));
    }

    private static File parameterFile(List<Construction> constructions, String algorithmName) {
        return constructions.stream()
                .filter(construction -> construction.algorithmName().equals(algorithmName))
                .findFirst()
                .orElseThrow()
                .parameterFile();
    }

    private static ClassLoader isolatedLoader(Class<?>... classes) throws IOException {
        Map<String, byte[]> definitions = new HashMap<>();
        for (Class<?> type : classes) {
            definitions.put(type.getName(), classBytes(type));
        }
        return new ByteArrayClassLoader(
                AlgorithmInstanceFactoryDependencyDeclarationTest.class.getClassLoader(),
                definitions);
    }

    private static byte[] classBytes(Class<?> type) throws IOException {
        String resource = type.getName().replace('.', '/') + ".class";
        try (InputStream input = type.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Class bytes not found: " + resource);
            }
            return input.readAllBytes();
        }
    }

    private static final class ByteArrayClassLoader extends ClassLoader {
        private final Map<String, byte[]> definitions;

        private ByteArrayClassLoader(ClassLoader parent, Map<String, byte[]> definitions) {
            super(parent);
            this.definitions = Map.copyOf(definitions);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    byte[] bytes = definitions.get(name);
                    loaded = bytes == null ? super.loadClass(name, false) : defineClass(name, bytes, 0, bytes.length);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }
    }

    private record Construction(
            String algorithmName,
            AlgorithmInstance<?> instance,
            File parameterFile,
            AlgorithmDependencies dependencies) {
    }

    private static final class RecordingFactory extends AlgorithmInstanceFactory {
        private final List<Construction> constructions;

        private RecordingFactory(List<Construction> constructions, ClassLoader classLoader) {
            super(
                    classLoader,
                    new Options(EXECUTION_CONTEXT.inputSemantic(), true, false, Optional.empty()));
            this.constructions = constructions;
        }

        @Override
        AlgorithmParameterMetadata parameterMetadata(
                AlgorithmDefinition algorithmDefinition,
                File parameterFile) {
            return null;
        }

        @Override
        protected <ALGO extends Algorithm> AlgorithmInstance<ALGO> construct(
                AlgorithmDefinition algorithmDefinition,
                File parameterFile,
                AlgorithmDependencies dependencies,
                AlgorithmDependencies applicationBindings,
                ExecutionContext executionContext) {
            @SuppressWarnings("unchecked")
            AlgorithmInstance<ALGO> instance = (AlgorithmInstance<ALGO>) (AlgorithmInstance<?>) new AlgorithmInstance<>(
                    algorithmDefinition,
                    null,
                    new NoopAlgorithm(),
                    TypeToken.of(NoopAlgorithm.class));
            constructions.add(new Construction(
                    algorithmDefinition.algorithmId().algorithmName(),
                    instance,
                    parameterFile,
                    dependencies));
            return instance;
        }
    }

    private record TestProvider(
            String name,
            RecordingFactory factory,
            File parameterFile,
            Map<String, AlgorithmDefinition> definitions) implements AlgorithmGraphResolver.Provider {

        private TestProvider {
            definitions = Map.copyOf(new LinkedHashMap<>(definitions));
        }

        @Override
        public AlgorithmDefinition readDefinition(String algorithmName) {
            AlgorithmDefinition definition = definitions.get(algorithmName);
            if (definition == null) {
                throw new IllegalArgumentException("Provider " + name + " does not define " + algorithmName);
            }
            return definition;
        }

        @Override
        public ClassLoader classLoader() {
            return factory.classLoader;
        }

    }

    private record TestCatalog(TestProvider root, Map<String, TestProvider> shared)
            implements AlgorithmGraphResolver.ProviderCatalog {
        private TestCatalog {
            shared = Map.copyOf(shared);
        }

        @Override
        public AlgorithmGraphResolver.Provider rootProvider(AlgorithmDefinition rootDefinition) {
            return root;
        }

        @Override
        public AlgorithmGraphResolver.Provider privateProvider(
                AlgorithmGraphResolver.Provider parent,
                AlgorithmDependencyDeclaration.Private declaration) {
            return parent;
        }

        @Override
        public AlgorithmGraphResolver.Provider sharedProvider(
                AlgorithmDependencyDeclaration.Shared declaration) {
            TestProvider provider = shared.get(declaration.name());
            if (provider == null) {
                throw new IllegalArgumentException("No shared provider for " + declaration.name());
            }
            return provider;
        }
    }

    private record RealProvider(
            String name,
            AlgorithmInstanceFactory factory,
            Map<String, AlgorithmDefinition> definitions) implements AlgorithmGraphResolver.Provider {
        private RealProvider {
            definitions = Map.copyOf(new LinkedHashMap<>(definitions));
        }

        @Override
        public AlgorithmDefinition readDefinition(String algorithmName) {
            AlgorithmDefinition definition = definitions.get(algorithmName);
            if (definition == null) {
                throw new IllegalArgumentException("Provider " + name + " does not define " + algorithmName);
            }
            return definition;
        }

        @Override
        public ClassLoader classLoader() {
            return AlgorithmInstanceFactoryDependencyDeclarationTest.class.getClassLoader();
        }

        @Override
        public File parameterFile() {
            return null;
        }
    }

    private record RealCatalog(RealProvider root) implements AlgorithmGraphResolver.ProviderCatalog {
        @Override
        public AlgorithmGraphResolver.Provider rootProvider(AlgorithmDefinition rootDefinition) {
            return root;
        }

        @Override
        public AlgorithmGraphResolver.Provider privateProvider(
                AlgorithmGraphResolver.Provider parent,
                AlgorithmDependencyDeclaration.Private declaration) {
            return parent;
        }

        @Override
        public AlgorithmGraphResolver.Provider sharedProvider(
                AlgorithmDependencyDeclaration.Shared declaration) {
            throw new AssertionError("Test graph does not declare shared dependencies");
        }
    }

    public static final class StringGenericAlgorithmFactory
            implements SimpleAlgorithmFactory<GenericAlgorithm<String>> {
        @Override
        public GenericAlgorithm<String> apply(Optional<com.fasterxml.jackson.databind.JsonNode> hyperparameters) {
            return new GenericAlgorithm<>() {};
        }

        @Override
        public GenericAlgorithm<String> create(
                ExecutionContext executionContext,
                Optional<LocalStateStorage> localStateStorage,
                Optional<com.fasterxml.jackson.databind.JsonNode> hyperparameters) {
            return new GenericAlgorithm<>() {};
        }
    }

    public static final class GenericAlgorithmFactory<VALUE>
            implements SimpleAlgorithmFactory<GenericAlgorithm<VALUE>> {
        @Override
        public GenericAlgorithm<VALUE> apply(Optional<com.fasterxml.jackson.databind.JsonNode> hyperparameters) {
            return new GenericAlgorithm<>() {};
        }
    }

    public static final class IntegerGenericConsumerFactory
            implements CompositeAlgorithmFactory<Scorer<String>> {
        @Override
        public Scorer<String> create(
                ExecutionContext executionContext,
                Optional<LocalStateStorage> localStateStorage,
                Optional<com.fasterxml.jackson.databind.JsonNode> hyperparameters,
                Map<String, InputStream> parameters,
                AlgorithmDependencies dependencies) {
            dependencies.only("child", new TypeToken<GenericAlgorithm<Integer>>() {});
            return value -> 0.0;
        }
    }

    public static final class LegacyAmbientConsumerFactory
            implements CompositeAlgorithmFactory<DependencyNamesAlgorithm> {
        @Override
        public DependencyNamesAlgorithm apply(
                Optional<com.fasterxml.jackson.databind.JsonNode> hyperparameters,
                Map<String, InputStream> parameters,
                Map<String, AlgorithmInstance<?>> dependencies) {
            return new DependencyNamesAlgorithm(dependencies.keySet());
        }
    }

    public static final class DeclaredDependencyConsumerFactory
            implements CompositeAlgorithmFactory<DependencyNamesAlgorithm> {
        @Override
        public DependencyNamesAlgorithm create(
                ExecutionContext executionContext,
                Optional<LocalStateStorage> localStateStorage,
                Optional<com.fasterxml.jackson.databind.JsonNode> hyperparameters,
                Map<String, InputStream> parameters,
                AlgorithmDependencies dependencies) {
            return new DependencyNamesAlgorithm(dependencies.names());
        }
    }

    public record DependencyNamesAlgorithm(Set<String> dependencyNames) implements Algorithm {
        public DependencyNamesAlgorithm {
            dependencyNames = Set.copyOf(dependencyNames);
        }
    }

    public interface GenericAlgorithm<VALUE> extends Algorithm {
    }

    private static final class NoopAlgorithm implements Algorithm {
    }
}
