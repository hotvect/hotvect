package com.eshioji.hotvect.api;

import com.eshioji.hotvect.api.codec.ExampleDecoder;
import com.eshioji.hotvect.api.data.raw.Example;

import java.util.function.Function;
import java.util.function.Supplier;

public interface ExampleDecoderFactory<R> extends Supplier<ExampleDecoder<R>> {
}
