package com.eshioji.hotvect.api;

import com.eshioji.hotvect.api.codec.ExampleDecoder;

import java.util.function.Supplier;

public interface ExampleDecoderFactory<R> extends Supplier<ExampleDecoder<R>> {
}
