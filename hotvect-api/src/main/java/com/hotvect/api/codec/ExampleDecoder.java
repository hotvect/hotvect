package com.hotvect.api.codec;

import com.hotvect.api.data.raw.Example;

import java.util.List;
import java.util.function.Function;

public interface ExampleDecoder<R> extends Function<String, List<Example<R>>> {
}
