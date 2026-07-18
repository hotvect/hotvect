package com.hotvect.api.algodefinition.ranking;

import com.hotvect.api.algodefinition.common.NonCompositeAlgorithmFactory;
import com.hotvect.api.algorithms.BulkScorer;

public interface BulkScorerFactory<DEPENDENCY, SHARED, ACTION> extends NonCompositeAlgorithmFactory<DEPENDENCY, BulkScorer<SHARED, ACTION>> {
}
