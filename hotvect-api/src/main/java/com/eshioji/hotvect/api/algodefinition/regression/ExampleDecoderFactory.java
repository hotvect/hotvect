package com.eshioji.hotvect.api.algodefinition.regression;

import com.eshioji.hotvect.api.codec.regression.ExampleDecoder;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Optional;
import java.util.function.Function;

public interface ExampleDecoderFactory<RECORD> extends Function<Optional<JsonNode>, ExampleDecoder<RECORD>> {
    @Override
    ExampleDecoder<RECORD> apply(Optional<JsonNode> hyperparameters);
}
