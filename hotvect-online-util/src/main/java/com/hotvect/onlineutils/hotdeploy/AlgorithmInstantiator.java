package com.hotvect.onlineutils.hotdeploy;

import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algorithms.Algorithm;
import com.hotvect.onlineutils.hotdeploy.util.MalformedAlgorithmException;

import java.io.File;

public interface AlgorithmInstantiator {
    <ALGO extends Algorithm> AlgorithmInstance<ALGO> load(String algorithmName, File parameterFile) throws MalformedAlgorithmException;

    <ALGO extends Algorithm> AlgorithmInstance<ALGO> load(AlgorithmDefinition algorithmDefinition, File parameterFile) throws MalformedAlgorithmException;

}
