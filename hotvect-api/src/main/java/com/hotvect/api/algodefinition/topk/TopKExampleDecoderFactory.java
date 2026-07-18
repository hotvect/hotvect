package com.hotvect.api.algodefinition.topk;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.common.ExampleDecoderFactory;
import com.hotvect.api.codec.topk.TopKExampleDecoder;
import com.hotvect.api.data.topk.TopKExample;

import java.util.Optional;

public interface TopKExampleDecoderFactory<SHARED, ACTION, OUTCOME> extends ExampleDecoderFactory<TopKExample<SHARED, ACTION, OUTCOME>> {
    /**
     * @deprecated Use create(...).
     */
    @Deprecated(forRemoval = true, since = "10.40.0")
    @Override
    TopKExampleDecoder<SHARED, ACTION, OUTCOME> apply(Optional<JsonNode> hyperparameter);

    @SuppressWarnings("unchecked")
    @Override
    default TopKExampleDecoder<SHARED, ACTION, OUTCOME> create(Optional<JsonNode> hyperparameter) {
        return (TopKExampleDecoder<SHARED, ACTION, OUTCOME>) ExampleDecoderFactory.super.create(hyperparameter);
    }
}
