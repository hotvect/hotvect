package com.hotvect.api.algodefinition.scoring;

import com.hotvect.api.algodefinition.common.ExampleEncoderFactory;
import com.hotvect.api.algodefinition.common.RewardFunction;
import com.hotvect.api.codec.scoring.ScoringExampleEncoder;
import com.hotvect.api.data.scoring.ScoringExample;
import com.hotvect.api.vectorization.ScoringVectorizer;

public interface ScoringExampleEncoderFactory<RECORD, OUTCOME> extends ExampleEncoderFactory<ScoringExample<RECORD, OUTCOME>, ScoringVectorizer<RECORD>, OUTCOME> {
    @Override
    ScoringExampleEncoder<RECORD, OUTCOME> apply(ScoringVectorizer<RECORD> vectorizer, RewardFunction<OUTCOME> rewardFunction);
}
