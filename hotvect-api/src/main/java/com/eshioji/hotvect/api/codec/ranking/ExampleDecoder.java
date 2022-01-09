package com.eshioji.hotvect.api.codec.ranking;

import com.eshioji.hotvect.api.data.ranking.Example;

import java.util.function.Function;

public interface ExampleDecoder<SHARED, ACTION, OUTCOME> extends Function<String, Example<SHARED, ACTION, OUTCOME>> {
    @Override
    Example<SHARED, ACTION, OUTCOME> apply(String toDecode);
}
