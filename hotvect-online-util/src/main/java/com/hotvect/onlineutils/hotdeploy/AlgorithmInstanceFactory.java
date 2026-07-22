package com.hotvect.onlineutils.hotdeploy;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.algodefinition.AlgorithmId;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algodefinition.common.CompositeVectorizerFactory;
import com.hotvect.api.algodefinition.AlgorithmParameterMetadata;
import com.hotvect.api.algodefinition.common.AlgorithmFactory;
import com.hotvect.api.algodefinition.common.CompositeAlgorithmFactory;
import com.hotvect.api.algodefinition.common.NonCompositeAlgorithmFactory;
import com.hotvect.api.algodefinition.common.SimpleAlgorithmFactory;
import com.hotvect.api.algodefinition.ranking.RankingTransformerFactory;
import com.hotvect.api.algodefinition.ranking.RankingVectorizerFactory;
import com.hotvect.api.algodefinition.state.NonCompositeStateFactory;
import com.hotvect.api.algodefinition.storage.LocalStateStorage;
import com.hotvect.api.algorithms.Algorithm;
import com.hotvect.api.execution.ExecutionContext;
import com.hotvect.api.transformation.AuditableTransformer;
import com.hotvect.api.transformation.CompositeTransformerFactory;
import com.hotvect.onlineutils.hotdeploy.util.AlgorithmUtils;
import com.hotvect.onlineutils.hotdeploy.util.MalformedAlgorithmException;
import com.hotvect.utils.AlgorithmDefinitionReader;
import com.hotvect.utils.AlgorithmDefinitionOverrideUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;

import static com.google.common.base.Preconditions.checkState;
import static java.util.stream.Collectors.toMap;

public class AlgorithmInstanceFactory extends HotvectFactory implements AlgorithmInstantiator {
    private static final Logger log = LoggerFactory.getLogger(AlgorithmInstanceFactory.class);
    private final ExecutionContext executionContext;
    private final boolean strictAlgorithmVersionCheck;
    private final boolean enableFeatureLogging;
    private final Optional<Path> localStateRoot;

    private ExecutionContext executionContext() {
        return executionContext;
    }

    private Optional<LocalStateStorage> localStateStorage(AlgorithmDefinition algorithmDefinition) {
        return localStateRoot.map(root -> new DirectoryLocalStateStorage(root, algorithmDefinition.algorithmId()));
    }

    public AlgorithmInstanceFactory(File algorithmJar, ExecutionContext executionContext, boolean strictAlgorithmVersionCheck) throws MalformedAlgorithmException {
        this(algorithmJar, executionContext, strictAlgorithmVersionCheck, false);
    }

    public AlgorithmInstanceFactory(File algorithmJar, ExecutionContext executionContext, boolean strictAlgorithmVersionCheck, boolean enableFeatureLogging) throws MalformedAlgorithmException {
        super(algorithmJar);
        this.executionContext = executionContext;
        this.strictAlgorithmVersionCheck = strictAlgorithmVersionCheck;
        this.enableFeatureLogging = enableFeatureLogging;
        this.localStateRoot = Optional.empty();
    }

    public AlgorithmInstanceFactory(ClassLoader classLoader, ExecutionContext executionContext, boolean strictAlgorithmVersionCheck) throws MalformedAlgorithmException {
        this(classLoader, executionContext, strictAlgorithmVersionCheck, false);
    }

    public AlgorithmInstanceFactory(ClassLoader classLoader, ExecutionContext executionContext, boolean strictAlgorithmVersionCheck, boolean enableFeatureLogging) throws MalformedAlgorithmException {
        this(classLoader, executionContext, strictAlgorithmVersionCheck, enableFeatureLogging, Optional.empty());
    }

    public AlgorithmInstanceFactory(
            ClassLoader classLoader,
            ExecutionContext executionContext,
            boolean strictAlgorithmVersionCheck,
            Optional<Path> localStateRoot) throws MalformedAlgorithmException {
        this(classLoader, executionContext, strictAlgorithmVersionCheck, false, localStateRoot);
    }

    public AlgorithmInstanceFactory(
            ClassLoader classLoader,
            ExecutionContext executionContext,
            boolean strictAlgorithmVersionCheck,
            boolean enableFeatureLogging,
            Optional<Path> localStateRoot) throws MalformedAlgorithmException {
        super(classLoader);
        this.executionContext = executionContext;
        this.strictAlgorithmVersionCheck = strictAlgorithmVersionCheck;
        this.enableFeatureLogging = enableFeatureLogging;
        this.localStateRoot = localStateRoot.map(Path::toAbsolutePath);
    }

    public AlgorithmInstanceFactory(File algorithmJar, ClassLoader parent, ExecutionContext executionContext, boolean strictAlgorithmVersionCheck) throws MalformedAlgorithmException {
        this(algorithmJar, parent, executionContext, strictAlgorithmVersionCheck, false);
    }

    public AlgorithmInstanceFactory(File algorithmJar, ClassLoader parent, ExecutionContext executionContext, boolean strictAlgorithmVersionCheck, boolean enableFeatureLogging) throws MalformedAlgorithmException {
        this(algorithmJar, parent, executionContext, strictAlgorithmVersionCheck, enableFeatureLogging, Optional.empty());
    }

    public AlgorithmInstanceFactory(
            File algorithmJar,
            ClassLoader parent,
            ExecutionContext executionContext,
            boolean strictAlgorithmVersionCheck,
            Optional<Path> localStateRoot) throws MalformedAlgorithmException {
        this(algorithmJar, parent, executionContext, strictAlgorithmVersionCheck, false, localStateRoot);
    }

    public AlgorithmInstanceFactory(
            File algorithmJar,
            ClassLoader parent,
            ExecutionContext executionContext,
            boolean strictAlgorithmVersionCheck,
            boolean enableFeatureLogging,
            Optional<Path> localStateRoot) throws MalformedAlgorithmException {
        super(algorithmJar, parent);
        this.executionContext = executionContext;
        this.strictAlgorithmVersionCheck = strictAlgorithmVersionCheck;
        this.enableFeatureLogging = enableFeatureLogging;
        this.localStateRoot = localStateRoot.map(Path::toAbsolutePath);
    }

    public boolean requiresLocalStateStorage(String algorithmName) throws MalformedAlgorithmException {
        try {
            AlgorithmDefinition definition = AlgorithmUtils.readAlgorithmDefinitionFromClassLoader(algorithmName, classLoader);
            return definition.requiresLocalStateStorage();
        } catch (MalformedAlgorithmException e) {
            throw e;
        } catch (Exception e) {
            throw new MalformedAlgorithmException(
                    "Failed to resolve local state storage requirement for algorithm " + algorithmName,
                    e);
        }
    }

    @Override
    public <ALGO extends Algorithm> AlgorithmInstance<ALGO> load(String algorithmName, File parameterFile, Map<String, AlgorithmInstance<?>> dependencyOverrides) throws MalformedAlgorithmException {
        AlgorithmDefinition algorithmDefinition = AlgorithmUtils.readAlgorithmDefinitionFromClassLoader(algorithmName, this.classLoader);
        return load(algorithmDefinition, parameterFile, dependencyOverrides);
    }

    @Override
    public <ALGO extends Algorithm> AlgorithmInstance<ALGO> load(AlgorithmDefinition algorithmDefinition, File parameterFile, Map<String, AlgorithmInstance<?>> dependencyOverrides) throws MalformedAlgorithmException {
        AlgorithmParameterMetadata parameterMetadata = readAlgorithmParameterMetadataIfPresent(
                algorithmDefinition.algorithmId(),
                parameterFile
        );
        validateParameterMetadata(algorithmDefinition, parameterMetadata);
        Object algorithmFactory = instantiate(algorithmDefinition.algorithmFactoryName());

        // This section can be cleaned up once we upgrade to Java 21 using switches
        if (algorithmFactory instanceof NonCompositeAlgorithmFactory) {
            // We are a non-composite algorithm
            return (AlgorithmInstance<ALGO>) loadAlgorithmInstance(algorithmDefinition, parameterFile, dependencyOverrides);
        } else if (algorithmFactory instanceof CompositeAlgorithmFactory) {
            // We are a composite algorithm
            CompositeAlgorithmFactory<ALGO> algoFactory = (CompositeAlgorithmFactory<ALGO>) algorithmFactory;
            Map<AlgorithmId, AlgorithmInstance<?>> dependencies = loadDependencies(algorithmDefinition, parameterFile, dependencyOverrides);
            Map<String, AlgorithmInstance<?>> dependenciesWithOverrides = applyDependencyOverrides(withOnlyAlgorithmName(dependencies), dependencyOverrides);
            try {
                if (parameterFile == null) {
                    ALGO algo = algoFactory.create(
                            executionContext(),
                            localStateStorage(algorithmDefinition),
                            algorithmDefinition.algorithmParameter(),
                            ImmutableMap.of(),
                            dependenciesWithOverrides);
                    AlgorithmDefinition algoDefWithResolvedDeps = withResolvedDependencies(algorithmDefinition, dependenciesWithOverrides);
                    return new AlgorithmInstance<>(algoDefWithResolvedDeps, parameterMetadata, algo);
                } else {
                    try (ZipFile file = new ZipFile(parameterFile)) {
                        Map<String, InputStream> parameters = AlgorithmUtils.extractParameters(algorithmDefinition.algorithmId(), file);
                        ALGO algo = algoFactory.create(
                                executionContext(),
                                localStateStorage(algorithmDefinition),
                                algorithmDefinition.algorithmParameter(),
                                parameters,
                                dependenciesWithOverrides);
                        AlgorithmDefinition algoDefWithResolvedDeps = withResolvedDependencies(algorithmDefinition, dependenciesWithOverrides);
                        return new AlgorithmInstance<>(algoDefWithResolvedDeps, parameterMetadata, algo);
                    }
                }
            } catch (Exception e) {
                throw new MalformedAlgorithmException(e);
            }
        } else if (algorithmFactory instanceof SimpleAlgorithmFactory) {
            // We are a simple algorithm
            SimpleAlgorithmFactory<ALGO> algoFactory = (SimpleAlgorithmFactory<ALGO>) algorithmFactory;
            ALGO algo = algoFactory.create(
                    executionContext(),
                    localStateStorage(algorithmDefinition),
                    algorithmDefinition.algorithmParameter());
            return new AlgorithmInstance<>(algorithmDefinition, null, algo);
        } else if (algorithmFactory instanceof NonCompositeStateFactory) {
            return (AlgorithmInstance<ALGO>) loadAlgorithmInstance(algorithmDefinition, parameterFile, dependencyOverrides);
        } else {
            // We don't know this class
            throw new MalformedAlgorithmException(String.format("Specified algorithm factory %s does not conform to allowed interfaces %s", algorithmDefinition.algorithmFactoryName(), ImmutableSet.of(NonCompositeAlgorithmFactory.class.getCanonicalName(), CompositeAlgorithmFactory.class.getCanonicalName())));
        }
    }

    private AlgorithmDefinition withResolvedDependencies(AlgorithmDefinition algorithmDefinition, Map<String, AlgorithmInstance<?>> dependencies) {
        Map<String, AlgorithmDefinition> dependencyAlgoDefs = ImmutableMap.copyOf(Maps.transformValues(dependencies, AlgorithmInstance::algorithmDefinition));
        return algorithmDefinition.replace(dependencyAlgoDefs);
    }

    protected AlgorithmInstance<?> loadAlgorithmInstance(AlgorithmDefinition algorithmDefinition, File parameterFile, Map<String, AlgorithmInstance<?>> dependencyOverrides) throws MalformedAlgorithmException {
        AlgorithmParameterMetadata algorithmParameterMetadata = readAlgorithmParameterMetadataIfPresent(
                algorithmDefinition.algorithmId(),
                parameterFile
        );
        return loadAlgorithmInstance(algorithmDefinition, algorithmParameterMetadata, parameterFile, dependencyOverrides);
    }

    protected AlgorithmInstance<?> loadAlgorithmInstance(String algorithmName, Optional<JsonNode> algorithmDefinitionOverride, File parameterFile, Map<String, AlgorithmInstance<?>> dependencyOverrides) throws MalformedAlgorithmException {
        AlgorithmDefinition baseAlgoDefinition = AlgorithmUtils.readAlgorithmDefinitionFromClassLoader(algorithmName, this.classLoader);
        AlgorithmParameterMetadata algorithmParameterMetadata = readAlgorithmParameterMetadataIfPresent(
                baseAlgoDefinition.algorithmId(),
                parameterFile
        );
        AlgorithmDefinition updatedAlgoDef = getUpdatedAlgoDef(algorithmDefinitionOverride, baseAlgoDefinition);
        return loadAlgorithmInstance(updatedAlgoDef, algorithmParameterMetadata, parameterFile, dependencyOverrides);
    }

    protected AlgorithmInstance<?> loadAlgorithmInstance(
            AlgorithmDefinition algorithmDefinition,
            AlgorithmParameterMetadata algorithmParameterMetadata,
            File parameterFile,
            Map<String, AlgorithmInstance<?>> dependencyOverrides
    ) throws MalformedAlgorithmException {
        AlgorithmFactory algorithmFactory = instantiate(algorithmDefinition.algorithmFactoryName());
        if(algorithmFactory instanceof NonCompositeAlgorithmFactory nonCompositeFactory){
            Object dependency = loadFeatureExtractionDependency(algorithmDefinition, parameterFile, dependencyOverrides);
            Algorithm algorithm = loadParameterizedAlgorithm(algorithmDefinition, dependency, nonCompositeFactory, parameterFile);
            return new AlgorithmInstance<>(algorithmDefinition, algorithmParameterMetadata, algorithm);
        } else if (algorithmFactory instanceof CompositeAlgorithmFactory compositeAlgoFactory) {
            Map<AlgorithmId, AlgorithmInstance<?>> dependencies = loadDependencies(algorithmDefinition, parameterFile, dependencyOverrides);
            Map<String, AlgorithmInstance<?>> dependenciesWithOverrides = applyDependencyOverrides(withOnlyAlgorithmName(dependencies), dependencyOverrides);
            // TODO parameter files for a composite algorithm are not supported yet
            var algorithm = compositeAlgoFactory.create(
                    executionContext(),
                    localStateStorage(algorithmDefinition),
                    algorithmDefinition.algorithmParameter(),
                    ImmutableMap.of(),
                    dependenciesWithOverrides);
            AlgorithmDefinition withResolvedDependencies = withResolvedDependencies(algorithmDefinition, dependenciesWithOverrides);
            return new AlgorithmInstance<>(withResolvedDependencies, algorithmParameterMetadata, algorithm);
        } else if (algorithmFactory instanceof NonCompositeStateFactory nonCompositeStateFactory) {
            try {
                if (parameterFile == null) {
                    var algorithm = nonCompositeStateFactory.create(
                            executionContext(),
                            localStateStorage(algorithmDefinition),
                            ImmutableMap.of(),
                            algorithmDefinition.algorithmParameter());
                    return new AlgorithmInstance<>(algorithmDefinition, algorithmParameterMetadata, algorithm);
                } else {
                    try (ZipFile file = new ZipFile(parameterFile)) {
                        Map<String, InputStream> parameters = AlgorithmUtils.extractParameters(algorithmDefinition.algorithmId(), file);
                        var algorithm = nonCompositeStateFactory.create(
                                executionContext(),
                                localStateStorage(algorithmDefinition),
                                parameters,
                                algorithmDefinition.algorithmParameter());
                        return new AlgorithmInstance<>(algorithmDefinition, algorithmParameterMetadata, algorithm);
                    }
                }
            } catch (Exception e) {
                throw new MalformedAlgorithmException(e);
            }

        } else {
            throw new MalformedAlgorithmException("Unknown algorithm factory type:" + algorithmFactory.getClass().getCanonicalName());
        }
    }

    protected <DEPENDENCY, ALGO extends Algorithm> ALGO loadParameterizedAlgorithm(AlgorithmDefinition algorithmDefinition, DEPENDENCY dependency, NonCompositeAlgorithmFactory algorithmFactory, File parameterFile) throws MalformedAlgorithmException {
        try {
            if (parameterFile == null) {
                return instantiateParameterizedAlgorithm(algorithmDefinition, dependency, algorithmFactory, ImmutableMap.of());
            } else {
                try (ZipFile file = new ZipFile(parameterFile)) {
                    Map<String, InputStream> parameters = AlgorithmUtils.extractParameters(algorithmDefinition.algorithmId(), file);
                    return instantiateParameterizedAlgorithm(algorithmDefinition, dependency, algorithmFactory, parameters);
                }
            }
        } catch (Exception e) {
            throw new MalformedAlgorithmException(e);
        }
    }

    private AlgorithmParameterMetadata readAlgorithmParameterMetadataIfPresent(AlgorithmId algorithmId, File parameterFile) throws MalformedAlgorithmException {
        if (parameterFile == null) {
            return null;
        }
        return AlgorithmUtils.readAlgorithmParameterMetadata(algorithmId, parameterFile, strictAlgorithmVersionCheck);
    }

    private void validateParameterMetadata(AlgorithmDefinition algorithmDefinition, AlgorithmParameterMetadata parameterMetadata) {
        if (parameterMetadata == null) {
            return;
        }
        if (strictAlgorithmVersionCheck) {
            checkState(parameterMetadata.algorithmId().equals(algorithmDefinition.algorithmId()));
        } else {
            checkState(parameterMetadata.algorithmId().algorithmName().equals(algorithmDefinition.algorithmId().algorithmName()));
        }
    }

    private <DEPENDENCY, ALGO extends Algorithm> ALGO instantiateParameterizedAlgorithm(
            AlgorithmDefinition algorithmDefinition,
            DEPENDENCY dependency,
            NonCompositeAlgorithmFactory algorithmFactory,
            Map<String, InputStream> parameters
    ) throws Exception {
        try {
            // Try non legacy first
            return (ALGO) algorithmFactory.create(
                    executionContext(),
                    localStateStorage(algorithmDefinition),
                    dependency,
                    parameters,
                    algorithmDefinition.algorithmParameter());
        } catch (UnsupportedOperationException e) {
            // The algorithm must be legacy
            checkState(algorithmDefinition.algorithmParameter().isEmpty(), "You cannot specify algorithm parameter on this legacy algorithm");
            // This weird construct is necessary due to legacy support
            return (ALGO) algorithmFactory.getClass().getMethod("apply", Object.class, Object.class).invoke(algorithmFactory, dependency, parameters);
        }
    }

    protected <DEPENDENCY> DEPENDENCY loadFeatureExtractionDependency(AlgorithmDefinition algorithmDefinition, File parameterFile, Map<String, AlgorithmInstance<?>> dependencyOverrides) throws MalformedAlgorithmException {
        Object dependencyFactory;
        Optional<JsonNode> hyperparameter;
        if(algorithmDefinition.vectorizerFactoryName() != null){
            // This algo is vectorizer based
            // For legacy reasons it is legal to also declare a transformer factory so we don't check if that's null
            dependencyFactory = instantiate(algorithmDefinition.vectorizerFactoryName());
            hyperparameter = algorithmDefinition.vectorizerParameter();
        } else {
            checkState(algorithmDefinition.transformerFactoryName()!=null, "You have to declare a vectorizer or a transformer");
            dependencyFactory = instantiate(algorithmDefinition.transformerFactoryName());
            hyperparameter = algorithmDefinition.transformerParameter();
        }

        if(parameterFile == null){
            Map<String, InputStream> parameters = ImmutableMap.of();
            return doLoadParameterizedDependency(algorithmDefinition, parameterFile, dependencyFactory, hyperparameter, parameters, dependencyOverrides);
        } else {
            try (ZipFile file = new ZipFile(parameterFile)) {
                Map<String, InputStream> parameters = AlgorithmUtils.extractParameters(algorithmDefinition.algorithmId(), file);
                return doLoadParameterizedDependency(algorithmDefinition, parameterFile, dependencyFactory, hyperparameter, parameters, dependencyOverrides);
            } catch (IOException e) {
                throw new MalformedAlgorithmException(e);
            }
        }
    }

    protected <DEPENDENCY, DEPENDENCY_FACTORY> DEPENDENCY doLoadParameterizedDependency(AlgorithmDefinition algorithmDefinition, File parameterFile, DEPENDENCY_FACTORY dependencyFactory, Optional<JsonNode> hyperparameter, Map<String, InputStream> parameters, Map<String, AlgorithmInstance<?>> dependencyOverrides) {
        DEPENDENCY dependency;

        if (dependencyFactory instanceof RankingTransformerFactory factory) {
            dependency = (DEPENDENCY) factory.create(executionContext(), hyperparameter, parameters);
        } else if (dependencyFactory instanceof RankingVectorizerFactory factory) {
            dependency = (DEPENDENCY) factory.create(executionContext(), hyperparameter, parameters);
        } else if (dependencyFactory instanceof CompositeTransformerFactory factory) {
            Map<AlgorithmId, AlgorithmInstance<?>> dependencies = loadDependencies(algorithmDefinition, parameterFile, dependencyOverrides);
            Map<String, AlgorithmInstance<?>> dependenciesWithOverrides = applyDependencyOverrides(withOnlyAlgorithmName(dependencies), dependencyOverrides);
            dependency = (DEPENDENCY) factory.create(executionContext(), hyperparameter, parameters, dependenciesWithOverrides);
        } else if (dependencyFactory instanceof CompositeVectorizerFactory factory) {
            Map<AlgorithmId, AlgorithmInstance<?>> dependencies = loadDependencies(algorithmDefinition, parameterFile, dependencyOverrides);
            Map<String, AlgorithmInstance<?>> dependenciesWithOverrides = applyDependencyOverrides(withOnlyAlgorithmName(dependencies), dependencyOverrides);
            dependency = (DEPENDENCY) factory.create(executionContext(), hyperparameter, parameters, dependenciesWithOverrides);
        } else if (dependencyFactory instanceof BiFunction) {
            BiFunction<Optional<JsonNode>, Map<String, InputStream>, DEPENDENCY> factory = (BiFunction<Optional<JsonNode>, Map<String, InputStream>, DEPENDENCY>) dependencyFactory;
            dependency = factory.apply(hyperparameter, parameters);
        } else {
            throw new MalformedAlgorithmException("Unknown dependency factory class type:" + dependencyFactory.getClass().getCanonicalName());
        }

        // Enable feature logging if requested (post-construction configuration)
        if (this.enableFeatureLogging) {
            enableFeatureLoggingOnTransformer(dependency, algorithmDefinition.algorithmId().algorithmName());
        }

        return dependency;
    }

    private void enableFeatureLoggingOnTransformer(Object transformer, String algorithmName) {
        if (transformer instanceof AuditableTransformer) {
            ((AuditableTransformer) transformer).setFeatureAuditEnabled(true, algorithmName);
            log.info("Feature auditing enabled for algorithm: {}", algorithmName);
        } else {
            log.warn("Feature auditing requested but algorithm '{}' uses '{}' which does not support feature auditing. Skipping feature logging for this dependency.",
                algorithmName, transformer.getClass().getName());
        }
    }

    protected Map<String, AlgorithmInstance<?>> withOnlyAlgorithmName(Map<AlgorithmId, AlgorithmInstance<?>> dependencies) {
        return ImmutableMap.copyOf(dependencies.entrySet().stream().collect(Collectors.toMap(
                e -> e.getKey().algorithmName(),
                Map.Entry::getValue
        )));
    }

    private Map<String, AlgorithmInstance<?>> applyDependencyOverrides(
            Map<String, AlgorithmInstance<?>> loadedDependencies,
            Map<String, AlgorithmInstance<?>> dependencyOverrides
    ) {
        Map<String, AlgorithmInstance<?>> result = new HashMap<>(loadedDependencies);
        result.putAll(dependencyOverrides);
        return result;
    }

    protected Map<AlgorithmId, AlgorithmInstance<?>> loadDependencies(AlgorithmDefinition algorithmDefinition, File parameterFile, Map<String, AlgorithmInstance<?>> dependencyOverrides) {
        return ImmutableMap.copyOf(algorithmDefinition.dependencyAlgorithmOverrides().entrySet().stream()
                .map(e -> loadAlgorithmInstance(e.getKey(), e.getValue(), parameterFile, dependencyOverrides))
                .collect(
                        toMap(
                                ai -> ai.algorithmDefinition().algorithmId(),
                                Function.identity()
                        )
                ));
    }

    protected static AlgorithmDefinition getUpdatedAlgoDef(Optional<JsonNode> algorithmDefinitionOverride, AlgorithmDefinition baseAlgoDefinition) {
        JsonNode merged = baseAlgoDefinition.rawAlgorithmDefinition();
        if(algorithmDefinitionOverride.isPresent()){
            merged = AlgorithmDefinitionOverrideUtils.applyOverride(merged, algorithmDefinitionOverride.orElseThrow());
        }
        try {
            return new AlgorithmDefinitionReader().parse(merged);
        } catch (IOException e) {
            throw new MalformedAlgorithmException(e);
        }
    }

    protected <T> T instantiate(String className){
        try {
            return (T)this.classLoader.loadClass(className).getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new MalformedAlgorithmException("Unable to instantiate:" + className, e);
        }
    }

}
