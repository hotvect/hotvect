package com.hotvect.offlineutils.hotdeploy;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algodefinition.common.ExampleDecoderFactory;
import com.hotvect.api.algodefinition.common.RewardFunction;
import com.hotvect.api.algodefinition.common.RewardFunctionFactory;
import com.hotvect.api.codec.common.ExampleDecoder;
import com.hotvect.api.codec.common.ExampleEncoder;
import com.hotvect.api.data.common.Example;
import com.hotvect.api.data.OfflineRequest;
import com.hotvect.api.execution.ExecutionContext;
import com.hotvect.api.execution.InputSemantic;
import com.hotvect.api.execution.WorkloadMode;
import com.hotvect.onlineutils.hotdeploy.AlgorithmInstanceFactory;
import com.hotvect.onlineutils.hotdeploy.util.MalformedAlgorithmException;

import java.io.File;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

public class AlgorithmOfflineSupporterFactory extends AlgorithmInstanceFactory implements AlgorithmOfflineInstantiator {
    private static final ExecutionContext EXECUTION_CONTEXT = ExecutionContext.of(WorkloadMode.BATCH, InputSemantic.OFFLINE);

    public AlgorithmOfflineSupporterFactory(File algorithmJar, ClassLoader parent) throws MalformedAlgorithmException {
        super(algorithmJar, parent, EXECUTION_CONTEXT, false);
    }

    public AlgorithmOfflineSupporterFactory(File algorithmJar) throws MalformedAlgorithmException {
        super(algorithmJar, EXECUTION_CONTEXT, false);
    }

    public AlgorithmOfflineSupporterFactory(ClassLoader parent) throws MalformedAlgorithmException {
        super(parent, EXECUTION_CONTEXT, false);
    }

    public <OUTCOME> RewardFunction<OUTCOME> getRewardFunction(AlgorithmDefinition algorithmDefinition) throws MalformedAlgorithmException {
        String factoryName = algorithmDefinition.rewardFunctionFactoryName();
        RewardFunctionFactory<OUTCOME> rewardFunctionFactory = instantiate(factoryName);
        return rewardFunctionFactory.get();
    }

    public <EXAMPLE extends Example<? extends OfflineRequest, ?>> ExampleDecoder<EXAMPLE> getTrainDecoder(AlgorithmDefinition algorithmDefinition) throws MalformedAlgorithmException {
        String factoryName = algorithmDefinition.decoderFactoryName();
        Optional<JsonNode> parameter = algorithmDefinition.trainDecoderParameter();
        ExampleDecoderFactory<EXAMPLE> decoderFactory = instantiate(factoryName);
        return decoderFactory.apply(algorithmDefinition.trainDecoderParameter());
    }

    @Override
    public <DEPENDENCY> DEPENDENCY loadFeatureExtractionDependency(AlgorithmDefinition algorithmDefinition, File parameterFile, Map<String, AlgorithmInstance<?>> dependencyOverrides) throws MalformedAlgorithmException {
        return super.loadFeatureExtractionDependency(algorithmDefinition, parameterFile, dependencyOverrides);
    }

    public <E> E getFeatureExtractionDependency(AlgorithmDefinition algorithmDefinition, File parameterFile) throws MalformedAlgorithmException {
        return loadFeatureExtractionDependency(algorithmDefinition, parameterFile, Map.of());
    }

    public <EXAMPLE extends Example<? extends OfflineRequest, ?>> ExampleEncoder<EXAMPLE> getTrainEncoder(AlgorithmDefinition algorithmDefinition, File parameters) throws MalformedAlgorithmException {
        String factoryName = algorithmDefinition.encoderFactoryName();
        RewardFunction<?> rewardFunction = this.getRewardFunction(algorithmDefinition);

        Object dependency = loadFeatureExtractionDependency(algorithmDefinition, parameters, Map.of());
        BiFunction encoderFactory = instantiate(factoryName);

        return (ExampleEncoder<EXAMPLE>) encoderFactory.apply(dependency, rewardFunction);
    }

    public <EXAMPLE extends Example<? extends OfflineRequest, ?>> ExampleDecoder<EXAMPLE> getTestDecoder(AlgorithmDefinition algorithmDefinition) throws MalformedAlgorithmException {
        String factoryName = algorithmDefinition.decoderFactoryName();
        Optional<JsonNode> hyperparameter = algorithmDefinition.testDecoderParameter();
        ExampleDecoderFactory<EXAMPLE> factory = instantiate(factoryName);
        return factory.apply(hyperparameter);
    }
}
