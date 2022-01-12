package com.eshioji.hotvect.api.algodefinition.scoring;

import com.eshioji.hotvect.api.algodefinition.common.ExampleEncoderFactory;
import com.eshioji.hotvect.api.algodefinition.common.RewardFunction;
import com.eshioji.hotvect.api.codec.common.ExampleEncoder;
import com.eshioji.hotvect.api.codec.scoring.ScoringExampleEncoder;
import com.eshioji.hotvect.api.data.scoring.ScoringExample;
import com.eshioji.hotvect.api.vectorization.ScoringVectorizer;

import java.util.function.Function;

public interface ScoringExampleEncoderFactory<RECORD, OUTCOME> extends ExampleEncoderFactory<ScoringExample<RECORD, OUTCOME>, ScoringVectorizer<RECORD>, OUTCOME> {
    @Override
    ScoringExampleEncoder<RECORD, OUTCOME> apply(ScoringVectorizer<RECORD> vectorizer, RewardFunction<OUTCOME> rewardFunction);
}
