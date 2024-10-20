package com.hotvect.api.algodefinition.ranking;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.common.ExampleDecoderFactory;
import com.hotvect.api.codec.ranking.RankingExampleDecoder;
import com.hotvect.api.data.ranking.RankingExample;

import java.util.Optional;

public interface RankingExampleDecoderFactory<SHARED, ACTION, OUTCOME> extends ExampleDecoderFactory<RankingExample<SHARED, ACTION, OUTCOME>> {
    @Override
    RankingExampleDecoder<SHARED, ACTION, OUTCOME> apply(Optional<JsonNode> hyperparameter);
}
