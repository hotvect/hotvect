package com.hotvect.api.algodefinition.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.codec.common.ExampleDecoder;
import com.hotvect.api.data.OfflineRequest;
import com.hotvect.api.data.common.Example;

import java.util.Optional;
import java.util.function.Function;

/**
 * Factory interface for creating Example decoders.
 * Since Examples are always offline data (containing outcomes),
 * they always use OfflineRequest implementations.
 */
public interface ExampleDecoderFactory<EXAMPLE extends Example<? extends OfflineRequest, ?>> extends Function<Optional<JsonNode>, ExampleDecoder<EXAMPLE>> {
    @Override
    ExampleDecoder<EXAMPLE> apply(Optional<JsonNode> hyperparameter);
}
