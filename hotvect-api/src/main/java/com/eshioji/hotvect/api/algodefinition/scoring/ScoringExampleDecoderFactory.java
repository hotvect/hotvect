package com.eshioji.hotvect.api.algodefinition.scoring;

import com.eshioji.hotvect.api.algodefinition.common.ExampleDecoderFactory;
import com.eshioji.hotvect.api.codec.common.ExampleDecoder;
import com.eshioji.hotvect.api.codec.scoring.ScoringExampleDecoder;
import com.eshioji.hotvect.api.data.scoring.ScoringExample;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Optional;
import java.util.function.Function;

public interface ScoringExampleDecoderFactory<RECORD, OUTCOME> extends ExampleDecoderFactory<ScoringExample<RECORD, OUTCOME>> {
    @Override
    ScoringExampleDecoder<RECORD, OUTCOME> apply(Optional<JsonNode> jsonNode);
}
