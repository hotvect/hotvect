package com.hotvect.api.algodefinition.ranking;

import com.hotvect.api.algodefinition.common.CompositeAlgorithmFactory;
import com.hotvect.api.algorithms.BulkScorer;

public interface CompositeBulkScorerFactory<SHARED,ACTION> extends CompositeAlgorithmFactory<BulkScorer<SHARED, ACTION>> {
}
