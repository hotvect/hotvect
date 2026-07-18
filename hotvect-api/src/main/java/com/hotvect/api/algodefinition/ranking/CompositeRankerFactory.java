package com.hotvect.api.algodefinition.ranking;

import com.hotvect.api.algodefinition.common.CompositeAlgorithmFactory;
import com.hotvect.api.algorithms.Ranker;

public interface CompositeRankerFactory<SHARED,ACTION> extends CompositeAlgorithmFactory<Ranker<SHARED, ACTION>> {
}
