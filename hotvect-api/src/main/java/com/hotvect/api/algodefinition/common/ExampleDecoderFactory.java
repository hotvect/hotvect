package com.hotvect.api.algodefinition.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.codec.common.ExampleDecoder;
import com.hotvect.api.data.common.Example;

import java.util.Optional;
import java.util.function.Function;

public interface ExampleDecoderFactory<EXAMPLE extends Example> extends Function<Optional<JsonNode>, ExampleDecoder<EXAMPLE>> {
    @Override
    ExampleDecoder<EXAMPLE> apply(Optional<JsonNode> hyperparameter);
}
