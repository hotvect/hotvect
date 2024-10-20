package com.hotvect.api.algodefinition.topk;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.common.ExampleDecoderFactory;
import com.hotvect.api.codec.topk.TopKExampleDecoder;
import com.hotvect.api.data.topk.TopKExample;

import java.util.Optional;

public interface TopKExampleDecoderFactory<SHARED, ACTION, OUTCOME> extends ExampleDecoderFactory<TopKExample<SHARED, ACTION, OUTCOME>> {
    @Override
    TopKExampleDecoder<SHARED, ACTION, OUTCOME> apply(Optional<JsonNode> hyperparameter);
}
