package com.eshioji.hotvect.api.algodefinition.ccb;

import com.eshioji.hotvect.api.algodefinition.common.ExampleDecoderFactory;
import com.eshioji.hotvect.api.codec.ccb.CcbRankingExampleDecoder;
import com.eshioji.hotvect.api.data.ccb.CcbRankingExample;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Optional;
import java.util.function.Function;

public interface CcbRankingExampleDecoderFactory<SHARED, ACTION, OUTCOME> extends ExampleDecoderFactory<CcbRankingExample<SHARED, ACTION, OUTCOME>> {
    @Override
    CcbRankingExampleDecoder<SHARED, ACTION, OUTCOME> apply(Optional<JsonNode> hyperparameters);
}
