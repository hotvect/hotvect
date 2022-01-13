package com.hotvect.api.codec.common;

import com.hotvect.api.data.common.Example;

import java.util.function.Function;

public interface ExampleEncoder<EXAMPLE extends Example> extends Function<EXAMPLE, String> {
}
