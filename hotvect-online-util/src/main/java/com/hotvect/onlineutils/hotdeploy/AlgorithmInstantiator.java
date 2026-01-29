package com.hotvect.onlineutils.hotdeploy;

import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algorithms.Algorithm;
import com.hotvect.onlineutils.hotdeploy.util.MalformedAlgorithmException;

import java.io.File;
import java.util.Map;

public interface AlgorithmInstantiator {
    <ALGO extends Algorithm> AlgorithmInstance<ALGO> load(String algorithmName, File parameterFile, Map<String, AlgorithmInstance<?>> dependencyOverrides) throws MalformedAlgorithmException;

    <ALGO extends Algorithm> AlgorithmInstance<ALGO> load(AlgorithmDefinition algorithmDefinition, File parameterFile, Map<String, AlgorithmInstance<?>> dependencyOverrides) throws MalformedAlgorithmException;

}
