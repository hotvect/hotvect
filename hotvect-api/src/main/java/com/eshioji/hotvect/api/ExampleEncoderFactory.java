package com.eshioji.hotvect.api;

import com.eshioji.hotvect.api.codec.ExampleEncoder;

import java.util.function.Supplier;

public interface ExampleEncoderFactory<R> extends Supplier<ExampleEncoder<R>> {
}
