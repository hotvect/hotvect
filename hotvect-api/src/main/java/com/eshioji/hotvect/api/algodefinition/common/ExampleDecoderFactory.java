package com.eshioji.hotvect.api.algodefinition.common;

import com.eshioji.hotvect.api.codec.common.ExampleDecoder;
import com.eshioji.hotvect.api.codec.scoring.ScoringExampleDecoder;
import com.eshioji.hotvect.api.data.common.Example;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Optional;
import java.util.function.Function;

public interface ExampleDecoderFactory<EXAMPLE extends Example> extends Function<Optional<JsonNode>, ExampleDecoder<EXAMPLE>> {
}
