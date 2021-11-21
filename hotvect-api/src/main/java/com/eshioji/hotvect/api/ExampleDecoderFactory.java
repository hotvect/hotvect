package com.eshioji.hotvect.api;

import com.eshioji.hotvect.api.codec.ExampleDecoder;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

public interface ExampleDecoderFactory<R> extends Function<Optional<JsonNode>, ExampleDecoder<R>> {
    @Override
    ExampleDecoder<R> apply(Optional<JsonNode> parameters);
}
