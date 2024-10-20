package com.hotvect.offlineutils.hotdeploy;

import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.algodefinition.common.RewardFunction;
import com.hotvect.api.codec.common.ExampleDecoder;
import com.hotvect.api.codec.common.ExampleEncoder;
import com.hotvect.api.data.common.Example;
import com.hotvect.onlineutils.hotdeploy.AlgorithmInstantiator;
import com.hotvect.onlineutils.hotdeploy.util.MalformedAlgorithmException;

import java.io.File;

public interface AlgorithmOfflineInstantiator extends AlgorithmInstantiator {
    <OUTCOME> RewardFunction<OUTCOME> getRewardFunction(AlgorithmDefinition algorithmDefinition) throws MalformedAlgorithmException;

    <EXAMPLE extends Example> ExampleDecoder<EXAMPLE> getTestDecoder(AlgorithmDefinition algorithmDefinition) throws MalformedAlgorithmException;

    <EXAMPLE extends Example> ExampleEncoder<EXAMPLE> getTrainEncoder(AlgorithmDefinition algorithmDefinition, File parameters) throws MalformedAlgorithmException;

    <EXAMPLE extends Example> ExampleDecoder<EXAMPLE> getTrainDecoder(AlgorithmDefinition algorithmDefinition) throws MalformedAlgorithmException;

    <DEPENDENCY> DEPENDENCY loadFeatureExtractionDependency(AlgorithmDefinition algorithmDefinition, File parameterFile) throws MalformedAlgorithmException;

}
