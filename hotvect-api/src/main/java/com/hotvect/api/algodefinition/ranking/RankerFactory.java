package com.hotvect.api.algodefinition.ranking;

import com.hotvect.api.algodefinition.common.NonCompositeAlgorithmFactory;
import com.hotvect.api.algorithms.Ranker;

public interface RankerFactory<DEPENDENCY, SHARED, ACTION> extends NonCompositeAlgorithmFactory<DEPENDENCY, Ranker<SHARED, ACTION>> {
}
