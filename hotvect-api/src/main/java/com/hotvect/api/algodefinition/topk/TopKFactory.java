package com.hotvect.api.algodefinition.topk;

import com.hotvect.api.algodefinition.common.NonCompositeAlgorithmFactory;
import com.hotvect.api.algorithms.TopK;

public interface TopKFactory<DEPENDENCY, SHARED, ACTION> extends NonCompositeAlgorithmFactory<DEPENDENCY, TopK<SHARED, ACTION>> {
}
