package com.hotvect.onlineutils.hotdeploy;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.reflect.TypeToken;
import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.algodefinition.AlgorithmDependencies;
import com.hotvect.api.algodefinition.AlgorithmId;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algodefinition.AlgorithmParameterMetadata;
import com.hotvect.api.algodefinition.common.CompositeAlgorithmFactory;
import com.hotvect.api.algodefinition.common.CompositeVectorizerFactory;
import com.hotvect.api.algodefinition.common.NonCompositeAlgorithmFactory;
import com.hotvect.api.algodefinition.common.SimpleAlgorithmFactory;
import com.hotvect.api.algodefinition.ranking.RankingTransformerFactory;
import com.hotvect.api.algodefinition.ranking.RankingVectorizerFactory;
import com.hotvect.api.algodefinition.state.NonCompositeStateFactory;
import com.hotvect.api.algodefinition.storage.LocalStateStorage;
import com.hotvect.api.algorithms.Algorithm;
import com.hotvect.api.execution.ExecutionContext;
import com.hotvect.api.execution.InputSemantic;
import com.hotvect.api.transformation.AuditableTransformer;
import com.hotvect.api.transformation.CompositeTransformerFactory;
import com.hotvect.onlineutils.hotdeploy.util.AlgorithmUtils;
import com.hotvect.onlineutils.hotdeploy.util.MalformedAlgorithmException;
import com.hotvect.utils.AlgorithmDefinitionOverrideUtils;
import com.hotvect.utils.AlgorithmDefinitionReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.zip.ZipFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkState;

/**
 * Constructs resolved algorithm graphs for one artifact classloader.
 *
 * <p>A returned {@link AlgorithmGraph} owns its nodes. Feature-extraction-only results remain owned
 * by this factory and may be used until the factory is closed.</p>
 */
public class AlgorithmInstanceFactory extends HotvectFactory implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(AlgorithmInstanceFactory.class);
    private static final Path DEFAULT_LOCAL_STATE_ROOT = Path.of(
            System.getProperty("java.io.tmpdir"),
            "algorithm-state").toAbsolutePath();

    private final InputSemantic inputSemantic;
    private final boolean strictAlgorithmVersionCheck;
    private final boolean enableFeatureLogging;
    private final Path localStateRoot;
    private final AlgorithmDefinitionReader definitionReader;
    private final RetainedResource ownedClassLoader;
    private final List<RuntimeGraphOwnership> ownedFeatureExtractions = new ArrayList<>();

    /** Complete construction options shared by JAR-backed and existing-classloader factories. */
    public record Options(
            InputSemantic inputSemantic,
            boolean strictAlgorithmVersionCheck,
            boolean enableFeatureLogging,
            Optional<Path> localStateRoot) {
        public Options {
            inputSemantic = Objects.requireNonNull(inputSemantic, "inputSemantic must not be null");
            localStateRoot = Objects.requireNonNull(localStateRoot, "localStateRoot must not be null")
                    .map(Path::toAbsolutePath)
                    .or(() -> Optional.of(DEFAULT_LOCAL_STATE_ROOT));
        }
    }

    /** Creates a factory that owns one isolated classloader for the supplied JAR. */
    public AlgorithmInstanceFactory(
            File algorithmJar,
            ClassLoader parent,
            Options options) {
        super(algorithmJar, parent);
        Options resolved = Objects.requireNonNull(options, "options must not be null");
        this.inputSemantic = resolved.inputSemantic();
        this.strictAlgorithmVersionCheck = resolved.strictAlgorithmVersionCheck();
        this.enableFeatureLogging = resolved.enableFeatureLogging();
        this.localStateRoot = resolved.localStateRoot().orElseThrow();
        this.definitionReader = new AlgorithmDefinitionReader(resolved.inputSemantic());
        this.ownedClassLoader = retainedClassLoader();
    }

    /** Creates a factory over an application-owned classloader. */
    public AlgorithmInstanceFactory(ClassLoader classLoader, Options options) {
        super(classLoader);
        Options resolved = Objects.requireNonNull(options, "options must not be null");
        this.inputSemantic = resolved.inputSemantic();
        this.strictAlgorithmVersionCheck = resolved.strictAlgorithmVersionCheck();
        this.enableFeatureLogging = resolved.enableFeatureLogging();
        this.localStateRoot = resolved.localStateRoot().orElseThrow();
        this.definitionReader = new AlgorithmDefinitionReader(resolved.inputSemantic());
        this.ownedClassLoader = null;
    }

    /** Reads one definition from this factory's artifact. */
    public AlgorithmDefinition readAlgorithmDefinition(String algorithmName) {
        return AlgorithmUtils.readAlgorithmDefinitionFromClassLoader(algorithmName, classLoader, definitionReader);
    }

    /** Plans reachable declarations without constructing algorithm instances. */
    public AlgorithmGraphInspection inspectGraph(
            AlgorithmDefinition algorithmDefinition,
            AlgorithmDependencies dependencyOverrides) {
        return AlgorithmGraphResolver.inspect(
                localArtifactProviderCatalog(null),
                algorithmDefinition,
                dependencyOverrides);
    }

    /** Inspects shared declarations reachable through private nodes of one selected provider. */
    public AlgorithmGraphInspection inspectSharedDependencies(
            AlgorithmDefinition algorithmDefinition,
            AlgorithmDependencies dependencyOverrides) {
        return AlgorithmGraphResolver.inspectSharedDependencies(
                localArtifactProviderCatalog(null),
                algorithmDefinition,
                dependencyOverrides);
    }

    /**
     * Rejects an execution context whose input semantic departs from the one this factory was built with.
     *
     * <p>The input semantic is fixed at construction time because it selects the dependency-resolution
     * policy of {@link #definitionReader}; only the workload mode is resolved per graph construction.
     */
    private void requireCompatibleInputSemantic(ExecutionContext executionContext) {
        Objects.requireNonNull(executionContext, "executionContext must not be null");
        if (executionContext.inputSemantic() != inputSemantic) {
            throw new IllegalArgumentException(
                    "Execution context must retain input semantic " + inputSemantic
                            + " but was " + executionContext.inputSemantic());
        }
    }

    /** Resolves one graph with borrowed application dependency bindings. */
    public <ALGO extends Algorithm> AlgorithmGraph<ALGO> loadGraph(
            AlgorithmDefinition algorithmDefinition,
            File parameterFile,
            AlgorithmDependencies dependencyOverrides,
            ExecutionContext executionContext) {
        return loadGraph(
                localArtifactProviderCatalog(parameterFile),
                algorithmDefinition,
                dependencyOverrides,
                AlgorithmGraphDependencies.empty(),
                null,
                executionContext);
    }

    /** Resolves one graph against an exact shared-provider catalog spanning several artifacts. */
    public <ALGO extends Algorithm> AlgorithmGraph<ALGO> loadGraphFromArtifactSet(
            AlgorithmDefinition algorithmDefinition,
            File parameterFile,
            AlgorithmDependencies dependencyOverrides,
            AlgorithmGraphDependencies slotBindings,
            String rootProviderIdentity,
            Map<AlgorithmId, AlgorithmArtifactProvider> sharedProviders,
            SharedNodeInterner sharedNodeInterner,
            ExecutionContext executionContext) {
        return loadGraph(
                artifactSetProviderCatalog(rootProviderIdentity, parameterFile, sharedProviders),
                algorithmDefinition,
                dependencyOverrides,
                slotBindings,
                Objects.requireNonNull(sharedNodeInterner, "sharedNodeInterner must not be null"),
                executionContext);
    }

    private <ALGO extends Algorithm> AlgorithmGraph<ALGO> loadGraph(
            AlgorithmGraphResolver.ProviderCatalog providerCatalog,
            AlgorithmDefinition algorithmDefinition,
            AlgorithmDependencies dependencyOverrides,
            AlgorithmGraphDependencies slotBindings,
            SharedNodeInterner sharedNodeInterner,
            ExecutionContext executionContext) {
        requireCompatibleInputSemantic(executionContext);
        RetainedResource.Lease classLoaderLease = acquireClassLoaderLease();
        try {
            AlgorithmGraph<ALGO> graph = AlgorithmGraphResolver.resolve(
                    providerCatalog,
                    algorithmDefinition,
                    dependencyOverrides,
                    slotBindings,
                    sharedNodeInterner,
                    executionContext);
            if (classLoaderLease != null) {
                graph.addOwnedResource(classLoaderLease);
            }
            return graph;
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(classLoaderLease, failure);
            throw failure;
        }
    }

    /** Constructs exactly one node whose dependencies have already been resolved. */
    protected <ALGO extends Algorithm> AlgorithmInstance<ALGO> construct(
            AlgorithmDefinition algorithmDefinition,
            File parameterFile,
            AlgorithmDependencies dependencies,
            AlgorithmDependencies applicationBindings,
            ExecutionContext executionContext) {
        Objects.requireNonNull(algorithmDefinition, "algorithmDefinition must not be null");
        Objects.requireNonNull(executionContext, "executionContext must not be null");
        AlgorithmParameterMetadata parameterMetadata = parameterMetadata(algorithmDefinition, parameterFile);
        Object algorithmFactory = instantiate(requireAlgorithmFactoryName(algorithmDefinition));

        try {
            TypeToken<? extends Algorithm> algorithmType =
                    AlgorithmContractResolver.resolve(algorithmFactory.getClass());
            if (algorithmFactory instanceof NonCompositeAlgorithmFactory<?, ?> nonCompositeFactory) {
                Object featureDependency = constructFeatureExtractionDependency(
                        algorithmDefinition,
                        parameterFile,
                        dependencies,
                        applicationBindings,
                        executionContext);
                Algorithm algorithm = withParameters(
                        algorithmDefinition,
                        parameterFile,
                        parameters -> instantiateParameterizedAlgorithm(
                                algorithmDefinition,
                                featureDependency,
                                nonCompositeFactory,
                                parameters,
                                executionContext));
                return instance(
                        algorithmDefinition,
                        parameterMetadata,
                        (ALGO) algorithm,
                        algorithmType);
            }
            if (algorithmFactory instanceof CompositeAlgorithmFactory<?> compositeFactory) {
                ALGO algorithm = withParameters(
                        algorithmDefinition,
                        parameterFile,
                        parameters -> (ALGO) compositeFactory.create(
                                executionContext,
                                localStateStorage(algorithmDefinition),
                                algorithmDefinition.algorithmParameter(),
                                parameters,
                                dependenciesForFactory(
                                        compositeFactory,
                                        CompositeAlgorithmFactory.class,
                                        dependencies,
                                        applicationBindings)));
                return instance(
                        algorithmDefinition,
                        parameterMetadata,
                        algorithm,
                        algorithmType);
            }
            if (algorithmFactory instanceof SimpleAlgorithmFactory<?> simpleFactory) {
                ALGO algorithm = (ALGO) simpleFactory.create(
                        executionContext,
                        localStateStorage(algorithmDefinition),
                        algorithmDefinition.algorithmParameter());
                return instance(
                        algorithmDefinition,
                        parameterMetadata,
                        algorithm,
                        algorithmType);
            }
            if (algorithmFactory instanceof NonCompositeStateFactory<?> stateFactory) {
                ALGO algorithm = withParameters(
                        algorithmDefinition,
                        parameterFile,
                        parameters -> (ALGO) stateFactory.create(
                                executionContext,
                                localStateStorage(algorithmDefinition),
                                parameters,
                                algorithmDefinition.algorithmParameter()));
                return instance(
                        algorithmDefinition,
                        parameterMetadata,
                        algorithm,
                        algorithmType);
            }
        } catch (MalformedAlgorithmException error) {
            throw error;
        } catch (Exception error) {
            throw new MalformedAlgorithmException(error);
        }

        throw new MalformedAlgorithmException(
                "Specified algorithm factory " + algorithmDefinition.algorithmFactoryName()
                        + " does not conform to allowed interfaces "
                        + ImmutableSet.of(
                                NonCompositeAlgorithmFactory.class.getCanonicalName(),
                                CompositeAlgorithmFactory.class.getCanonicalName(),
                                SimpleAlgorithmFactory.class.getCanonicalName(),
                                NonCompositeStateFactory.class.getCanonicalName()));
    }

    /** Derives the validated parameter identity without constructing the algorithm implementation. */
    AlgorithmParameterMetadata parameterMetadata(
            AlgorithmDefinition algorithmDefinition,
            File parameterFile) {
        Objects.requireNonNull(algorithmDefinition, "algorithmDefinition must not be null");
        AlgorithmParameterMetadata metadata = readAlgorithmParameterMetadataIfPresent(
                algorithmDefinition.algorithmId(),
                parameterFile);
        validateParameterMetadata(algorithmDefinition, metadata);
        return metadata;
    }

    /**
     * Constructs only the feature-extraction component after resolving its graph through the common
     * path. The returned value and its child algorithms remain valid until this factory is closed.
     */
    protected <DEPENDENCY> DEPENDENCY prepareFeatureExtraction(
            AlgorithmDefinition algorithmDefinition,
            File parameterFile,
            AlgorithmDependencies dependencyOverrides,
            ExecutionContext executionContext) {
        requireCompatibleInputSemantic(executionContext);
        RetainedResource.Lease classLoaderLease = acquireClassLoaderLease();
        RuntimeGraphOwnership ownership = null;
        try {
            AlgorithmGraphResolver.ResolvedFeatureExtraction<DEPENDENCY> resolved =
                    AlgorithmGraphResolver.resolveFeatureExtraction(
                            localArtifactProviderCatalog(parameterFile),
                            algorithmDefinition,
                            dependencyOverrides,
                            executionContext);
            ownership = resolved.ownership();
            if (classLoaderLease != null) {
                ownership.addOwnedResource(classLoaderLease);
                classLoaderLease = null;
            }
            ownedFeatureExtractions.add(ownership);
            return resolved.dependency();
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(ownership, failure);
            closeAfterFailure(classLoaderLease, failure);
            throw failure;
        }
    }

    @Override
    public void close() throws Exception {
        Throwable failure = null;
        for (int index = ownedFeatureExtractions.size() - 1; index >= 0; index--) {
            failure = close(ownedFeatureExtractions.get(index), failure);
        }
        if (ownedClassLoader != null) {
            failure = close(ownedClassLoader, failure);
        }
        if (failure != null) {
            rethrow(failure);
        }
    }

    private RetainedResource retainedClassLoader() {
        if (!(classLoader instanceof AutoCloseable closeable)) {
            throw new IllegalStateException("Artifact classloader must be closeable");
        }
        return new RetainedResource(closeable);
    }

    private RetainedResource.Lease acquireClassLoaderLease() {
        return ownedClassLoader == null ? null : ownedClassLoader.acquire();
    }

    private static void closeAfterFailure(AutoCloseable closeable, Throwable failure) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Throwable closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private static Throwable close(AutoCloseable closeable, Throwable failure) {
        try {
            closeable.close();
        } catch (Throwable closeFailure) {
            if (failure == null) {
                return closeFailure;
            }
            failure.addSuppressed(closeFailure);
        }
        return failure;
    }

    private static void rethrow(Throwable failure) throws Exception {
        if (failure instanceof Exception exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new RuntimeException(failure);
    }

    /** Constructs a feature-extraction component with a fully resolved child graph. */
    protected <DEPENDENCY> DEPENDENCY constructFeatureExtractionDependency(
            AlgorithmDefinition algorithmDefinition,
            File parameterFile,
            AlgorithmDependencies dependencies,
            AlgorithmDependencies applicationBindings,
            ExecutionContext executionContext) {
        Object dependencyFactory;
        Optional<JsonNode> hyperparameter;
        if (algorithmDefinition.vectorizerFactoryName() != null) {
            dependencyFactory = instantiate(algorithmDefinition.vectorizerFactoryName());
            hyperparameter = algorithmDefinition.vectorizerParameter();
        } else {
            if (algorithmDefinition.transformerFactoryName() == null) {
                throw new MalformedAlgorithmException(
                        "Algorithm " + algorithmDefinition.algorithmId()
                                + " must declare a vectorizer or transformer factory");
            }
            dependencyFactory = instantiate(algorithmDefinition.transformerFactoryName());
            hyperparameter = algorithmDefinition.transformerParameter();
        }

        DEPENDENCY dependency = withParameters(
                algorithmDefinition,
                parameterFile,
                parameters -> instantiateFeatureDependency(
                        dependencyFactory,
                        hyperparameter,
                        parameters,
                        dependencies,
                        applicationBindings,
                        executionContext));
        enableFeatureLoggingIfRequested(dependency, algorithmDefinition.algorithmId().algorithmName());
        return dependency;
    }

    private <ALGO extends Algorithm> AlgorithmInstance<ALGO> instance(
            AlgorithmDefinition definition,
            AlgorithmParameterMetadata parameterMetadata,
            ALGO algorithm,
            TypeToken<? extends Algorithm> declaredAlgorithmType) {
        @SuppressWarnings("unchecked")
        TypeToken<ALGO> algorithmType = (TypeToken<ALGO>) declaredAlgorithmType;
        return new AlgorithmInstance<>(
                definition,
                parameterMetadata,
                algorithm,
                algorithmType);
    }

    private Optional<LocalStateStorage> localStateStorage(AlgorithmDefinition algorithmDefinition) {
        return Optional.of(new DirectoryLocalStateStorage(localStateRoot, algorithmDefinition.algorithmId()));
    }

    private AlgorithmParameterMetadata readAlgorithmParameterMetadataIfPresent(
            AlgorithmId algorithmId,
            File parameterFile) {
        return parameterFile == null
                ? null
                : AlgorithmUtils.readAlgorithmParameterMetadata(
                        algorithmId,
                        parameterFile,
                        strictAlgorithmVersionCheck);
    }

    private void validateParameterMetadata(
            AlgorithmDefinition algorithmDefinition,
            AlgorithmParameterMetadata parameterMetadata) {
        if (parameterMetadata == null) {
            return;
        }
        if (strictAlgorithmVersionCheck) {
            checkState(parameterMetadata.algorithmId().equals(algorithmDefinition.algorithmId()));
        } else {
            checkState(parameterMetadata.algorithmId().algorithmName().equals(
                    algorithmDefinition.algorithmId().algorithmName()));
        }
    }

    private <ALGO extends Algorithm> ALGO instantiateParameterizedAlgorithm(
            AlgorithmDefinition algorithmDefinition,
            Object dependency,
            NonCompositeAlgorithmFactory<?, ?> algorithmFactory,
            Map<String, InputStream> parameters,
            ExecutionContext executionContext) {
        @SuppressWarnings("unchecked")
        NonCompositeAlgorithmFactory<Object, ALGO> typedFactory =
                (NonCompositeAlgorithmFactory<Object, ALGO>) algorithmFactory;
        return typedFactory.create(
                executionContext,
                localStateStorage(algorithmDefinition),
                dependency,
                parameters,
                algorithmDefinition.algorithmParameter());
    }

    private <DEPENDENCY> DEPENDENCY instantiateFeatureDependency(
            Object dependencyFactory,
            Optional<JsonNode> hyperparameter,
            Map<String, InputStream> parameters,
            AlgorithmDependencies dependencies,
            AlgorithmDependencies applicationBindings,
            ExecutionContext executionContext) {
        if (dependencyFactory instanceof RankingTransformerFactory<?, ?> factory) {
            return (DEPENDENCY) factory.create(executionContext, hyperparameter, parameters);
        }
        if (dependencyFactory instanceof RankingVectorizerFactory<?, ?> factory) {
            return (DEPENDENCY) factory.create(executionContext, hyperparameter, parameters);
        }
        if (dependencyFactory instanceof CompositeTransformerFactory<?> factory) {
            return (DEPENDENCY) factory.create(
                    executionContext,
                    hyperparameter,
                    parameters,
                    dependenciesForFactory(
                            factory,
                            CompositeTransformerFactory.class,
                            dependencies,
                            applicationBindings));
        }
        if (dependencyFactory instanceof CompositeVectorizerFactory<?> factory) {
            return (DEPENDENCY) factory.create(
                    executionContext,
                    hyperparameter,
                    parameters,
                    dependenciesForFactory(
                            factory,
                            CompositeVectorizerFactory.class,
                            dependencies,
                            applicationBindings));
        }
        if (dependencyFactory instanceof BiFunction<?, ?, ?> factory) {
            @SuppressWarnings("unchecked")
            BiFunction<Optional<JsonNode>, Map<String, InputStream>, DEPENDENCY> typedFactory =
                    (BiFunction<Optional<JsonNode>, Map<String, InputStream>, DEPENDENCY>) factory;
            return typedFactory.apply(hyperparameter, parameters);
        }
        throw new MalformedAlgorithmException(
                "Unknown dependency factory class type: " + dependencyFactory.getClass().getCanonicalName());
    }

    /**
     * Preserves the application-binding contract of published singleton factories. Those factories
     * received application bindings independently of their packaged dependency declarations. New
     * factories receive only the dependencies declared for their graph node.
     */
    private static AlgorithmDependencies dependenciesForFactory(
            Object factory,
            Class<?> compatibilityBridge,
            AlgorithmDependencies declaredDependencies,
            AlgorithmDependencies applicationBindings) {
        Method dependencyMethod = Arrays.stream(factory.getClass().getMethods())
                .filter(method -> method.getName().equals("create"))
                .filter(method -> Arrays.asList(method.getParameterTypes()).contains(AlgorithmDependencies.class))
                .findFirst()
                .orElseThrow();
        if (!dependencyMethod.getDeclaringClass().equals(compatibilityBridge)) {
            return declaredDependencies;
        }

        TreeMap<String, AlgorithmInstance<?>> merged = new TreeMap<>(applicationBindings.asMap());
        declaredDependencies.asMap().forEach((name, algorithm) -> {
            AlgorithmInstance<?> previous = merged.put(name, algorithm);
            if (previous != null && !previous.equals(algorithm)) {
                throw new IllegalStateException(
                        "Application dependency " + name + " does not match its resolved graph dependency");
            }
        });
        return merged.isEmpty() ? AlgorithmDependencies.empty() : new AlgorithmDependencies(merged);
    }

    private <T> T withParameters(
            AlgorithmDefinition algorithmDefinition,
            File parameterFile,
            ParameterizedConstruction<T> construction) {
        try {
            if (parameterFile == null) {
                return construction.construct(ImmutableMap.of());
            }
            try (ZipFile file = new ZipFile(parameterFile)) {
                return construction.construct(AlgorithmUtils.extractParameters(algorithmDefinition.algorithmId(), file));
            }
        } catch (MalformedAlgorithmException error) {
            throw error;
        } catch (Exception error) {
            throw new MalformedAlgorithmException(error);
        }
    }

    private void enableFeatureLoggingIfRequested(Object transformer, String algorithmName) {
        if (!enableFeatureLogging) {
            return;
        }
        if (transformer instanceof AuditableTransformer auditableTransformer) {
            auditableTransformer.setFeatureAuditEnabled(true, algorithmName);
            LOG.info("Feature auditing enabled for algorithm: {}", algorithmName);
            return;
        }
        LOG.warn(
                "Feature auditing requested but algorithm '{}' uses '{}' which does not support feature auditing.",
                algorithmName,
                transformer.getClass().getName());
    }

    private static String requireAlgorithmFactoryName(AlgorithmDefinition algorithmDefinition) {
        String factoryName = algorithmDefinition.algorithmFactoryName();
        if (factoryName == null || factoryName.isBlank()) {
            throw new MalformedAlgorithmException(
                    "Algorithm " + algorithmDefinition.algorithmId()
                            + " must declare algorithm_factory_classname");
        }
        return factoryName;
    }

    protected <T> T instantiate(String className) {
        try {
            return (T) classLoader.loadClass(className).getDeclaredConstructor().newInstance();
        } catch (Exception error) {
            throw new MalformedAlgorithmException("Unable to instantiate: " + className, error);
        }
    }

    AlgorithmDefinition applyDefinitionOverride(AlgorithmDefinition baseDefinition, JsonNode override) {
        JsonNode merged = AlgorithmDefinitionOverrideUtils.applyOverride(
                Objects.requireNonNull(baseDefinition, "baseDefinition must not be null").rawAlgorithmDefinition(),
                Objects.requireNonNull(override, "override must not be null"),
                definitionReader.dependencyResolution());
        return parseAlgorithmDefinition(merged);
    }

    AlgorithmDefinition parseAlgorithmDefinition(JsonNode definition) {
        try {
            return definitionReader.parse(Objects.requireNonNull(definition, "definition must not be null"));
        } catch (IOException error) {
            throw new MalformedAlgorithmException(error);
        }
    }

    private AlgorithmGraphResolver.ProviderCatalog localArtifactProviderCatalog(File parameterFile) {
        AlgorithmArtifactProvider provider = new AlgorithmArtifactProvider(this, "local:" + classLoader, parameterFile);
        return new AlgorithmGraphResolver.ProviderCatalog() {
            @Override
            public AlgorithmGraphResolver.Provider rootProvider(AlgorithmDefinition rootDefinition) {
                return provider;
            }

            @Override
            public AlgorithmGraphResolver.Provider privateProvider(
                    AlgorithmGraphResolver.Provider parent,
                    com.hotvect.api.algodefinition.AlgorithmDependencyDeclaration.Private declaration) {
                if (!(parent instanceof AlgorithmArtifactProvider)) {
                    throw new IllegalStateException(
                            "Direct private dependency " + declaration.name()
                                    + " has no parent artifact provider");
                }
                return parent;
            }

            @Override
            public AlgorithmGraphResolver.Provider sharedProvider(
                    com.hotvect.api.algodefinition.AlgorithmDependencyDeclaration.Shared declaration) {
                return provider;
            }
        };
    }

    private AlgorithmGraphResolver.ProviderCatalog artifactSetProviderCatalog(
            String rootProviderIdentity,
            File rootParameterFile,
            Map<AlgorithmId, AlgorithmArtifactProvider> sharedProviders) {
        if (rootProviderIdentity == null || rootProviderIdentity.isBlank()) {
            throw new IllegalArgumentException("rootProviderIdentity must not be blank");
        }
        Objects.requireNonNull(sharedProviders, "sharedProviders must not be null");
        Map<AlgorithmId, AlgorithmArtifactProvider> providersById = Map.copyOf(sharedProviders);
        AlgorithmArtifactProvider rootProvider = new AlgorithmArtifactProvider(
                this,
                rootProviderIdentity,
                rootParameterFile);
        return new AlgorithmGraphResolver.ProviderCatalog() {
            @Override
            public AlgorithmGraphResolver.Provider rootProvider(AlgorithmDefinition rootDefinition) {
                return rootProvider;
            }

            @Override
            public AlgorithmGraphResolver.Provider privateProvider(
                    AlgorithmGraphResolver.Provider parent,
                    com.hotvect.api.algodefinition.AlgorithmDependencyDeclaration.Private declaration) {
                if (!(parent instanceof AlgorithmArtifactProvider)) {
                    throw new IllegalStateException(
                            "Private dependency " + declaration.name() + " has no parent artifact provider");
                }
                return parent;
            }

            @Override
            public AlgorithmGraphResolver.Provider sharedProvider(
                    com.hotvect.api.algodefinition.AlgorithmDependencyDeclaration.Shared declaration) {
                AlgorithmArtifactProvider provider = providersById.get(declaration.algorithmId());
                if (provider == null) {
                    throw new IllegalArgumentException(
                            "No loaded artifact provides shared dependency " + declaration.algorithmId());
                }
                return provider;
            }
        };
    }

    @FunctionalInterface
    private interface ParameterizedConstruction<T> {
        T construct(Map<String, InputStream> parameters) throws Exception;
    }
}
