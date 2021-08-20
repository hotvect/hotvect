package com.eshioji.hotvect.api.codec;

import com.eshioji.hotvect.api.data.raw.Example;

import java.util.function.Function;

public interface ExampleDecoder<R> extends Function<String, Example<R>> {
}
