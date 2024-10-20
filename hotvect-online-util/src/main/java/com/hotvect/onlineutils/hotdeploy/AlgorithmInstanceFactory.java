package com.hotvect.onlineutils.hotdeploy;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.algodefinition.AlgorithmId;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algodefinition.AlgorithmParameterMetadata;
import com.hotvect.api.algodefinition.common.AlgorithmFactory;
import com.hotvect.api.algodefinition.common.CompositeAlgorithmFactory;
import com.hotvect.api.algodefinition.common.NonCompositeAlgorithmFactory;
import com.hotvect.api.algodefinition.common.SimpleAlgorithmFactory;
import com.hotvect.api.algodefinition.ranking.CompositeRankerFactory;
import com.hotvect.api.algodefinition.ranking.CompositeRankingVectorizerFactory;
import com.hotvect.api.algorithms.Algorithm;
import com.hotvect.onlineutils.hotdeploy.util.AlgorithmUtils;
import com.hotvect.onlineutils.hotdeploy.util.MalformedAlgorithmException;
import com.hotvect.utils.AlgorithmDefinitionReader;
import org.apache.commons.compress.archivers.zip.ZipFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkState;
import static com.hotvect.utils.JsonUtils.deepMergeJsonNodeWithArrayReplacement;
import static java.util.stream.Collectors.toMap;

public class AlgorithmInstanceFactory extends HotvectFactory implements AlgorithmInstantiator {
    private final boolean strictAlgorithmVersionCheck;

    public AlgorithmInstanceFactory(File algorithmJar, boolean strictAlgorithmVersionCheck) throws MalformedAlgorithmException {
        super(algorithmJar);
        this.strictAlgorithmVersionCheck = strictAlgorithmVersionCheck;
    }

    public AlgorithmInstanceFactory(ClassLoader classLoader, boolean strictAlgorithmVersionCheck) throws MalformedAlgorithmException {
        super(classLoader);
        this.strictAlgorithmVersionCheck = strictAlgorithmVersionCheck;
    }

    public AlgorithmInstanceFactory(File algorithmJar, ClassLoader parent, boolean strictAlgorithmVersionCheck) throws MalformedAlgorithmException {
        super(algorithmJar, parent);
        this.strictAlgorithmVersionCheck = strictAlgorithmVersionCheck;
    }

    @Override
    public <ALGO extends Algorithm> AlgorithmInstance<ALGO> load(String algorithmName, File parameterFile) throws MalformedAlgorithmException {
        AlgorithmDefinition algorithmDefinition = AlgorithmUtils.readAlgorithmDefinitionFromClassLoader(algorithmName, this.classLoader);
        return load(algorithmDefinition, parameterFile);
    }

    @Override
    public <ALGO extends Algorithm> AlgorithmInstance<ALGO> load(AlgorithmDefinition algorithmDefinition, File parameterFile) throws MalformedAlgorithmException {
        AlgorithmParameterMetadata parameterMetadata = AlgorithmUtils.readAlgorithmParameterMetadata(algorithmDefinition.getAlgorithmId(), parameterFile, strictAlgorithmVersionCheck);

        if (strictAlgorithmVersionCheck) {
            checkState(parameterMetadata.getAlgorithmId().equals(algorithmDefinition.getAlgorithmId()));
        } else {
            checkState(parameterMetadata.getAlgorithmId().getAlgorithmName().equals(algorithmDefinition.getAlgorithmId().getAlgorithmName()));
        }

        Object algorithmFactory = instantiate(algorithmDefinition.getAlgorithmFactoryName());
        if (algorithmFactory instanceof NonCompositeAlgorithmFactory) {
            // We are a non-composite algorithm
            return (AlgorithmInstance<ALGO>) loadAlgorithmInstance(algorithmDefinition, parameterFile);
        } else if (algorithmFactory instanceof CompositeAlgorithmFactory) {
            // We are a composite algorithm
            CompositeAlgorithmFactory<ALGO> algoFactory = (CompositeAlgorithmFactory<ALGO>) algorithmFactory;
            Map<String, AlgorithmInstance<?>> dependencies = withOnlyAlgorithmName(loadDependencies(algorithmDefinition, parameterFile));
            try (ZipFile file = new ZipFile(parameterFile)) {
                Map<String, InputStream> parameters = AlgorithmUtils.extractParameters(algorithmDefinition.getAlgorithmId(), file);
                ALGO algo = algoFactory.apply(algorithmDefinition.getAlgorithmParameter(),  parameters, dependencies);
                AlgorithmDefinition algoDefWithResolvedDeps = withResolvedDependencies(algorithmDefinition, dependencies);
                return new AlgorithmInstance<>(algoDefWithResolvedDeps, parameterMetadata, algo);
            } catch (Exception e) {
                throw new MalformedAlgorithmException(e);
            }
        } else if (algorithmFactory instanceof SimpleAlgorithmFactory) {
            // We are a simple algorithm
            SimpleAlgorithmFactory<ALGO> algoFactory = (SimpleAlgorithmFactory<ALGO>) algorithmFactory;
            ALGO algo = algoFactory.apply(algorithmDefinition.getAlgorithmParameter());
            return new AlgorithmInstance<>(algorithmDefinition, null, algo);
        } else if(algorithmFactory instanceof CompositeRankerFactory){
            CompositeRankerFactory<?, ?> algoFactory = (CompositeRankerFactory) algorithmFactory;
            Map<String, AlgorithmInstance<?>> dependencies = withOnlyAlgorithmName(loadDependencies(algorithmDefinition, parameterFile));
            try (ZipFile file = new ZipFile(parameterFile)) {
                Map<String, InputStream> parameters = AlgorithmUtils.extractParameters(algorithmDefinition.getAlgorithmId(), file);
                ALGO algo =  (ALGO)algoFactory.apply(algorithmDefinition.getAlgorithmParameter(), parameters, dependencies);
                AlgorithmDefinition algoDefWithResolvedDeps = withResolvedDependencies(algorithmDefinition, dependencies);
                return new AlgorithmInstance<>(algoDefWithResolvedDeps, parameterMetadata, algo);
            } catch (Exception e) {
                throw new MalformedAlgorithmException(e);
            }
        } else {
            // We don't know this class
            throw new MalformedAlgorithmException(String.format("Specified algorithm factory %s does not conform to allowed interfaces %s", algorithmDefinition.getAlgorithmFactoryName(), ImmutableSet.of(NonCompositeAlgorithmFactory.class.getCanonicalName(), CompositeAlgorithmFactory.class.getCanonicalName())));
        }
    }

    private AlgorithmDefinition withResolvedDependencies(AlgorithmDefinition algorithmDefinition, Map<String, AlgorithmInstance<?>> dependencies) {
        Map<String, AlgorithmDefinition> dependencyAlgoDefs = ImmutableMap.copyOf(Maps.transformValues(dependencies, AlgorithmInstance::getAlgorithmDefinition));
        return algorithmDefinition.replace(dependencyAlgoDefs);
    }

    protected AlgorithmInstance<?> loadAlgorithmInstance(AlgorithmDefinition algorithmDefinition, File parameterFile) throws MalformedAlgorithmException {
        AlgorithmParameterMetadata algorithmParameterMetadata = AlgorithmUtils.readAlgorithmParameterMetadata(algorithmDefinition.getAlgorithmId(), parameterFile, strictAlgorithmVersionCheck);
        return loadAlgorithmInstance(algorithmDefinition, algorithmParameterMetadata, parameterFile);
    }

    protected AlgorithmInstance<?> loadAlgorithmInstance(String algorithmName, Optional<JsonNode> algorithmDefinitionOverride, File parameterFile) throws MalformedAlgorithmException {
        AlgorithmDefinition baseAlgoDefinition = AlgorithmUtils.readAlgorithmDefinitionFromClassLoader(algorithmName, this.classLoader);
        AlgorithmParameterMetadata algorithmParameterMetadata = AlgorithmUtils.readAlgorithmParameterMetadata(baseAlgoDefinition.getAlgorithmId(), parameterFile, strictAlgorithmVersionCheck);

        AlgorithmDefinition updatedAlgoDef = getUpdatedAlgoDef(algorithmDefinitionOverride, baseAlgoDefinition);

        return loadAlgorithmInstance(updatedAlgoDef, algorithmParameterMetadata, parameterFile);
    }


    protected AlgorithmInstance<?> loadAlgorithmInstance(
            AlgorithmDefinition algorithmDefinition,
            AlgorithmParameterMetadata algorithmParameterMetadata,
            File parameterFile
    ) throws MalformedAlgorithmException {
        AlgorithmFactory algorithmFactory = instantiate(algorithmDefinition.getAlgorithmFactoryName());
        if(algorithmFactory instanceof NonCompositeAlgorithmFactory){
            var nonCompositeFactory = (NonCompositeAlgorithmFactory)algorithmFactory;
            Object dependency = loadFeatureExtractionDependency(algorithmDefinition, parameterFile);
            Algorithm algorithm = loadParameterizedAlgorithm(algorithmDefinition, dependency, nonCompositeFactory, parameterFile);
            return new AlgorithmInstance<>(algorithmDefinition, algorithmParameterMetadata, algorithm);
        } if (algorithmFactory instanceof CompositeAlgorithmFactory) {
            var compositeAlgoFactory = (CompositeAlgorithmFactory) algorithmFactory;
            Map<AlgorithmId, AlgorithmInstance<?>> dependencies = loadDependencies(algorithmDefinition, parameterFile);
            // TODO parameter files for a composite algorithm are not supported yet
            var algorithm = compositeAlgoFactory.apply(algorithmDefinition.getAlgorithmParameter(), ImmutableMap.of(), withOnlyAlgorithmName(dependencies));
            AlgorithmDefinition withResolvedDependencies = withResolvedDependencies(algorithmDefinition, withOnlyAlgorithmName(dependencies));
            return new AlgorithmInstance<>(withResolvedDependencies, algorithmParameterMetadata, algorithm);
        } else {
            throw new MalformedAlgorithmException("Unknown algorithm factory type:" + algorithmFactory.getClass().getCanonicalName());
        }
    }


    protected <DEPENDENCY, ALGO extends Algorithm> ALGO loadParameterizedAlgorithm(AlgorithmDefinition algorithmDefinition, DEPENDENCY dependency, NonCompositeAlgorithmFactory algorithmFactory, File parameterFile) throws MalformedAlgorithmException {
        try (ZipFile file = new ZipFile(parameterFile)) {
            Map<String, InputStream> parameters = AlgorithmUtils.extractParameters(algorithmDefinition.getAlgorithmId(), file);

            try {
                // Try non legacy first
                return (ALGO) algorithmFactory.apply(dependency, parameters, algorithmDefinition.getAlgorithmParameter());
            }catch (UnsupportedOperationException e){
                // The algorithm must be legacy
                checkState(algorithmDefinition.getAlgorithmParameter().isEmpty(), "You cannot specify algorithm parameter on this legacy algorithm");
                // This weird construct is necessary due to legacy support
                return (ALGO) algorithmFactory.getClass().getMethod("apply", Object.class, Object.class).invoke(algorithmFactory, dependency, parameters);
            }

        } catch (Exception e) {
            throw new MalformedAlgorithmException(e);
        }
    }

    protected <DEPENDENCY> DEPENDENCY loadFeatureExtractionDependency(AlgorithmDefinition algorithmDefinition, File parameterFile) throws MalformedAlgorithmException {
        Object dependencyFactory;
        Optional<JsonNode> hyperparameter;
        if(algorithmDefinition.getVectorizerFactoryName() != null){
            // This algo is vectorizer based
            // For legacy reasons it is legal to also declare a transformer factory so we don't check if that's null
            dependencyFactory = instantiate(algorithmDefinition.getVectorizerFactoryName());
            hyperparameter = algorithmDefinition.getVectorizerParameter();
        } else {
            checkState(algorithmDefinition.getTransformerFactoryName()!=null, "You have to declare a vectorizer or a transformer");
            dependencyFactory = instantiate(algorithmDefinition.getTransformerFactoryName());
            hyperparameter = algorithmDefinition.getTransformerParameter();
        }



        if(parameterFile == null){
            Map<String, InputStream> parameters = ImmutableMap.of();
            return doLoadParameterizedDependency(algorithmDefinition, parameterFile, dependencyFactory, hyperparameter, parameters);
        } else {
            try (ZipFile file = new ZipFile(parameterFile)) {
                Map<String, InputStream> parameters = AlgorithmUtils.extractParameters(algorithmDefinition.getAlgorithmId(), file);
                return doLoadParameterizedDependency(algorithmDefinition, parameterFile, dependencyFactory, hyperparameter, parameters);
            } catch (IOException e) {
                throw new MalformedAlgorithmException(e);
            }
        }
    }

    protected <DEPENDENCY, DEPENDENCY_FACTORY> DEPENDENCY doLoadParameterizedDependency(AlgorithmDefinition algorithmDefinition, File parameterFile, DEPENDENCY_FACTORY dependencyFactory, Optional<JsonNode> hyperparameter, Map<String, InputStream> parameters) {
        if(dependencyFactory instanceof BiFunction){
            BiFunction<Optional<JsonNode>, Map<String, InputStream>, DEPENDENCY> factory = (BiFunction<Optional<JsonNode>, Map<String, InputStream>, DEPENDENCY>) dependencyFactory;
            return factory.apply(hyperparameter, parameters);
        } else if (dependencyFactory instanceof com.hotvect.api.algodefinition.ranking.RankingTransformerFactory){
            com.hotvect.api.algodefinition.ranking.RankingTransformerFactory factory = (com.hotvect.api.algodefinition.ranking.RankingTransformerFactory) dependencyFactory;
            return (DEPENDENCY) factory.apply(hyperparameter,parameters);
        } else if (dependencyFactory instanceof com.hotvect.api.transformation.CompositeTransformerFactory){
            com.hotvect.api.transformation.CompositeTransformerFactory factory = (com.hotvect.api.transformation.CompositeTransformerFactory) dependencyFactory;
            Map<String, AlgorithmInstance<?>> dependencies = withOnlyAlgorithmName(loadDependencies(algorithmDefinition, parameterFile));
            return (DEPENDENCY) factory.apply(hyperparameter, parameters, dependencies);
        } else if (dependencyFactory instanceof CompositeRankingVectorizerFactory){
            CompositeRankingVectorizerFactory factory = (CompositeRankingVectorizerFactory) dependencyFactory;
            Map<String, AlgorithmInstance<?>> dependencies = withOnlyAlgorithmName(loadDependencies(algorithmDefinition, parameterFile));
            return (DEPENDENCY) factory.apply(hyperparameter, parameters, dependencies);
        } else {
            throw new MalformedAlgorithmException("Unknown dependency factory class type:" + dependencyFactory.getClass().getCanonicalName());
        }
    }
    protected Map<String, AlgorithmInstance<?>> withOnlyAlgorithmName(Map<AlgorithmId, AlgorithmInstance<?>> dependencies) {
        return ImmutableMap.copyOf(dependencies.entrySet().stream().collect(Collectors.toMap(
                e -> e.getKey().getAlgorithmName(),
                Map.Entry::getValue
        )));
    }

    protected Map<AlgorithmId, AlgorithmInstance<?>> loadDependencies(AlgorithmDefinition algorithmDefinition, File parameterFile) {
        return ImmutableMap.copyOf(algorithmDefinition.getDependencyAlgorithmOverrides().entrySet().stream()
                .map(e -> loadAlgorithmInstance(e.getKey(), e.getValue(), parameterFile))
                .collect(
                        toMap(
                                ai -> ai.getAlgorithmDefinition().getAlgorithmId(),
                                Function.identity()
                        )
                ));
    }

    protected static AlgorithmDefinition getUpdatedAlgoDef(Optional<JsonNode> algorithmDefinitionOverride, AlgorithmDefinition baseAlgoDefinition) {
        JsonNode merged = baseAlgoDefinition.getRawAlgorithmDefinition();
        if(algorithmDefinitionOverride.isPresent()){
            merged = deepMergeJsonNodeWithArrayReplacement(merged, algorithmDefinitionOverride.orElseThrow());
        }
        try {
            return new AlgorithmDefinitionReader().parse(merged);
        } catch (IOException e) {
            throw new MalformedAlgorithmException(e);
        }
    }


    protected <T> T instantiate(String className){
        try {
            return (T)Class.forName(className, true, this.classLoader).getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new MalformedAlgorithmException("Unable to instantiate:" + className, e);
        }
    }
}
