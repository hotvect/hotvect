package com.hotvect.api.algodefinition.scoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.common.ExampleDecoderFactory;
import com.hotvect.api.codec.scoring.ScoringExampleDecoder;
import com.hotvect.api.data.scoring.ScoringExample;

import java.util.Optional;

public interface ScoringExampleDecoderFactory<RECORD, OUTCOME> extends ExampleDecoderFactory<ScoringExample<RECORD, OUTCOME>> {
    @Override
    ScoringExampleDecoder<RECORD, OUTCOME> apply(Optional<JsonNode> hyperparameter);
}
