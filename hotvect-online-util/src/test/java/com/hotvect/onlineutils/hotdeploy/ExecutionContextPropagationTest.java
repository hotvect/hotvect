package com.hotvect.onlineutils.hotdeploy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.google.common.collect.ImmutableMap;
import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.algodefinition.AlgorithmDependencies;
import com.hotvect.api.algodefinition.AlgorithmId;
import com.hotvect.api.algodefinition.common.CompositeVectorizerFactory;
import com.hotvect.api.algodefinition.common.NonCompositeAlgorithmFactory;
import com.hotvect.api.algodefinition.ranking.RankingTransformer;
import com.hotvect.api.algodefinition.ranking.RankingTransformerFactory;
import com.hotvect.api.algodefinition.ranking.RankingVectorizer;
import com.hotvect.api.algodefinition.ranking.RankingVectorizerFactory;
import com.hotvect.api.algodefinition.state.NonCompositeStateFactory;
import com.hotvect.api.algodefinition.storage.LocalStateStorage;
import com.hotvect.api.algodefinition.topk.SimpleTopKFactory;
import com.hotvect.api.algodefinition.topk.TopKFactory;
import com.hotvect.api.algorithms.Algorithm;
import com.hotvect.api.algorithms.TopK;
import com.hotvect.api.data.Namespace;
import com.hotvect.api.data.SparseVector;
import com.hotvect.api.data.common.NamespacedRecord;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.topk.TopKRequest;
import com.hotvect.api.data.topk.TopKResponse;
import com.hotvect.api.execution.ExecutionContext;
import com.hotvect.api.execution.InputSemantic;
import com.hotvect.api.execution.WorkloadMode;
import com.hotvect.api.transformation.CompositeTransformerFactory;
import com.hotvect.onlineutils.hotdeploy.util.MalformedAlgorithmException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExecutionContextPropagationTest {
    private static final ExecutionContext ONLINE_CONTEXT = ExecutionContext.realtime(InputSemantic.ONLINE);
    private static final ExecutionContext OFFLINE_CONTEXT = ExecutionContext.of(WorkloadMode.BATCH, InputSemantic.OFFLINE);

    @Test
    void stateFactoryReceivesExecutionContext() throws Exception {
        try (AlgorithmGraph<ContextAwareState> graph = new AlgorithmInstanceFactory(
                Thread.currentThread().getContextClassLoader(),
                new AlgorithmInstanceFactory.Options(OFFLINE_CONTEXT.inputSemantic(), true, false, Optional.empty())
        ).loadGraph(
                stateDefinition(),
                null,
                AlgorithmDependencies.empty(),
                OFFLINE_CONTEXT)) {
            assertEquals(OFFLINE_CONTEXT, graph.algorithm().executionContext());
        }
    }

    @Test
    void stateFactoryCanAllocatePrivateLocalStateDirectory(@TempDir Path stateRoot) throws Exception {
        try (AlgorithmGraph<StorageAwareState> graph = new AlgorithmInstanceFactory(
                Thread.currentThread().getContextClassLoader(),
                new AlgorithmInstanceFactory.Options(ONLINE_CONTEXT.inputSemantic(), true, false, Optional.of(stateRoot))
        ).loadGraph(
                storageAwareStateDefinition(),
                null,
                AlgorithmDependencies.empty(),
                ONLINE_CONTEXT)) {
            assertEquals(stateRoot.toAbsolutePath(), graph.algorithm().stateDirectory().getParent());
        }
    }

    @Test
    void defaultsLocalStateRootToTheSystemTemporaryDirectory() {
        assertEquals(
                Optional.of(Path.of(System.getProperty("java.io.tmpdir"), "algorithm-state").toAbsolutePath()),
                new AlgorithmInstanceFactory.Options(ONLINE_CONTEXT.inputSemantic(), true, false, Optional.empty()).localStateRoot());
    }

    @Test
    void rankingTransformerFactoryReceivesExecutionContext() throws Exception {
        try (AlgorithmGraph<ContextAwareDependencyAlgorithm> graph = loadDependencyAlgorithm(
                ONLINE_CONTEXT,
                ContextAwareRankingTransformerFactory.class.getName(),
                null)) {
            assertEquals(ONLINE_CONTEXT, graph.algorithm().dependencyExecutionContext());
        }
    }

    @Test
    void rejectsAnExecutionContextThatChangesTheInputSemantic() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new AlgorithmInstanceFactory(
                        Thread.currentThread().getContextClassLoader(),
                        new AlgorithmInstanceFactory.Options(
                                ONLINE_CONTEXT.inputSemantic(), true, false, Optional.empty()))
                        .loadGraph(
                                stateDefinition(),
                                null,
                                AlgorithmDependencies.empty(),
                                OFFLINE_CONTEXT));

        assertEquals(
                "Execution context must retain input semantic ONLINE but was OFFLINE",
                error.getMessage());
    }

    @Test
    void rejectsLegacyTwoArgumentAlgorithmFactories() {
        MalformedAlgorithmException error = assertThrows(
                MalformedAlgorithmException.class,
                () -> new AlgorithmInstanceFactory(
                        Thread.currentThread().getContextClassLoader(),
                        new AlgorithmInstanceFactory.Options(ONLINE_CONTEXT.inputSemantic(), true, false, Optional.empty()))
                        .loadGraph(
                                legacyTwoArgumentFactoryDefinition(),
                                null,
                                AlgorithmDependencies.empty(),
                                ONLINE_CONTEXT));

        assertEquals("Legacy two-argument factories are unsupported", error.getCause().getMessage());
    }

    @Test
    void compositeTransformerFactoryReceivesExecutionContext() throws Exception {
        try (AlgorithmGraph<ContextAwareDependencyAlgorithm> graph = loadDependencyAlgorithm(
                OFFLINE_CONTEXT,
                ContextAwareCompositeTransformerFactory.class.getName(),
                null)) {
            assertEquals(OFFLINE_CONTEXT, graph.algorithm().dependencyExecutionContext());
        }
    }

    @Test
    void rankingVectorizerFactoryReceivesExecutionContext() throws Exception {
        try (AlgorithmGraph<ContextAwareDependencyAlgorithm> graph = loadDependencyAlgorithm(
                ONLINE_CONTEXT,
                null,
                ContextAwareRankingVectorizerFactory.class.getName())) {
            assertEquals(ONLINE_CONTEXT, graph.algorithm().dependencyExecutionContext());
        }
    }

    @Test
    void compositeVectorizerFactoryReceivesExecutionContext() throws Exception {
        try (AlgorithmGraph<ContextAwareDependencyAlgorithm> graph = loadDependencyAlgorithm(
                OFFLINE_CONTEXT,
                null,
                ContextAwareCompositeVectorizerFactory.class.getName())) {
            assertEquals(OFFLINE_CONTEXT, graph.algorithm().dependencyExecutionContext());
        }
    }

    @Test
    void topKFactoryReceivesExecutionContext() throws Exception {
        try (AlgorithmGraph<ContextAwareTopK> graph = new AlgorithmInstanceFactory(
                Thread.currentThread().getContextClassLoader(),
                new AlgorithmInstanceFactory.Options(ONLINE_CONTEXT.inputSemantic(), true, false, Optional.empty())
        ).loadGraph(
                topKDefinition(),
                null,
                AlgorithmDependencies.empty(),
                ONLINE_CONTEXT)) {
            assertEquals(ONLINE_CONTEXT, graph.algorithm().executionContext());
        }
    }

    @Test
    void simpleTopKFactoryCanBeLoaded() throws Exception {
        try (AlgorithmGraph<SimpleTopKAlgorithm> graph = new AlgorithmInstanceFactory(
                Thread.currentThread().getContextClassLoader(),
                new AlgorithmInstanceFactory.Options(OFFLINE_CONTEXT.inputSemantic(), true, false, Optional.empty())
        ).loadGraph(
                simpleTopKDefinition(),
                null,
                AlgorithmDependencies.empty(),
                OFFLINE_CONTEXT)) {
            assertEquals("loaded", graph.algorithm().value());
        }
    }

    private static AlgorithmGraph<ContextAwareDependencyAlgorithm> loadDependencyAlgorithm(
            ExecutionContext executionContext,
            String transformerFactoryName,
            String vectorizerFactoryName
    ) throws Exception {
        return new AlgorithmInstanceFactory(
                Thread.currentThread().getContextClassLoader(),
                new AlgorithmInstanceFactory.Options(executionContext.inputSemantic(), true, false, Optional.empty())
        ).loadGraph(
                dependencyAlgorithmDefinition(transformerFactoryName, vectorizerFactoryName),
                null,
                AlgorithmDependencies.empty(),
                executionContext);
    }

    private static AlgorithmDefinition stateDefinition() {
        return new AlgorithmDefinition(
                JsonNodeFactory.instance.objectNode(),
                new AlgorithmId("context-aware-state", "1.0.0"),
                ImmutableMap.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                ContextAwareStateFactory.class.getName(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()
        );
    }

    private static AlgorithmDefinition storageAwareStateDefinition() {
        AlgorithmDefinition base = stateDefinition();
        return new AlgorithmDefinition(
                base.rawAlgorithmDefinition(),
                new AlgorithmId("storage-aware-state", "1.0.0"),
                base.dependencyDeclarations(),
                base.generateStateFactoryName(),
                base.decoderFactoryName(),
                base.transformerFactoryName(),
                base.vectorizerFactoryName(),
                base.rewardFunctionFactoryName(),
                base.encoderFactoryName(),
                StorageAwareStateFactory.class.getName(),
                base.transformerParameter(),
                base.vectorizerParameter(),
                base.trainDecoderParameter(),
                base.testDecoderParameter(),
                base.algorithmParameter());
    }

    private static AlgorithmDefinition dependencyAlgorithmDefinition(
            String transformerFactoryName,
            String vectorizerFactoryName
    ) {
        return new AlgorithmDefinition(
                JsonNodeFactory.instance.objectNode(),
                new AlgorithmId("context-aware-dependency", "1.0.0"),
                ImmutableMap.of(),
                null,
                null,
                transformerFactoryName,
                vectorizerFactoryName,
                null,
                null,
                ContextAwareDependencyAlgorithmFactory.class.getName(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()
        );
    }

    private static AlgorithmDefinition legacyTwoArgumentFactoryDefinition() {
        return new AlgorithmDefinition(
                JsonNodeFactory.instance.objectNode(),
                new AlgorithmId("legacy-two-argument-factory", "1.0.0"),
                ImmutableMap.of(),
                null,
                null,
                ContextAwareRankingTransformerFactory.class.getName(),
                null,
                null,
                null,
                LegacyTwoArgumentAlgorithmFactory.class.getName(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()
        );
    }

    private static AlgorithmDefinition topKDefinition() {
        return new AlgorithmDefinition(
                JsonNodeFactory.instance.objectNode(),
                new AlgorithmId("context-aware-topk", "1.0.0"),
                ImmutableMap.of(),
                null,
                null,
                ContextAwareRankingTransformerFactory.class.getName(),
                null,
                null,
                null,
                ContextAwareTopKFactory.class.getName(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()
        );
    }

    private static AlgorithmDefinition simpleTopKDefinition() {
        return new AlgorithmDefinition(
                JsonNodeFactory.instance.objectNode(),
                new AlgorithmId("simple-topk", "1.0.0"),
                ImmutableMap.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                SimpleLoadingTopKFactory.class.getName(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(JsonNodeFactory.instance.textNode("loaded"))
        );
    }

    private interface ContextCarrier {
        ExecutionContext executionContext();
    }

    public record ContextAwareState(ExecutionContext executionContext) implements Algorithm {
    }

    public static final class ContextAwareStateFactory implements NonCompositeStateFactory<ContextAwareState> {
        @Override
        public ContextAwareState apply(Map<String, InputStream> parameters, Optional<JsonNode> hyperparameter) {
            throw new AssertionError("Legacy state factory overload should not be used");
        }

        @Override
        public ContextAwareState create(
                ExecutionContext executionContext,
                Map<String, InputStream> parameters,
                Optional<JsonNode> hyperparameter
        ) {
            return new ContextAwareState(executionContext);
        }
    }

    public record StorageAwareState(Path stateDirectory) implements Algorithm {
    }

    public static final class StorageAwareStateFactory implements NonCompositeStateFactory<StorageAwareState> {
        @Override
        public StorageAwareState apply(Map<String, InputStream> parameters, Optional<JsonNode> hyperparameter) {
            throw new AssertionError("Legacy state factory overload should not be used");
        }

        @Override
        public StorageAwareState create(
                ExecutionContext executionContext,
                Optional<LocalStateStorage> localStateStorage,
                Map<String, InputStream> parameters,
                Optional<JsonNode> hyperparameter) {
            return new StorageAwareState(localStateStorage.orElseThrow().allocateDirectory());
        }
    }

    public record ContextAwareDependencyAlgorithm(ExecutionContext dependencyExecutionContext) implements Algorithm {
    }

    public static final class ContextAwareDependencyAlgorithmFactory
            implements NonCompositeAlgorithmFactory<ContextCarrier, ContextAwareDependencyAlgorithm> {
        @Override
        public ContextAwareDependencyAlgorithm apply(
                ContextCarrier dependency,
                Map<String, InputStream> parameters,
                Optional<JsonNode> hyperparameter
        ) {
            return new ContextAwareDependencyAlgorithm(dependency.executionContext());
        }
    }

    public static final class LegacyTwoArgumentAlgorithmFactory
            implements NonCompositeAlgorithmFactory<ContextCarrier, ContextAwareDependencyAlgorithm> {
        @Override
        public ContextAwareDependencyAlgorithm apply(
                ContextCarrier dependency,
                Map<String, InputStream> parameters,
                Optional<JsonNode> hyperparameter
        ) {
            throw new UnsupportedOperationException("Legacy two-argument factories are unsupported");
        }

        public ContextAwareDependencyAlgorithm apply(Object dependency, Object parameters) {
            return new ContextAwareDependencyAlgorithm(ONLINE_CONTEXT);
        }
    }

    public record ContextAwareTopK(ExecutionContext executionContext) implements TopK<String, String> {
        @Override
        public TopKResponse<String> apply(TopKRequest<String> topKRequest) {
            return TopKResponse.newResponse(List.of());
        }
    }

    public static final class ContextAwareTopKFactory implements TopKFactory<ContextCarrier, String, String> {
        @Override
        public TopK<String, String> apply(
                ContextCarrier dependency,
                Map<String, InputStream> parameters,
                Optional<JsonNode> hyperparameter
        ) {
            throw new AssertionError("Legacy TopK factory overload should not be used");
        }

        @Override
        public TopK<String, String> create(
                ExecutionContext executionContext,
                ContextCarrier dependency,
                Map<String, InputStream> parameters,
                Optional<JsonNode> hyperparameter
        ) {
            return new ContextAwareTopK(executionContext);
        }
    }

    public record SimpleTopKAlgorithm(String value) implements TopK<String, String> {
        @Override
        public TopKResponse<String> apply(TopKRequest<String> topKRequest) {
            return TopKResponse.newResponse(List.of());
        }
    }

    public static final class SimpleLoadingTopKFactory implements SimpleTopKFactory<String, String> {
        @Override
        public TopK<String, String> apply(Optional<JsonNode> hyperparameter) {
            return new SimpleTopKAlgorithm(hyperparameter.orElseThrow().asText());
        }
    }

    private record ContextAwareRankingTransformer(ExecutionContext executionContext)
            implements RankingTransformer<String, String>, ContextCarrier {
        @Override
        public List<NamespacedRecord<Namespace, Object>> apply(RankingRequest<String, String> rankingRequest) {
            return List.of();
        }

        @Override
        public SortedSet<? extends Namespace> getUsedFeatures() {
            return Collections.emptySortedSet();
        }
    }

    public static final class ContextAwareRankingTransformerFactory
            implements RankingTransformerFactory<String, String> {
        @Override
        public RankingTransformer<String, String> apply(
                Optional<JsonNode> hyperparameter,
                Map<String, InputStream> parameter
        ) {
            throw new AssertionError("Legacy ranking transformer overload should not be used");
        }

        @Override
        public RankingTransformer<String, String> create(
                ExecutionContext executionContext,
                Optional<JsonNode> hyperparameter,
                Map<String, InputStream> parameter
        ) {
            return new ContextAwareRankingTransformer(executionContext);
        }
    }

    public static final class ContextAwareCompositeTransformerFactory
            implements CompositeTransformerFactory<RankingTransformer<String, String>> {
        @Override
        public RankingTransformer<String, String> create(
                ExecutionContext executionContext,
                Optional<JsonNode> hyperparameters,
                Map<String, InputStream> parameters,
                AlgorithmDependencies algorithmDependencies
        ) {
            return new ContextAwareRankingTransformer(executionContext);
        }

        @Override
        public SortedSet<? extends Namespace> getUsedFeatures(Optional<JsonNode> transformerHyperparameters) {
            return Collections.emptySortedSet();
        }
    }

    private record ContextAwareRankingVectorizer(ExecutionContext executionContext)
            implements RankingVectorizer<String, String>, ContextCarrier {
        @Override
        public List<SparseVector> apply(RankingRequest<String, String> rankingRequest) {
            return List.of();
        }

        @Override
        public SortedSet<? extends Namespace> getUsedFeatures() {
            return Collections.emptySortedSet();
        }
    }

    public static final class ContextAwareRankingVectorizerFactory
            implements RankingVectorizerFactory<String, String> {
        @Override
        public RankingVectorizer<String, String> apply(
                Optional<JsonNode> hyperparameters,
                Map<String, InputStream> parameters
        ) {
            throw new AssertionError("Legacy ranking vectorizer overload should not be used");
        }

        @Override
        public RankingVectorizer<String, String> create(
                ExecutionContext executionContext,
                Optional<JsonNode> hyperparameters,
                Map<String, InputStream> parameters
        ) {
            return new ContextAwareRankingVectorizer(executionContext);
        }
    }

    public static final class ContextAwareCompositeVectorizerFactory
            implements CompositeVectorizerFactory<RankingVectorizer<String, String>> {
        @Override
        public RankingVectorizer<String, String> create(
                ExecutionContext executionContext,
                Optional<JsonNode> hyperparameters,
                Map<String, InputStream> parameters,
                AlgorithmDependencies algorithmDependencies
        ) {
            return new ContextAwareRankingVectorizer(executionContext);
        }
    }
}
