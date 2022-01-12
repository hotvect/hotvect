package com.eshioji.hotvect.api.algodefinition.scoring;

import com.eshioji.hotvect.api.algodefinition.common.AlgorithmFactory;
import com.eshioji.hotvect.api.algorithms.Scorer;
import com.eshioji.hotvect.api.vectorization.ScoringVectorizer;

import java.io.InputStream;
import java.util.Map;
import java.util.function.BiFunction;

public interface ScorerFactory<RECORD> extends AlgorithmFactory<ScoringVectorizer<RECORD>, Scorer<RECORD>> {
    @Override
    Scorer<RECORD> apply(ScoringVectorizer<RECORD> scoringVectorizer, Map<String, InputStream> predictParameters);
}
