package com.hotvect.api.algodefinition.topk;

import com.hotvect.api.algodefinition.common.CompositeAlgorithmFactory;
import com.hotvect.api.algorithms.TopK;

public interface CompositeTopKFactory<SHARED,ACTION> extends CompositeAlgorithmFactory<TopK<SHARED, ACTION>> {
}
