package com.eshioji.hotvect.api.algodefinition.ranking;

import com.eshioji.hotvect.api.algodefinition.common.ExampleDecoderFactory;
import com.eshioji.hotvect.api.codec.ranking.RankingExampleDecoder;
import com.eshioji.hotvect.api.codec.ranking.RankingExampleEncoder;
import com.eshioji.hotvect.api.data.ranking.RankingExample;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Optional;
import java.util.function.Function;

public interface RankingExampleDecoderFactory<SHARED, ACTION, OUTCOME> extends ExampleDecoderFactory<RankingExample<SHARED, ACTION, OUTCOME>> {
    @Override
    RankingExampleDecoder<SHARED, ACTION, OUTCOME> apply(Optional<JsonNode> hyperparameters);
}
