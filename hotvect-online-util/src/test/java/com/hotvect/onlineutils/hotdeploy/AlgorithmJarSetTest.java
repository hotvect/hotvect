package com.hotvect.onlineutils.hotdeploy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.algodefinition.AlgorithmDependencies;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algorithms.Scorer;
import com.hotvect.api.execution.ExecutionContext;
import com.hotvect.api.execution.InputSemantic;
import com.hotvect.onlineutils.hotdeploy.util.MalformedAlgorithmException;
import com.hotvect.utils.AlgorithmDefinitionOverrideUtils;
import com.hotvect.utils.AlgorithmDefinitionReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlgorithmJarSetTest {
    @TempDir
    Path tempDir;

    @Test
    void resolvesExactSharedVersionAcrossIsolatedProvidersAndRetainsTheLogicalRootLoader() throws Exception {
        Path rootJar = compileJar(
                "root.jar",
                Map.of("fixtures.root.RootFactory", """
                        package fixtures.root;

                        import com.fasterxml.jackson.databind.JsonNode;
                        import com.google.common.reflect.TypeToken;
                        import com.hotvect.api.algodefinition.AlgorithmDependencies;
                        import com.hotvect.api.algodefinition.common.CompositeAlgorithmFactory;
                        import com.hotvect.api.algodefinition.storage.LocalStateStorage;
                        import com.hotvect.api.algorithms.Scorer;
                        import com.hotvect.api.execution.ExecutionContext;
                        import java.io.InputStream;
                        import java.util.Map;
                        import java.util.Optional;

                        public final class RootFactory implements CompositeAlgorithmFactory<Scorer<String>> {
                            @Override
                            public Scorer<String> create(
                                    ExecutionContext executionContext,
                                    Optional<LocalStateStorage> localStateStorage,
                                    Optional<JsonNode> hyperparameters,
                                    Map<String, InputStream> parameters,
                                    AlgorithmDependencies dependencies) {
                                Scorer<String> child = dependencies.only("child", new TypeToken<Scorer<String>>() {});
                                return child::applyAsDouble;
                            }
                        }
                        """),
                Map.of("root-algorithm-definition.json", """
                        {
                          "algorithm_name": "root",
                          "algorithm_version": "1",
                          "algorithm_factory_classname": "fixtures.root.RootFactory",
                          "dependencies": {"child@1": {"scope": "shared"}}
                        }
                        """));
        Path childJar = compileJar(
                "child.jar",
                Map.of("fixtures.child.ChildFactory", """
                        package fixtures.child;

                        import com.fasterxml.jackson.databind.JsonNode;
                        import com.hotvect.api.algodefinition.common.SimpleAlgorithmFactory;
                        import com.hotvect.api.algorithms.Scorer;
                        import java.util.Optional;

                        public final class ChildFactory implements SimpleAlgorithmFactory<Scorer<String>> {
                            @Override
                            public Scorer<String> apply(Optional<JsonNode> hyperparameter) {
                                return value -> 7.0;
                            }
                        }
                        """),
                Map.of("child-algorithm-definition.json", """
                        {
                          "algorithm_name": "child",
                          "algorithm_version": "1",
                          "algorithm_factory_classname": "fixtures.child.ChildFactory"
                        }
                        """));
        Path otherChildVersionJar = compileJar(
                "child-v2.jar",
                Map.of("fixtures.childv2.ChildFactory", """
                        package fixtures.childv2;

                        import com.fasterxml.jackson.databind.JsonNode;
                        import com.hotvect.api.algodefinition.common.SimpleAlgorithmFactory;
                        import com.hotvect.api.algorithms.Scorer;
                        import java.util.Optional;

                        public final class ChildFactory implements SimpleAlgorithmFactory<Scorer<String>> {
                            @Override
                            public Scorer<String> apply(Optional<JsonNode> hyperparameter) {
                                return value -> 8.0;
                            }
                        }
                        """),
                Map.of("child-algorithm-definition.json", """
                        {
                          "algorithm_name": "child",
                          "algorithm_version": "2",
                          "algorithm_factory_classname": "fixtures.childv2.ChildFactory"
                        }
                        """));

        try (AlgorithmJarSet algorithmJars = new AlgorithmJarSet(
                List.of(rootJar.toFile(), childJar.toFile(), otherChildVersionJar.toFile()),
                getClass().getClassLoader())) {
            ClassLoader rootLoader = algorithmJars.classLoader("root");
            try (AlgorithmGraph<Scorer<String>> graph = algorithmJars.load(
                    algorithmJars.readAlgorithmDefinition("root"),
                    null,
                    AlgorithmDependencies.empty(),
                    ExecutionContext.realtime(InputSemantic.ONLINE),
                    true,
                    false,
                    Optional.empty())) {
                assertSame(
                        algorithmJars.classLoader("root"),
                        graph.algorithm().getClass().getClassLoader());
                assertSame(algorithmJars.classLoader("root"), graph.rootArtifactClassLoader());
                algorithmJars.close();
                assertNotNull(rootLoader.getResource("root-algorithm-definition.json"));
                assertEquals(7.0, graph.algorithm().applyAsDouble("record"));
            }
            assertNull(rootLoader.getResource("root-algorithm-definition.json"));
        }
    }

    @Test
    void resolvesNestedSharedDependenciesWithTheRequestedOnlineSemantics() throws Exception {
        Path rootJar = compileJar(
                "root-with-private-child.jar",
                Map.of(
                        "fixtures.nested.RootFactory", """
                                package fixtures.nested;

                                import com.fasterxml.jackson.databind.JsonNode;
                                import com.google.common.reflect.TypeToken;
                                import com.hotvect.api.algodefinition.AlgorithmDependencies;
                                import com.hotvect.api.algodefinition.common.CompositeAlgorithmFactory;
                                import com.hotvect.api.algodefinition.storage.LocalStateStorage;
                                import com.hotvect.api.algorithms.Scorer;
                                import com.hotvect.api.execution.ExecutionContext;
                                import java.io.InputStream;
                                import java.util.Map;
                                import java.util.Optional;

                                public final class RootFactory implements CompositeAlgorithmFactory<Scorer<String>> {
                                    @Override
                                    public Scorer<String> create(
                                            ExecutionContext executionContext,
                                            Optional<LocalStateStorage> localStateStorage,
                                            Optional<JsonNode> hyperparameters,
                                            Map<String, InputStream> parameters,
                                            AlgorithmDependencies dependencies) {
                                        Scorer<String> child = dependencies.only("private-child", new TypeToken<Scorer<String>>() {});
                                        return child::applyAsDouble;
                                    }
                                }
                                """,
                        "fixtures.nested.PrivateChildFactory", """
                                package fixtures.nested;

                                import com.fasterxml.jackson.databind.JsonNode;
                                import com.google.common.reflect.TypeToken;
                                import com.hotvect.api.algodefinition.AlgorithmDependencies;
                                import com.hotvect.api.algodefinition.common.CompositeAlgorithmFactory;
                                import com.hotvect.api.algodefinition.storage.LocalStateStorage;
                                import com.hotvect.api.algorithms.Scorer;
                                import com.hotvect.api.execution.ExecutionContext;
                                import java.io.InputStream;
                                import java.util.Map;
                                import java.util.Optional;

                                public final class PrivateChildFactory
                                        implements CompositeAlgorithmFactory<Scorer<String>> {
                                    @Override
                                    public Scorer<String> create(
                                            ExecutionContext executionContext,
                                            Optional<LocalStateStorage> localStateStorage,
                                            Optional<JsonNode> hyperparameters,
                                            Map<String, InputStream> parameters,
                                            AlgorithmDependencies dependencies) {
                                        Scorer<String> child = dependencies.only(
                                                "shared-grandchild",
                                                new TypeToken<Scorer<String>>() {});
                                        return child::applyAsDouble;
                                    }
                                }
                                """),
                Map.of(
                        "root-algorithm-definition.json", """
                                {
                                  "algorithm_name": "root",
                                  "algorithm_version": "1",
                                  "algorithm_factory_classname": "fixtures.nested.RootFactory",
                                  "dependencies": {"private-child": {}}
                                }
                                """,
                        "private-child-algorithm-definition.json", """
                                {
                                  "algorithm_name": "private-child",
                                  "algorithm_version": "1",
                                  "algorithm_factory_classname": "fixtures.nested.PrivateChildFactory",
                                  "dependencies": {"shared-grandchild@1": {"scope": "shared"}}
                                }
                                """));
        Path sharedGrandchildJar = compileJar(
                "shared-grandchild.jar",
                Map.of("fixtures.shared.GrandchildFactory", """
                        package fixtures.shared;

                        import com.fasterxml.jackson.databind.JsonNode;
                        import com.hotvect.api.algodefinition.common.SimpleAlgorithmFactory;
                        import com.hotvect.api.algorithms.Scorer;
                        import java.util.Optional;

                        public final class GrandchildFactory implements SimpleAlgorithmFactory<Scorer<String>> {
                            @Override
                            public Scorer<String> apply(Optional<JsonNode> hyperparameter) {
                                return value -> 9.0;
                            }
                        }
                        """),
                Map.of("shared-grandchild-algorithm-definition.json", """
                        {
                          "algorithm_name": "shared-grandchild",
                          "algorithm_version": "1",
                          "algorithm_factory_classname": "fixtures.shared.GrandchildFactory"
                        }
                        """));

        try (AlgorithmJarSet algorithmJars = new AlgorithmJarSet(
                List.of(rootJar.toFile(), sharedGrandchildJar.toFile()),
                getClass().getClassLoader());
             AlgorithmGraph<Scorer<String>> graph = algorithmJars.load(
                     algorithmJars.readAlgorithmDefinition("root"),
                     null,
                     AlgorithmDependencies.empty(),
                     ExecutionContext.realtime(InputSemantic.ONLINE),
                     true,
                     false,
                     Optional.empty())) {
            assertEquals(9.0, graph.algorithm().applyAsDouble("record"));
        }
    }

    @Test
    void treatsSharedDeclarationsAsPrivateWhenLoadedOffline() throws Exception {
        Path rootJar = compileJar(
                "offline-root.jar",
                Map.of(
                        "fixtures.offline.RootFactory", """
                                package fixtures.offline;

                                import com.fasterxml.jackson.databind.JsonNode;
                                import com.google.common.reflect.TypeToken;
                                import com.hotvect.api.algodefinition.AlgorithmDependencies;
                                import com.hotvect.api.algodefinition.common.CompositeAlgorithmFactory;
                                import com.hotvect.api.algodefinition.storage.LocalStateStorage;
                                import com.hotvect.api.algorithms.Scorer;
                                import com.hotvect.api.execution.ExecutionContext;
                                import java.io.InputStream;
                                import java.util.Map;
                                import java.util.Optional;

                                public final class RootFactory implements CompositeAlgorithmFactory<Scorer<String>> {
                                    @Override
                                    public Scorer<String> create(
                                            ExecutionContext executionContext,
                                            Optional<LocalStateStorage> localStateStorage,
                                            Optional<JsonNode> hyperparameters,
                                            Map<String, InputStream> parameters,
                                            AlgorithmDependencies dependencies) {
                                        Scorer<String> child = dependencies.only("child", new TypeToken<Scorer<String>>() {});
                                        return child::applyAsDouble;
                                    }
                                }
                                """,
                        "fixtures.offline.ChildFactory", """
                                package fixtures.offline;

                                import com.fasterxml.jackson.databind.JsonNode;
                                import com.hotvect.api.algodefinition.common.SimpleAlgorithmFactory;
                                import com.hotvect.api.algorithms.Scorer;
                                import java.util.Optional;

                                public final class ChildFactory implements SimpleAlgorithmFactory<Scorer<String>> {
                                    @Override
                                    public Scorer<String> apply(Optional<JsonNode> hyperparameters) {
                                        double score = hyperparameters.orElseThrow().path("threshold").asDouble();
                                        return value -> score;
                                    }
                                }
                                """),
                Map.of(
                        "root-algorithm-definition.json", """
                                {
                                  "algorithm_name": "root",
                                  "algorithm_version": "1",
                                  "algorithm_factory_classname": "fixtures.offline.RootFactory",
                                  "dependencies": {
                                    "child@1": {"scope": "shared"}
                                  }
                                }
                                """,
                        "child-algorithm-definition.json", """
                                {
                                  "algorithm_name": "child",
                                  "algorithm_version": "1",
                                  "algorithm_factory_classname": "fixtures.offline.ChildFactory"
                                }
                                """));

        try (AlgorithmJarSet algorithmJars = new AlgorithmJarSet(
                List.of(rootJar.toFile()),
                getClass().getClassLoader())) {
            AlgorithmDefinition base = algorithmJars.readAlgorithmDefinition("root");
            AlgorithmDefinition effective = new AlgorithmDefinitionReader(
                    AlgorithmDefinitionReader.DependencyResolution.OFFLINE).parse(
                            AlgorithmDefinitionOverrideUtils.applyOverride(
                                    base.rawAlgorithmDefinition(),
                                    new ObjectMapper().readTree("""
                                            {
                                              "dependencies": {
                                                "child": {
                                                  "algorithm_parameters": {"threshold": 7.0}
                                                }
                                              }
                                            }
                                            """),
                                    AlgorithmDefinitionReader.DependencyResolution.OFFLINE));
            try (AlgorithmGraph<Scorer<String>> graph = algorithmJars.load(
                    effective,
                    null,
                    AlgorithmDependencies.empty(),
                    ExecutionContext.batch(InputSemantic.OFFLINE),
                    true,
                    false,
                    Optional.empty())) {
                assertEquals(7.0, graph.algorithm().applyAsDouble("record"));
            }
        }
    }

    @Test
    void suppliesSelectedParametersToASharedAlgorithmFromAnotherArtifact() throws Exception {
        Path rootJar = compileJar(
                "root.jar",
                Map.of("fixtures.root.RootFactory", """
                        package fixtures.root;

                        import com.fasterxml.jackson.databind.JsonNode;
                        import com.google.common.reflect.TypeToken;
                        import com.hotvect.api.algodefinition.AlgorithmDependencies;
                        import com.hotvect.api.algodefinition.common.CompositeAlgorithmFactory;
                        import com.hotvect.api.algodefinition.storage.LocalStateStorage;
                        import com.hotvect.api.algorithms.Scorer;
                        import com.hotvect.api.execution.ExecutionContext;
                        import java.io.InputStream;
                        import java.util.Map;
                        import java.util.Optional;

                        public final class RootFactory implements CompositeAlgorithmFactory<Scorer<String>> {
                            @Override
                            public Scorer<String> create(
                                    ExecutionContext executionContext,
                                    Optional<LocalStateStorage> localStateStorage,
                                    Optional<JsonNode> hyperparameters,
                                    Map<String, InputStream> parameters,
                                    AlgorithmDependencies dependencies) {
                                Scorer<String> child = dependencies.only("child", new TypeToken<Scorer<String>>() {});
                                return child::applyAsDouble;
                            }
                        }
                        """),
                Map.of("root-algorithm-definition.json", """
                        {
                          "algorithm_name": "root",
                          "algorithm_version": "1",
                          "algorithm_factory_classname": "fixtures.root.RootFactory",
                          "dependencies": {"child@1": {"scope": "shared"}}
                        }
                        """));
        Path childJar = compileJar(
                "child.jar",
                Map.of("fixtures.child.ChildFactory", """
                        package fixtures.child;

                        import com.fasterxml.jackson.databind.JsonNode;
                        import com.hotvect.api.algodefinition.AlgorithmDependencies;
                        import com.hotvect.api.algodefinition.common.CompositeAlgorithmFactory;
                        import com.hotvect.api.algodefinition.storage.LocalStateStorage;
                        import com.hotvect.api.algorithms.Scorer;
                        import com.hotvect.api.execution.ExecutionContext;
                        import java.io.IOException;
                        import java.io.InputStream;
                        import java.nio.charset.StandardCharsets;
                        import java.util.Map;
                        import java.util.Optional;

                        public final class ChildFactory implements CompositeAlgorithmFactory<Scorer<String>> {
                            @Override
                            public Scorer<String> create(
                                    ExecutionContext executionContext,
                                    Optional<LocalStateStorage> localStateStorage,
                                    Optional<JsonNode> hyperparameters,
                                    Map<String, InputStream> parameters,
                                    AlgorithmDependencies dependencies) {
                                try {
                                    double score = Double.parseDouble(new String(
                                            parameters.get("model.parameter").readAllBytes(),
                                            StandardCharsets.UTF_8));
                                    return value -> score;
                                } catch (IOException error) {
                                    throw new RuntimeException(error);
                                }
                            }
                        }
                        """),
                Map.of("child-algorithm-definition.json", """
                        {
                          "algorithm_name": "child",
                          "algorithm_version": "1",
                          "algorithm_factory_classname": "fixtures.child.ChildFactory"
                        }
                        """));
        Path parameterArchive = parameterArchive("parameters.zip", Map.of(
                "root/algorithm-parameters.json", """
                        {
                          "algorithm_name": "root",
                          "algorithm_version": "1",
                          "parameter_id": "root-parameters",
                          "ran_at": "2026-08-15T00:00:00Z"
                        }
                        """,
                "child/algorithm-parameters.json", """
                        {
                          "algorithm_name": "child",
                          "algorithm_version": "1",
                          "parameter_id": "child-parameters",
                          "ran_at": "2026-08-15T00:00:00Z"
                        }
                        """,
                "child/model.parameter", "17.25"));

        try (AlgorithmJarSet algorithmJars = new AlgorithmJarSet(
                List.of(rootJar.toFile(), childJar.toFile()),
                getClass().getClassLoader())) {
            try (AlgorithmGraph<Scorer<String>> graph = algorithmJars.load(
                    algorithmJars.readAlgorithmDefinition("root"),
                    parameterArchive.toFile(),
                    AlgorithmDependencies.empty(),
                    ExecutionContext.realtime(InputSemantic.ONLINE),
                    true,
                    false,
                    Optional.empty())) {
                assertSame(
                        graph.rootArtifactClassLoader(),
                        graph.algorithm().getClass().getClassLoader());
                assertEquals(17.25, graph.algorithm().applyAsDouble("record"));
            }
        }
    }

    @Test
    void keepsPrivateChildrenInTheirImmediateParentArtifact() throws Exception {
        Path canonicalChildJar = compileJar(
                "a-canonical-child.jar",
                Map.of("fixtures.canonical.ChildFactory", """
                        package fixtures.canonical;

                        import com.fasterxml.jackson.databind.JsonNode;
                        import com.hotvect.api.algodefinition.common.SimpleAlgorithmFactory;
                        import com.hotvect.api.algorithms.Scorer;
                        import java.util.Optional;

                        public final class ChildFactory implements SimpleAlgorithmFactory<Scorer<String>> {
                            @Override
                            public Scorer<String> apply(Optional<JsonNode> hyperparameter) {
                                return value -> 2.0;
                            }
                        }
                        """),
                Map.of("child-algorithm-definition.json", """
                        {
                          "algorithm_name": "child",
                          "algorithm_version": "shared",
                          "algorithm_factory_classname": "fixtures.canonical.ChildFactory"
                        }
                        """));
        Path rootJar = compileJar(
                "z-private-root.jar",
                Map.of(
                        "fixtures.privateRoot.RootFactory", """
                                package fixtures.privateRoot;

                                import com.fasterxml.jackson.databind.JsonNode;
                                import com.google.common.reflect.TypeToken;
                                import com.hotvect.api.algodefinition.AlgorithmDependencies;
                                import com.hotvect.api.algodefinition.common.CompositeAlgorithmFactory;
                                import com.hotvect.api.algodefinition.storage.LocalStateStorage;
                                import com.hotvect.api.algorithms.Scorer;
                                import com.hotvect.api.execution.ExecutionContext;
                                import java.io.InputStream;
                                import java.util.Map;
                                import java.util.Optional;

                                public final class RootFactory implements CompositeAlgorithmFactory<Scorer<String>> {
                                    @Override
                                    public Scorer<String> create(
                                            ExecutionContext executionContext,
                                            Optional<LocalStateStorage> localStateStorage,
                                            Optional<JsonNode> hyperparameters,
                                            Map<String, InputStream> parameters,
                                            AlgorithmDependencies dependencies) {
                                        Scorer<String> child = dependencies.only("child", new TypeToken<Scorer<String>>() {});
                                        return child::applyAsDouble;
                                    }
                                }
                                """,
                        "fixtures.privateChild.ChildFactory", """
                                package fixtures.privateChild;

                                import com.fasterxml.jackson.databind.JsonNode;
                                import com.hotvect.api.algodefinition.common.SimpleAlgorithmFactory;
                                import com.hotvect.api.algorithms.Scorer;
                                import java.util.Optional;

                                public final class ChildFactory implements SimpleAlgorithmFactory<Scorer<String>> {
                                    @Override
                                    public Scorer<String> apply(Optional<JsonNode> hyperparameter) {
                                        return value -> 1.0;
                                    }
                                }
                                """),
                Map.of(
                        "root-algorithm-definition.json", """
                                {
                                  "algorithm_name": "root",
                                  "algorithm_version": "1",
                                  "algorithm_factory_classname": "fixtures.privateRoot.RootFactory",
                                  "dependencies": {"child": {}}
                                }
                                """,
                        "child-algorithm-definition.json", """
                                {
                                  "algorithm_name": "child",
                                  "algorithm_version": "private",
                                  "algorithm_factory_classname": "fixtures.privateChild.ChildFactory"
                                }
                                """));

        try (AlgorithmJarSet algorithmJars = new AlgorithmJarSet(
                List.of(rootJar.toFile(), canonicalChildJar.toFile()),
                getClass().getClassLoader())) {
            try (AlgorithmGraph<Scorer<String>> graph = algorithmJars.load(
                    algorithmJars.readAlgorithmDefinition("root"),
                    null,
                    AlgorithmDependencies.empty(),
                    ExecutionContext.realtime(InputSemantic.ONLINE),
                    true,
                    false,
                    Optional.empty())) {
                assertSame(
                        graph.rootArtifactClassLoader(),
                        graph.algorithm().getClass().getClassLoader());
                assertEquals(1.0, graph.algorithm().applyAsDouble("record"));
            }
        }
    }

    @Test
    void canonicalizesEqualCommittedProvidersAndRejectsConflictingVersions() throws Exception {
        Path canonical = compileJar(
                "a-canonical.jar",
                Map.of(),
                Map.of("child-algorithm-definition.json", """
                        {
                          "algorithm_name": "child",
                          "algorithm_version": "1",
                          "provider": "canonical",
                          "algorithm_factory_classname": "fixtures.CanonicalFactory"
                        }
                        """));
        Path alias = compileJar(
                "z-alias.jar",
                Map.of(),
                Map.of("child-algorithm-definition.json", """
                        {
                          "algorithm_name": "child",
                          "algorithm_version": "1",
                          "provider": "alias",
                          "algorithm_factory_classname": "fixtures.AliasFactory"
                        }
                        """));

        try (AlgorithmJarSet algorithmJars = new AlgorithmJarSet(
                List.of(alias.toFile(), canonical.toFile()),
                getClass().getClassLoader())) {
            assertEquals(
                    "canonical",
                    algorithmJars.readAlgorithmDefinition("child")
                            .rawAlgorithmDefinition()
                            .path("provider")
                            .asText());
        }

        Path conflicting = compileJar(
                "z-conflicting.jar",
                Map.of(),
                Map.of("child-algorithm-definition.json", """
                        {
                          "algorithm_name": "child",
                          "algorithm_version": "2",
                          "algorithm_factory_classname": "fixtures.ConflictingFactory"
                        }
                        """));

        try (AlgorithmJarSet algorithmJars = new AlgorithmJarSet(
                List.of(canonical.toFile(), conflicting.toFile()),
                getClass().getClassLoader())) {
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> algorithmJars.readAlgorithmDefinition("child"));

            assertTrue(error.getMessage().contains("conflicting provider identities"));
            assertTrue(error.getMessage().contains("child@1"));
            assertTrue(error.getMessage().contains("child@2"));
        }
    }

    @Test
    void rejectsOfflineOnlySyntaxInCommittedDefinitions() throws Exception {
        Path hyperparameterized = compileJar(
                "hyperparameterized.jar",
                Map.of(),
                Map.of("child-algorithm-definition.json", """
                        {
                          "algorithm_name": "child",
                          "algorithm_version": "1",
                          "hyperparameter_version": "candidate-a",
                          "algorithm_factory_classname": "fixtures.ChildFactory"
                        }
                        """));

        MalformedAlgorithmException hyperparameterError = assertThrows(
                MalformedAlgorithmException.class,
                () -> new AlgorithmJarSet(List.of(hyperparameterized.toFile()), getClass().getClassLoader()));
        assertTrue(hyperparameterError.getCause().getMessage()
                .contains("must not contain offline-only hyperparameter_version"));

        Path versionedPrivate = compileJar(
                "versioned-private.jar",
                Map.of(),
                Map.of("root-algorithm-definition.json", """
                        {
                          "algorithm_name": "root",
                          "algorithm_version": "1",
                          "algorithm_factory_classname": "fixtures.RootFactory",
                          "dependencies": {"child@3": {}}
                        }
                        """));

        MalformedAlgorithmException dependencyError = assertThrows(
                MalformedAlgorithmException.class,
                () -> new AlgorithmJarSet(List.of(versionedPrivate.toFile()), getClass().getClassLoader()));
        assertEquals(
                "Private dependency child must not declare an algorithm version",
                dependencyError.getCause().getMessage());
    }

    @Test
    void rejectsSiblingOwnedDomainTypesBeforeTheCompositeIsConstructed() throws Exception {
        Path rootJar = compileJar(
                "root-domain.jar",
                Map.of(
                        "fixtures.root.RootFactory", """
                                package fixtures.root;

                                import com.fasterxml.jackson.databind.JsonNode;
                                import com.hotvect.api.algodefinition.AlgorithmDependencies;
                                import com.hotvect.api.algodefinition.common.CompositeAlgorithmFactory;
                                import com.hotvect.api.algodefinition.storage.LocalStateStorage;
                                import com.hotvect.api.algorithms.Scorer;
                                import com.hotvect.api.execution.ExecutionContext;
                                import java.io.InputStream;
                                import java.util.Map;
                                import java.util.Optional;

                                public final class RootFactory implements CompositeAlgorithmFactory<Scorer<Object>> {
                                    @Override
                                    public Scorer<Object> create(
                                            ExecutionContext executionContext,
                                            Optional<LocalStateStorage> localStateStorage,
                                            Optional<JsonNode> hyperparameters,
                                            Map<String, InputStream> parameters,
                                            AlgorithmDependencies dependencies) {
                                        return value -> 0.0;
                                    }
                                }
                                """,
                        "fixtures.domain.Model", """
                                package fixtures.domain;

                                public record Model(String value) {}
                                """),
                Map.of("root-algorithm-definition.json", """
                        {
                          "algorithm_name": "root",
                          "algorithm_version": "1",
                          "algorithm_factory_classname": "fixtures.root.RootFactory",
                          "dependencies": {"child@1": {"scope": "shared"}}
                        }
                        """));
        Path childJar = compileJar(
                "child-domain.jar",
                Map.of(
                        "fixtures.child.ChildFactory", """
                                package fixtures.child;

                                import com.fasterxml.jackson.databind.JsonNode;
                                import com.hotvect.api.algodefinition.common.SimpleAlgorithmFactory;
                                import com.hotvect.api.algorithms.Scorer;
                                import fixtures.domain.Model;
                                import java.util.Optional;

                                public final class ChildFactory implements SimpleAlgorithmFactory<Scorer<Model>> {
                                    @Override
                                    public Scorer<Model> apply(Optional<JsonNode> hyperparameter) {
                                        return value -> 1.0;
                                    }
                                }
                                """,
                        "fixtures.domain.Model", """
                                package fixtures.domain;

                                public record Model(String value) {}
                                """),
                Map.of("child-algorithm-definition.json", """
                        {
                          "algorithm_name": "child",
                          "algorithm_version": "1",
                          "algorithm_factory_classname": "fixtures.child.ChildFactory"
                        }
                        """));

        try (AlgorithmJarSet algorithmJars = new AlgorithmJarSet(
                List.of(rootJar.toFile(), childJar.toFile()),
                getClass().getClassLoader())) {
            IllegalStateException error = assertThrows(
                    IllegalStateException.class,
                    () -> {
                        try (AlgorithmGraph<?> ignored = algorithmJars.load(
                                algorithmJars.readAlgorithmDefinition("root"),
                                null,
                                AlgorithmDependencies.empty(),
                                ExecutionContext.realtime(InputSemantic.ONLINE),
                                true,
                                false,
                                Optional.empty())) {
                            throw new AssertionError("Expected domain model boundary validation to fail");
                        }
                    });

            assertTrue(error.getMessage().contains("fixtures.domain.Model"));
        }
    }

    @Test
    void requiresAnExplicitParentClassLoader() {
        NullPointerException error = assertThrows(
                NullPointerException.class,
                () -> new AlgorithmJarSet(List.of(), null));

        assertEquals("parentClassLoader must not be null", error.getMessage());
    }

    private Path parameterArchive(String archiveName, Map<String, String> entries) throws IOException {
        Path archive = tempDir.resolve(archiveName);
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (var entry : new LinkedHashMap<>(entries).entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return archive;
    }

    private Path compileJar(
            String jarName,
            Map<String, String> sources,
            Map<String, String> resources) throws IOException {
        Path sourceRoot = tempDir.resolve(jarName + "-source");
        Path classes = sourceRoot.resolve("classes");
        Files.createDirectories(classes);
        List<String> compilerArguments = new java.util.ArrayList<>(List.of(
                "-classpath",
                System.getProperty("java.class.path"),
                "-d",
                classes.toString()));
        for (var source : new LinkedHashMap<>(sources).entrySet()) {
            Path sourceFile = sourceRoot.resolve(source.getKey().replace('.', '/') + ".java");
            Files.createDirectories(sourceFile.getParent());
            Files.writeString(sourceFile, source.getValue());
            compilerArguments.add(sourceFile.toString());
        }
        if (!sources.isEmpty()) {
            int exitCode = ToolProvider.getSystemJavaCompiler().run(
                    null,
                    null,
                    null,
                    compilerArguments.toArray(String[]::new));
            assertEquals(0, exitCode, "fixture compilation failed");
        }

        Path jar = tempDir.resolve(jarName);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            try (var files = Files.walk(classes)) {
                for (Path classFile : files.filter(Files::isRegularFile).toList()) {
                    output.putNextEntry(new JarEntry(classes.relativize(classFile).toString()));
                    Files.copy(classFile, output);
                    output.closeEntry();
                }
            }
            for (var resource : new LinkedHashMap<>(resources).entrySet()) {
                output.putNextEntry(new JarEntry(resource.getKey()));
                output.write(resource.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return jar;
    }
}
