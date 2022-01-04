package com.eshioji.hotvect.api.codec.ccb;

import com.eshioji.hotvect.api.data.raw.ccb.Example;

import java.util.function.Function;

public interface ExampleDecoder<SHARED, ACTION> extends Function<String, Example<SHARED, ACTION>> {
    @Override
    Example<SHARED, ACTION> apply(String toDecode);
}
