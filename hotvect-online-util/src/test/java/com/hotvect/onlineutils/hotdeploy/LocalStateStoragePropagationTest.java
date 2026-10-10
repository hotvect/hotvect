package com.hotvect.onlineutils.hotdeploy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.google.common.collect.ImmutableMap;
import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.algodefinition.AlgorithmDependencies;
import com.hotvect.api.algodefinition.AlgorithmId;
import com.hotvect.api.algodefinition.common.CompositeAlgorithmFactory;
import com.hotvect.api.algodefinition.common.NonCompositeAlgorithmFactory;
import com.hotvect.api.algodefinition.common.SimpleAlgorithmFactory;
import com.hotvect.api.algodefinition.ranking.RankingTransformer;
import com.hotvect.api.algodefinition.ranking.RankingTransformerFactory;
import com.hotvect.api.algodefinition.state.NonCompositeStateFactory;
import com.hotvect.api.algodefinition.storage.LocalStateStorage;
import com.hotvect.api.algorithms.Algorithm;
import com.hotvect.api.data.Namespace;
import com.hotvect.api.data.common.NamespacedRecord;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.execution.ExecutionContext;
import com.hotvect.api.execution.InputSemantic;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalStateStoragePropagationTest {
    @TempDir
    Path tempDir;

    @Test
    void allAlgorithmFactoryKindsCanAllocateLocalStateStorage() throws Exception {
        Path stateRoot = tempDir.resolve("states");
        AlgorithmInstanceFactory factory = new AlgorithmInstanceFactory(
                Thread.currentThread().getContextClassLoader(),
                new AlgorithmInstanceFactory.Options(
                        InputSemantic.ONLINE,
                        true,
                        false,
                        Optional.of(stateRoot)));

        try (AlgorithmGraph<StorageAlgorithm> state = load(factory, definition("state", StorageStateFactory.class, null));
                AlgorithmGraph<StorageAlgorithm> nonComposite = load(
                        factory,
                        definition("non-composite", StorageNonCompositeFactory.class, NoopTransformerFactory.class));
                AlgorithmGraph<StorageAlgorithm> composite = load(
                        factory,
                        definition("composite", StorageCompositeFactory.class, null));
                AlgorithmGraph<StorageAlgorithm> simple = load(
                        factory,
                        definition("simple", StorageSimpleFactory.class, null))) {
            List<Path> allocatedDirectories = List.of(
                    state.algorithm().stateDirectory(),
                    nonComposite.algorithm().stateDirectory(),
                    composite.algorithm().stateDirectory(),
                    simple.algorithm().stateDirectory());

            assertEquals(4, Set.copyOf(allocatedDirectories).size());
            assertEquals(
                    Set.of(stateRoot.toAbsolutePath()),
                    allocatedDirectories.stream().map(Path::getParent).collect(java.util.stream.Collectors.toSet()));
        }
    }

    private static AlgorithmGraph<StorageAlgorithm> load(
            AlgorithmInstanceFactory factory,
            AlgorithmDefinition definition) {
        return factory.loadGraph(
                definition,
                null,
                AlgorithmDependencies.empty(),
                ExecutionContext.realtime(InputSemantic.ONLINE));
    }

    private static AlgorithmDefinition definition(
            String name,
            Class<?> algorithmFactory,
            Class<?> transformerFactory) {
        return new AlgorithmDefinition(
                JsonNodeFactory.instance.objectNode(),
                new AlgorithmId(name, "1"),
                ImmutableMap.of(),
                null,
                null,
                transformerFactory == null ? null : transformerFactory.getName(),
                null,
                null,
                null,
                algorithmFactory.getName(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    public record StorageAlgorithm(Path stateDirectory) implements Algorithm {
    }

    public static final class StorageStateFactory implements NonCompositeStateFactory<StorageAlgorithm> {
        @Override
        public StorageAlgorithm apply(Map<String, InputStream> parameters, Optional<JsonNode> hyperparameter) {
            throw new AssertionError("Legacy state factory overload should not be used");
        }

        @Override
        public StorageAlgorithm create(
                ExecutionContext executionContext,
                Optional<LocalStateStorage> localStateStorage,
                Map<String, InputStream> parameters,
                Optional<JsonNode> hyperparameter) {
            return new StorageAlgorithm(localStateStorage.orElseThrow().allocateDirectory());
        }
    }

    public static final class StorageNonCompositeFactory
            implements NonCompositeAlgorithmFactory<Object, StorageAlgorithm> {
        @Override
        public StorageAlgorithm apply(
                Object dependency,
                Map<String, InputStream> parameters,
                Optional<JsonNode> hyperparameter) {
            throw new AssertionError("Legacy non-composite factory overload should not be used");
        }

        @Override
        public StorageAlgorithm create(
                ExecutionContext executionContext,
                Optional<LocalStateStorage> localStateStorage,
                Object dependency,
                Map<String, InputStream> parameters,
                Optional<JsonNode> hyperparameter) {
            return new StorageAlgorithm(localStateStorage.orElseThrow().allocateDirectory());
        }
    }

    public static final class StorageCompositeFactory implements CompositeAlgorithmFactory<StorageAlgorithm> {
        @Override
        public StorageAlgorithm create(
                ExecutionContext executionContext,
                Optional<LocalStateStorage> localStateStorage,
                Optional<JsonNode> hyperparameters,
                Map<String, InputStream> parameters,
                AlgorithmDependencies algorithmDependencies) {
            return new StorageAlgorithm(localStateStorage.orElseThrow().allocateDirectory());
        }
    }

    public static final class StorageSimpleFactory implements SimpleAlgorithmFactory<StorageAlgorithm> {
        @Override
        public StorageAlgorithm apply(Optional<JsonNode> hyperparameter) {
            throw new AssertionError("Legacy simple factory overload should not be used");
        }

        @Override
        public StorageAlgorithm create(
                ExecutionContext executionContext,
                Optional<LocalStateStorage> localStateStorage,
                Optional<JsonNode> hyperparameter) {
            return new StorageAlgorithm(localStateStorage.orElseThrow().allocateDirectory());
        }
    }

    public static final class NoopTransformerFactory implements RankingTransformerFactory<String, String> {
        @Override
        public RankingTransformer<String, String> apply(
                Optional<JsonNode> hyperparameter,
                Map<String, InputStream> parameter) {
            return new NoopTransformer();
        }
    }

    private static final class NoopTransformer implements RankingTransformer<String, String> {
        @Override
        public List<NamespacedRecord<Namespace, Object>> apply(RankingRequest<String, String> rankingRequest) {
            return List.of();
        }

        @Override
        public SortedSet<? extends Namespace> getUsedFeatures() {
            return Collections.emptySortedSet();
        }
    }
}
