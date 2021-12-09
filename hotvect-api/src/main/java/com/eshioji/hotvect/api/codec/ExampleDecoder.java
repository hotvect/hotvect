package com.eshioji.hotvect.api.codec;

import com.eshioji.hotvect.api.data.raw.Example;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

public interface ExampleDecoder<R> extends Function<String, List<Example<R>>> {
}
