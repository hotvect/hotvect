package com.eshioji.hotvect.api.codec.ccb;

import com.eshioji.hotvect.api.data.raw.ccb.Example;

import java.util.function.Function;

public interface ExampleDecoder<SHARED, ACTION, OUTCOME> extends Function<String, Example<SHARED, ACTION, OUTCOME>> {
    @Override
    Example<SHARED, ACTION, OUTCOME> apply(String toDecode);
}
