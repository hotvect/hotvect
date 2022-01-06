package com.eshioji.hotvect.api.algodefinition.ccb;

import com.eshioji.hotvect.api.codec.ccb.ExampleDecoder;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Optional;
import java.util.function.Function;

public interface ExampleDecoderFactory<SHARED, ACTION, OUTCOME> extends Function<Optional<JsonNode>, ExampleDecoder<SHARED, ACTION, OUTCOME>> {
    @Override
    ExampleDecoder<SHARED, ACTION, OUTCOME> apply(Optional<JsonNode> hyperparameters);
}
