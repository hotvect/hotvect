package com.hotvect.api.algodefinition.scoring;

import com.hotvect.api.algodefinition.common.AlgorithmFactory;
import com.hotvect.api.algorithms.Scorer;
import com.hotvect.api.vectorization.ScoringVectorizer;

import java.io.InputStream;
import java.util.Map;

public interface ScorerFactory<RECORD> extends AlgorithmFactory<ScoringVectorizer<RECORD>, Scorer<RECORD>> {
    @Override
    Scorer<RECORD> apply(ScoringVectorizer<RECORD> scoringVectorizer, Map<String, InputStream> predictParameters);
}
