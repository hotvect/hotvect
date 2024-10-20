package com.hotvect.offlineutils.hotdeploy;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.algodefinition.common.ExampleDecoderFactory;
import com.hotvect.api.algodefinition.common.RewardFunction;
import com.hotvect.api.algodefinition.common.RewardFunctionFactory;
import com.hotvect.api.codec.common.ExampleDecoder;
import com.hotvect.api.codec.common.ExampleEncoder;
import com.hotvect.api.data.common.Example;
import com.hotvect.onlineutils.hotdeploy.AlgorithmInstanceFactory;
import com.hotvect.onlineutils.hotdeploy.util.MalformedAlgorithmException;

import java.io.File;
import java.util.Optional;
import java.util.function.BiFunction;

public class AlgorithmOfflineSupporterFactory extends AlgorithmInstanceFactory implements AlgorithmOfflineInstantiator {
    public AlgorithmOfflineSupporterFactory(File algorithmJar, ClassLoader parent) throws MalformedAlgorithmException {
        super(algorithmJar, parent, false);
    }

    public AlgorithmOfflineSupporterFactory(File algorithmJar) throws MalformedAlgorithmException {
        super(algorithmJar, false);
    }

    public AlgorithmOfflineSupporterFactory(ClassLoader parent) throws MalformedAlgorithmException {
        super(parent, false);
    }

    public <OUTCOME> RewardFunction<OUTCOME> getRewardFunction(AlgorithmDefinition algorithmDefinition) throws MalformedAlgorithmException {
        String factoryName = algorithmDefinition.getRewardFunctionFactoryName();
        RewardFunctionFactory<OUTCOME> rewardFunctionFactory = instantiate(factoryName);
        return rewardFunctionFactory.get();
    }

    public <EXAMPLE extends Example> ExampleDecoder<EXAMPLE> getTrainDecoder(AlgorithmDefinition algorithmDefinition) throws MalformedAlgorithmException {
        String factoryName = algorithmDefinition.getDecoderFactoryName();
        Optional<JsonNode> parameter = algorithmDefinition.getTrainDecoderParameter();
        ExampleDecoderFactory<EXAMPLE> decoderFactory = instantiate(factoryName);
        return decoderFactory.apply(algorithmDefinition.getTrainDecoderParameter());
    }

    @Override
    public <DEPENDENCY> DEPENDENCY loadFeatureExtractionDependency(AlgorithmDefinition algorithmDefinition, File parameterFile) throws MalformedAlgorithmException {
        return super.loadFeatureExtractionDependency(algorithmDefinition, parameterFile);
    }

    public <E> E getFeatureExtractionDependency(AlgorithmDefinition algorithmDefinition, File parameterFile) throws MalformedAlgorithmException {
        return loadFeatureExtractionDependency(algorithmDefinition, parameterFile);
    }

    public <EXAMPLE extends Example> ExampleEncoder<EXAMPLE> getTrainEncoder(AlgorithmDefinition algorithmDefinition, File parameters) throws MalformedAlgorithmException {
        String factoryName = algorithmDefinition.getEncoderFactoryName();
        RewardFunction<?> rewardFunction = this.getRewardFunction(algorithmDefinition);

        Object dependency = loadFeatureExtractionDependency(algorithmDefinition, parameters);
        BiFunction encoderFactory = instantiate(factoryName);

        return (ExampleEncoder<EXAMPLE>) encoderFactory.apply(dependency, rewardFunction);
    }

    public <EXAMPLE extends Example> ExampleDecoder<EXAMPLE> getTestDecoder(AlgorithmDefinition algorithmDefinition) throws MalformedAlgorithmException {
        String factoryName = algorithmDefinition.getDecoderFactoryName();
        Optional<JsonNode> hyperparameter = algorithmDefinition.getTestDecoderParameter();
        ExampleDecoderFactory<EXAMPLE> factory = instantiate(factoryName);
        return factory.apply(hyperparameter);
    }
}
