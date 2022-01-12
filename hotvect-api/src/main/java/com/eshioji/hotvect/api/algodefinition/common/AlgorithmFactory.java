package com.eshioji.hotvect.api.algodefinition.common;

import com.eshioji.hotvect.api.algorithms.Algorithm;
import com.eshioji.hotvect.api.vectorization.Vectorizer;

import java.io.InputStream;
import java.util.Map;
import java.util.function.BiFunction;

public interface AlgorithmFactory<VEC extends Vectorizer, ALGO extends Algorithm> extends BiFunction<VEC, Map<String, InputStream>, ALGO> {
}
